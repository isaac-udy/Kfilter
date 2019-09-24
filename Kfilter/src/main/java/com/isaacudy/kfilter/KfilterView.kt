
package com.isaacudy.kfilter

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
import com.isaacudy.kfilter.processor.KfilterProcessor
import com.isaacudy.kfilter.rendering.KfilterMediaRenderer
import com.isaacudy.kfilter.utils.ExternalTexture
import com.isaacudy.kfilter.utils.loadBitmap
import java.io.IOException
import java.util.*


private const val MAXIMUM_OPEN_WAIT_TIME = 2000
const val ERROR_NO_ERROR = 0
const val ERROR_TIMEOUT = 1
const val ERROR_MEDIA_PLAYER_CONFIGURE = 2
const val ERROR_SAVE = 3
const val ERROR_UNKNOWN_MEDIA_TYPE = 4
const val ERROR_MEDIA_PLAYER = 5
const val ERROR_RENDERING = 6

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
    var onErrorListener: (errorCode: Int) -> Unit = { Log.e("KfilterView", "ERROR: $it") }
    private var lastError = ERROR_NO_ERROR

    private val gestureDetector = GestureDetector(context, GestureListener())
    var gesturesEnabled = true

    private val kfilters = ArrayList<Kfilter>()

    private var contentFile: KfilterMediaFile? = null
    val contentPath: String?
        get() = contentFile?.path

    private var renderThread: RenderThread? = null
    var mediaPlayer: MediaPlayer? = null
        private set
    private var isPrepared: Boolean = false

    private val externalTexture = ExternalTexture()
    private var mediaRenderer: KfilterMediaRenderer? = null
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    private var isPlayingOnDetach = false
    var selectedKfilterStart = 0
    var isChange: Boolean = false
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
            if (Math.round(kfilterOffset) == kfilters.size)
                return 0
            return Math.round(kfilterOffset)
        }
        set(value) {
            kfilterOffset = value.toFloat()
            mediaRenderer?.apply { filterOffset = 0f }
        }

    init {
        surfaceTextureListener = this

        onPreparedListener = { mediaPlayer ->
            mediaPlayer.isLooping = true
            mediaPlayer.seekTo(0)
            mediaPlayer.start()
        }
    }

    fun setContentPath(path: String) {
        contentFile = KfilterMediaFile(path)
        openContent()
    }

    fun setFilters(incomingFilters: List<Kfilter>) {
        clearFilters()
        kfilters.addAll(incomingFilters)
        selectedKfilter = kfilters.size - 1
        mediaRenderer?.apply { setKfilter(kfilters[selectedKfilter]) }
    }

    private fun clearFilters() {
        for (kfilter in kfilters) {
            kfilter.release()
        }
        kfilters.clear()
        selectedKfilter = 0
    }

    fun getProcessor() : KfilterProcessor? {
        contentFile?.let {
            val selected = kfilters[selectedKfilter]
            return KfilterProcessor(selected, it.path)
        }
        return null
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
        var primary = selectedKfilter
        var secondary = Math.floor(kfilterOffset.toDouble()).toInt()
        if (primary == secondary) {
            secondary = Math.ceil(kfilterOffset.toDouble()).toInt()
        }

        if(primary < 0) primary = 0
        if(secondary < 0) secondary = 0

        mediaRenderer?.apply {
            var primaryKfilter: Kfilter = BaseKfilter()
            if(kfilters.size > primary){
                primaryKfilter = kfilters[primary]
            }

            var secondaryKfilter: Kfilter = BaseKfilter()
            if(kfilters.size > primary){
                secondaryKfilter = kfilters[secondary]
            }

            setKfilter(primaryKfilter, secondaryKfilter)
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

        if (event.action == MotionEvent.ACTION_DOWN) {
            isChange = true
            offsetAnimator?.apply { cancel() }
            offsetAnimator = null
        }

        if (event.action == MotionEvent.ACTION_UP) {
            isChange = false
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

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)

        if (hasWindowFocus) {
            if(isPlayingOnDetach){
                mediaPlayer?.start()
            }
        }
        else {
            isPlayingOnDetach = mediaPlayer?.isPlaying ?: false
            mediaPlayer?.pause()
        }
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

            when (mediaType) {
                MediaType.IMAGE -> openImageContent()
                MediaType.VIDEO -> openVideoContent()
                else -> triggerError(ERROR_UNKNOWN_MEDIA_TYPE)
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
        kfilters.forEach { it.release() }
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

        externalTexture.release()
    }

    private fun openVideoContent() {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(contentFile!!.path)
                setSurface(surface)
                isLooping = true
                setOnPreparedListener { mp ->
                    isPrepared = true
                    onPreparedListener(mp)
                }
                setOnErrorListener { _, what, extra ->
                    onErrorListener(ERROR_MEDIA_PLAYER)
                    false
                }
                prepareAsync()
            }

            renderThread = VideoRenderThread().apply { start() }
        }
        catch (e: IOException) {
            e.printStackTrace()
            triggerError(ERROR_MEDIA_PLAYER_CONFIGURE)
        }
    }

    private fun openImageContent() {
        renderThread = ImageRenderThread().apply { start() }
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
        releaseRenderingResources()
        return false
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
    }
    //endregion

    private fun checkFilterSwipeDirection(isLeft: Boolean, x1: Float, x2: Float) {
        if (isChange) {
            if ((x1 - x2) <= -80) {
                if (selectedKfilterStart == 0 || selectedKfilterStart == kfilters.size - 1) {
                    selectedKfilterStart = kfilters.size - 1
                    isChange = false
                }
            } else if (isLeft && (x1 - x2) >= 80) {
                if (selectedKfilterStart == 0 || selectedKfilterStart == kfilters.size - 1) {
                    selectedKfilterStart = 0
                    isChange = false
                }
            }
        }

    }

    private inner open class RenderThread : Thread() {

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
                onPreRender()
                // don't re-render the image unless the kfilterOffset has changed since last time
                if (lastRenderedPosition != kfilterOffset || mediaPlayer?.isPlaying == true) {
                    mediaRenderer?.let { mediaRenderer ->
                        synchronized(mediaRenderer) {
                            try {
                                onRender()
                                mediaRenderer.apply {
                                    mediaRenderer.mediaTexture?.let {
                                        val time = mediaPlayer?.currentPosition?.toLong() ?: 0L
                                        setFrameTime(time)
                                        onFrameAvailable(it)
                                    }
                                }
                                lastRenderedPosition = kfilterOffset
                            }
                            catch (ex: Exception) {
                                running = false
                                onErrorListener(ERROR_RENDERING)
                            }
                        }
                    }
                }
                Thread.sleep(33)
            }
        }

        fun release() {
            synchronized(this) {
                running = false
            }
            onRelease()
        }

        open fun onPreRender() {}
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
    private inner class VideoRenderThread : RenderThread() {
        override fun onPreRender() {
            if(mediaPlayer == null){
                running = false
            }
        }
    }

    private inner class ImageRenderThread : RenderThread() {
        lateinit var bitmap: Bitmap

        init {
            contentFile?.let { bitmap = loadBitmap(it) }
        }

        override fun onRender() {
            mediaRenderer?.setFrameTime(0)
            surface?.apply {
                val canvas = lockCanvas(null)
                canvas.drawARGB(255, 0, 0, 0)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                unlockCanvasAndPost(canvas)
            }
        }

        override fun onRelease() {
            bitmap.recycle()
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent?): Boolean {
            if (selectedKfilter == 0) {
                selectedKfilter = kfilters.size
            } else if (selectedKfilter == kfilters.size - 1) {
                selectedKfilter = 0
            }
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
            val yDistance: Float = Math.abs(e1.getY() - e2.getY())
            val velocityY1 = Math.abs(velocityY)
            
            if (velocityY1 > 400 && yDistance > 400) return true

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
            checkFilterSwipeDirection(e1.x > e2.x, e1.x, e2.x)
            kfilterOffset = selectedKfilterStart + distance
            return true
        }
    }

}
