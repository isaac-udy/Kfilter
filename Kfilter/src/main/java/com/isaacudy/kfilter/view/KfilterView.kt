package com.isaacudy.kfilter.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import com.isaacudy.kfilter.BaseKfilter
import com.isaacudy.kfilter.Kfilter
import com.isaacudy.kfilter.KfilterMediaFile
import com.isaacudy.kfilter.MediaType
import com.isaacudy.kfilter.processor.KfilterProcessor
import com.isaacudy.kfilter.utils.ExternalTexture
import com.isaacudy.kfilter.utils.loadBitmap
import java.io.IOException
import java.util.*


private const val MAXIMUM_OPEN_WAIT_TIME = 2000
const val ERROR_NO_ERROR = 0
const val ERROR_TIMEOUT = 1
const val ERROR_MEDIA_PLAYER_CONFIGURE = 2
const val ERROR_SAVE = 3

class KfilterView @JvmOverloads constructor(context: Context,
                                            attrs: AttributeSet? = null,
                                            defStyleAttr: Int = 0,
                                            defStyleRes: Int = 0)
    : TextureView(context, attrs, defStyleAttr, defStyleRes), TextureView.SurfaceTextureListener {

    var onPreparedListener: (mediaPlayer: MediaPlayer) -> Unit = {}
        set(value) {
            field = value
            if (isPrepared) {
                mediaPlayer?.let(field)
            }
        }
    var onErrorListener: (errorCode: Int) -> Unit = {}
    private var lastError = ERROR_NO_ERROR

    private val gestureDetector = GestureDetector(context, GestureListener())
    var gesturesEnabled = true

    private val kfilters = ArrayList<Kfilter>()
    private var contentFile: KfilterMediaFile? = null
    private var renderThread: RenderThread? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared: Boolean = false

    private val externalTexture = ExternalTexture()
    private var mediaRenderer: KfilterMediaRenderer? = null
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    private var offsetAnimator: ValueAnimator? = null
    private var kfilterOffset = 0f
        set(value) {
            field = value
            if (field > kfilters.size - 1) field = kfilters.size - 1f
            if (field < 0) field = 0f
            applyKfilterOffset()
        }
    var selectedKfilter: Int
        get() {
            return Math.round(kfilterOffset)
        }
        set(value) {
            kfilterOffset = value.toFloat()
            mediaRenderer?.apply { filterOffset = 0f }
        }

    init {
        surfaceTextureListener = this
    }

    fun setContentPath(path: String) {
        contentFile = KfilterMediaFile(path)
        openContent()
    }

    fun setFilters(incomingFilters: List<Kfilter>) {
        clearFilters()
        kfilters.addAll(incomingFilters)
        mediaRenderer?.apply { setKfilter(kfilters[selectedKfilter]) }
    }

    private fun clearFilters() {
        for (kfilter in kfilters) {
            kfilter.release()
        }
        kfilters.clear()
        selectedKfilter = 0
    }

    fun save(savePath: String) {
        contentFile?.let {
            val selected = kfilters[selectedKfilter]
            val processor = KfilterProcessor(selected, it.path)
            processor.onError = {
                Log.e("KfilterView", "KfilterProcessor encountered an error", it)
                triggerError(ERROR_SAVE)
            }
            processor.save(savePath)
        }
    }

    @JvmOverloads
    fun animateToSelection(selection: Int, duration: Long = -1) {
        var targetPosition = selection
        if (targetPosition < 0) targetPosition = 0
        if (targetPosition >= kfilters.size) targetPosition = kfilters.size - 1

        offsetAnimator?.apply { cancel() }
        offsetAnimator = null

        var animationTime = duration
        if (animationTime < 0) {
            animationTime = (250 * (Math.pow(Math.abs(kfilterOffset - targetPosition).toDouble(), 0.5))).toLong()
        }

        offsetAnimator = ValueAnimator.ofFloat(kfilterOffset, targetPosition.toFloat()).setDuration(animationTime)
        offsetAnimator?.addUpdateListener {
            kfilterOffset = it.animatedValue as Float
        }
        offsetAnimator?.start()
    }

    private fun applyKfilterOffset() {
        val primary = selectedKfilter
        var secondary = Math.floor(kfilterOffset.toDouble()).toInt()
        if (primary == secondary) {
            secondary = Math.ceil(kfilterOffset.toDouble()).toInt()
        }

        mediaRenderer?.apply {
            setKfilter(kfilters[primary], kfilters[secondary])
            filterOffset = (selectedKfilter - kfilterOffset)
        }
    }

    private fun triggerError(errorCode: Int) {
        if (lastError == errorCode) return  // only display each error once
        if (errorCode == ERROR_NO_ERROR) {
            lastError = errorCode // don't trigger error listener if the error is "no error"
        }
        else {
            onErrorListener(errorCode)
            lastError = errorCode
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled) return false

        if (event.action == MotionEvent.ACTION_UP) {
            offsetAnimator?.apply { cancel() }
            offsetAnimator = null

            offsetAnimator = ValueAnimator.ofFloat(kfilterOffset, selectedKfilter.toFloat()).setDuration(225)
            offsetAnimator?.addUpdateListener {
                kfilterOffset = it.animatedValue as Float
            }
            offsetAnimator?.start()
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    //region Rendering
    private fun openContent() {
        synchronized(this) {
            if (contentFile == null) {
                return
            }
            if (texture == null) {
                return
            }

            if (kfilters.size == 0) {
                selectedKfilter = 0
                kfilters.add(BaseKfilter())
            }

            releaseRenderingResources()
            prepareRenderingResources()

            val mediaType = contentFile?.mediaType ?: MediaType.NONE

            if (mediaType === MediaType.IMAGE) {
                openImageContent()
            }
            else if (mediaType == MediaType.VIDEO) {
                openVideoContent()
            }
        }
    }

    private fun prepareRenderingResources() {
        val texture = texture ?: return

        mediaRenderer = KfilterMediaRenderer(texture, surfaceWidth, surfaceHeight, externalTexture).apply {
            setMediaSize(contentFile?.mediaWidth ?: -1, contentFile?.mediaHeight ?: -1)
            setKfilter(kfilters[selectedKfilter])
        }

        var videoTexture: SurfaceTexture? = null
        val startTime = System.currentTimeMillis()
        while (videoTexture == null) {
            if (System.currentTimeMillis() - startTime > MAXIMUM_OPEN_WAIT_TIME) {
                triggerError(ERROR_TIMEOUT)
                return
            }
            videoTexture = mediaRenderer?.mediaTexture
        }

        surface?.let {
            synchronized(it) {
                it.release()
            }
        }
        surface = Surface(videoTexture)
    }

    private fun releaseRenderingResources() {
        mediaRenderer?.release()
        mediaRenderer = null

        this.renderThread?.release()
        this.renderThread = null

        mediaPlayer?.apply {
            reset()
            release()
        }
        mediaPlayer = null
        isPrepared = false

        surface?.apply { release() }
        surface = null
    }

    private fun openVideoContent() {
        try {
            mediaPlayer = MediaPlayer().apply {
                setOnPreparedListener(onPreparedListener)
                setDataSource(contentFile!!.path)
                setSurface(surface)
                isLooping = true
                setOnPreparedListener { mp ->
                    isPrepared = true
                    onPreparedListener(mp)
                }
                prepareAsync()
            }
            mediaPlayer?.let { mediaPlayer ->
                mediaRenderer?.let { mediaRenderer ->
                    renderThread = VideoRenderThread(mediaRenderer, mediaPlayer).apply { start() }
                }
            }
        }
        catch (e: IOException) {
            e.printStackTrace()
            triggerError(ERROR_MEDIA_PLAYER_CONFIGURE)
        }
    }

    private fun openImageContent() {
        val bitmap = loadBitmap(contentFile ?: return)
        try {
            surface?.apply {
                val canvas = lockCanvas(null)
                canvas.drawARGB(255, 0, 0, 0)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                unlockCanvasAndPost(canvas)
            }
        }
        catch (ex: Exception) {
            // it's possible for the surface to become invalid, and cause a crash here
            // if that happens, just return
            return
        }
        finally {
            bitmap.recycle()
        }

        mediaRenderer?.let { mediaRenderer ->
            renderThread = RenderThread(mediaRenderer).apply { start() }
        }
    }
    //endregion

    //region SurfaceTextureListener
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        texture = st
        openContent()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        texture = st
        openContent()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        kfilters.forEach { it.release() }
        releaseRenderingResources()
        return false
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    //endregion

    private inner open class RenderThread(val mediaRenderer: KfilterMediaRenderer) : Thread() {

        internal var running = false

        override fun start() {
            synchronized(this) {
                running = true
            }
            super.start()
        }

        override fun run() {
            var lastRenderedPosition = -1f

            while (synchronized(this) { running }) {
                onRender()

                // don't re-render the image unless the kfilterOffset has changed since last time
                if (lastRenderedPosition != this@KfilterView.kfilterOffset) {
                    synchronized(mediaRenderer) {
                        try {
                            mediaRenderer.apply {
                                mediaRenderer.mediaTexture?.let {
                                    onFrameAvailable(it)
                                }
                            }
                            lastRenderedPosition = this@KfilterView.kfilterOffset
                        }
                        catch (ex: Exception) {
                            running = false
                        }
                    }
                }
                Thread.sleep(16)
            }
        }

        fun release() {
            synchronized(this) {
                running = false
            }
            onRelease()
        }

        open fun onRender() {}
        open fun onRelease() {}
    }

    /**
     * This class watches a media player, and will trigger extra rendering calls while the media player
     * is paused/not playing
     *
     * Without triggering manual rendering of the video through KfilterMediaRenderer.onFrameAvailable,
     * the video will pause, but the live preview of filters will also stop, which is not ideal.
     */
    private inner class VideoRenderThread(mediaRenderer: KfilterMediaRenderer, val mediaPlayer: MediaPlayer) : RenderThread(mediaRenderer) {
        override fun onRender() {
            try {
                while (mediaPlayer.isPlaying) {
                    Thread.sleep(66)
                }
            }
            catch (ex: IllegalStateException) {
                // if mediaPlayer.isPlaying throws an exception, that means it's no longer valid,
                // so we stop running
                running = false
            }
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        var selectedKfilterStart = 0

        override fun onDown(e: MotionEvent?): Boolean {
            selectedKfilterStart = selectedKfilter
            return super.onDown(e)
        }

        override fun onSingleTapUp(e: MotionEvent?): Boolean {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                }
                else {
                    it.start()
                }
            }
            return super.onSingleTapUp(e)
        }

        override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val direction = if (velocityX < 0) 1 else -1
            if (Math.abs(velocityX) > 1000) {
                offsetAnimator?.apply { cancel() }
                offsetAnimator = null

                offsetAnimator = ValueAnimator.ofFloat(kfilterOffset, (selectedKfilterStart + direction).toFloat()).setDuration(225)
                offsetAnimator?.addUpdateListener {
                    kfilterOffset = it.animatedValue as Float
                }
                offsetAnimator?.start()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val distance = (e1.x - e2.x) / surfaceWidth
            kfilterOffset = selectedKfilterStart + distance
            return true
        }
    }
}