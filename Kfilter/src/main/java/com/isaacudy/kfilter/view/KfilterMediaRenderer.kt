// https://www.virag.si/2014/03/rendering-video-with-opengl-on-android/ & https://github.com/izacus/AndroidOpenGLVideoDemo
package com.isaacudy.kfilter.view

import android.graphics.SurfaceTexture
import android.media.cts.TextureRender
import android.opengl.GLES20
import android.opengl.Matrix

import com.isaacudy.kfilter.utils.ExternalTexture
import com.isaacudy.kfilter.Kfilter

internal class KfilterMediaRenderer(texture: SurfaceTexture, private var mediaWidth: Int, private var mediaHeight: Int, private val externalTexture: ExternalTexture)
    : TextureSurfaceRenderer(texture, mediaWidth, mediaHeight), SurfaceTexture.OnFrameAvailableListener {

    var mediaTexture: SurfaceTexture? = null
        private set
    private var frameAvailable = false
    private var adjustViewport = false

    private var kfilter: TextureRender? = null
    private var queuedPrimaryKfilter: TextureRender? = null
    private var secondaryKfilter: TextureRender? = null
    private var queuedSecondaryKfilter: TextureRender? = null

    var filterOffset: Float = 0f
        set(value) {
            field = value
            if (field > 1f) field = 1f
            if (field < -1f) field = -1f
        }

    private val stMatrix = FloatArray(16)

    private fun setupVertexBuffer() {
        Matrix.setIdentityM(stMatrix, 0)
    }

    private fun setupTexture() {
        if (mediaTexture != null) return
        mediaTexture = SurfaceTexture(externalTexture.id).apply {
            setOnFrameAvailableListener(this@KfilterMediaRenderer)
        }
    }

    override fun draw(): Boolean {
        if (adjustViewport) {
            adjustViewport()
        }

        synchronized(this) {
            queuedPrimaryKfilter?.let {
                kfilter?.release()
                kfilter = it
                queuedPrimaryKfilter = null

                it.surfaceCreated()
//                if (!it.isInitialised) {
//                    it.setDimensions(mediaWidth, mediaHeight, width, height)
//                    it.initialise(externalTexture)
//                }
            }
            queuedSecondaryKfilter?.let {
                secondaryKfilter?.release()
                secondaryKfilter = it
                queuedSecondaryKfilter = null
                it.surfaceCreated()
//                if (!it.isInitialised) {
//                    it.setDimensions(mediaWidth, mediaHeight, width, height)
//                    it.initialise(externalTexture)
//                }
            }
            if (kfilter == null) return false

            if (frameAvailable) {
                mediaTexture?.updateTexImage()
                mediaTexture?.getTransformMatrix(stMatrix)
                frameAvailable = false
            }
            else {
                return false
            }
        }

        val surfaceTexture = mediaTexture ?: return false

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        if (filterOffset == 0f || secondaryKfilter == null) {
            kfilter?.draw(surfaceTexture)
        }
        else {
            val slidingLeft = filterOffset < 0
            val currentOffset = filterOffset
            kfilter?.draw(surfaceTexture, 1 - Math.abs(currentOffset), !slidingLeft)
            secondaryKfilter?.draw(surfaceTexture, Math.abs(currentOffset) * 1.005f, slidingLeft)
        }
        return true
    }

    private fun adjustViewport() {
        val surfaceAspect = height / width.toFloat()
        val videoAspect = mediaHeight / mediaWidth.toFloat()

//        kfilter?.apply { setDimensions(mediaWidth, mediaHeight, width, height) }
//        queuedPrimaryKfilter?.apply { setDimensions(mediaWidth, mediaHeight, width, height) }

        if (surfaceAspect < videoAspect) {
            val newWidth = (height / videoAspect).toInt()
            val xOffset = Math.abs(width - newWidth) / 2
            GLES20.glViewport(xOffset, 0, width - xOffset * 2, height)
        }
        else {
            val newHeight = (width * videoAspect).toInt()
            val yOffset = Math.abs(height - newHeight) / 2
            GLES20.glViewport(0, yOffset, width, height - yOffset * 2)
        }
        adjustViewport = false
        mediaTexture?.apply { setDefaultBufferSize(mediaWidth, mediaHeight) }
    }

    override fun initGLComponents() {
        setupVertexBuffer()
        setupTexture()
    }

    override fun deinitGLComponents() {
        kfilter?.apply { release() }
        queuedPrimaryKfilter?.apply { release() }
        secondaryKfilter?.apply { release() }
        queuedSecondaryKfilter?.apply { release() }

        kfilter = null
        queuedPrimaryKfilter = null
        secondaryKfilter = null
        queuedSecondaryKfilter = null

        mediaTexture?.apply {
            setOnFrameAvailableListener(null)
            release()
        }
        mediaTexture = null
    }

    fun setMediaSize(width: Int, height: Int) {
        this.mediaWidth = width
        this.mediaHeight = height
        adjustViewport = true
    }

    fun setKfilter(kfilter: Kfilter) {
        var alreadySet = false
        this.kfilter?.let {
            if (it.kfilter === kfilter) alreadySet = true
        }
        if (alreadySet) return

        queuedPrimaryKfilter = TextureRender(kfilter).apply {
            setDimensions(mediaWidth, mediaHeight, width, height)
        }
    }

    fun setKfilter(kfilter: Kfilter, secondaryKfilter: Kfilter?) {
        if (this.kfilter?.kfilter == secondaryKfilter && this.secondaryKfilter?.kfilter == kfilter && secondaryKfilter != null) {
            // we are swapping the kfilters
            val currentSecondary = this.secondaryKfilter
            val currentPrimary = this.kfilter
            this.secondaryKfilter = currentPrimary
            this.kfilter = currentSecondary
        }
        else {
            setKfilter(kfilter)
            secondaryKfilter?.let { setSecondaryKfilter(it) }
        }
    }

    fun setSecondaryKfilter(kfilter: Kfilter) {
        var alreadySet = false
        secondaryKfilter?.let {
            if (it.kfilter === kfilter) alreadySet = true
        }
        if (alreadySet) return

        queuedSecondaryKfilter = TextureRender(kfilter).apply {
            setDimensions(mediaWidth, mediaHeight, width, height)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        synchronized(this) {
            frameAvailable = true
        }
    }
}