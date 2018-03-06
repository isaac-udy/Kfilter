/*
 * https://github.com/izacus/AndroidOpenGLVideoDemo/blob/master/LICENSE
 *
 * Modifications copyright (C) 2018 Isaac Udy
 */

package com.isaacudy.kfilter.rendering

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.util.Log

import com.isaacudy.kfilter.utils.ExternalTexture
import com.isaacudy.kfilter.Kfilter

internal class KfilterMediaRenderer(texture: SurfaceTexture, private var mediaWidth: Int, private var mediaHeight: Int, private val externalTexture: ExternalTexture)
    : TextureSurfaceRenderer(texture, mediaWidth, mediaHeight), SurfaceTexture.OnFrameAvailableListener {

    var mediaTexture: SurfaceTexture? = null
        private set

    private var frameTime: Long = 0
    private var frameAvailable = false
    private var adjustViewport = false

    private var kfilter: KfilterRenderer? = null
    private var queuedPrimaryKfilter: KfilterRenderer? = null
    private var secondaryKfilter: KfilterRenderer? = null
    private var queuedSecondaryKfilter: KfilterRenderer? = null

    var filterOffset: Float = 0f
        set(value) {
            field = value
            if (field > 1f) field = 1f
            if (field < -1f) field = -1f
        }

    override fun draw(): Boolean {
        if (adjustViewport) {
            adjustViewport()
        }

        queuedPrimaryKfilter?.let {
            kfilter?.release()
            kfilter = it
            queuedPrimaryKfilter = null

            if (!it.initialised) {
                it.kfilter.externalTexture = externalTexture
                it.setDimensions(mediaWidth, mediaHeight, width, height)
                it.initialise()
            }
        }
        queuedSecondaryKfilter?.let {
            secondaryKfilter?.release()
            secondaryKfilter = it
            queuedSecondaryKfilter = null

            if (!it.initialised) {
                it.kfilter.externalTexture = externalTexture
                it.setDimensions(mediaWidth, mediaHeight, width, height)
                it.initialise()
            }
        }
        if (kfilter == null) return false

        if (frameAvailable) {
            mediaTexture?.updateTexImage()
            frameAvailable = false
        }
        else {
            return false
        }

        val surfaceTexture = mediaTexture ?: return false

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        synchronized(this) {
            if (filterOffset == 0f || secondaryKfilter == null) {
                kfilter?.draw(frameTime, surfaceTexture)
            }
            else {
                val slidingLeft = filterOffset < 0
                val currentOffset = filterOffset
                kfilter?.draw(frameTime, surfaceTexture, 1 - Math.abs(currentOffset), !slidingLeft)
                secondaryKfilter?.draw(frameTime, surfaceTexture, Math.abs(currentOffset) * 1.005f, slidingLeft)
            }
        }
        return true
    }

    private var aspectAdjustedWidth: Int = 0
    private var aspectAdjustedHeight: Int = 0

    private fun adjustViewport() {
        val surfaceAspect = height / width.toFloat()
        val videoAspect = mediaHeight / mediaWidth.toFloat()

        kfilter?.apply { setDimensions(mediaWidth, mediaHeight, width, height) }
        queuedPrimaryKfilter?.apply { setDimensions(mediaWidth, mediaHeight, width, height) }

        if (surfaceAspect < videoAspect) {
            val newWidth = (height / videoAspect).toInt()
            val xOffset = Math.abs(width - newWidth) / 2
            GLES20.glViewport(xOffset, 0, width - xOffset * 2, height)

            aspectAdjustedWidth = newWidth
            aspectAdjustedHeight = height
        }
        else {
            val newHeight = (width * videoAspect).toInt()
            val yOffset = Math.abs(height - newHeight) / 2
            GLES20.glViewport(0, yOffset, width, height - yOffset * 2)

            aspectAdjustedWidth = width
            aspectAdjustedHeight = newHeight
        }
        adjustViewport = false
        mediaTexture?.apply { setDefaultBufferSize(mediaWidth, mediaHeight) }
    }

    override fun initGLComponents() {
        if (mediaTexture != null) return
        mediaTexture = SurfaceTexture(externalTexture.id).apply {
            setOnFrameAvailableListener(this@KfilterMediaRenderer)
        }
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

    fun setKfilter(kfilter: Kfilter, secondaryKfilter: Kfilter? = null) {
        if(this.kfilter?.kfilter === kfilter && this.secondaryKfilter?.kfilter === secondaryKfilter) {
            return
        }

        if (this.kfilter?.kfilter == secondaryKfilter && this.secondaryKfilter?.kfilter == kfilter && secondaryKfilter != null) {
            // we are swapping the kfilters
            synchronized(this) {
                val currentSecondary = this.secondaryKfilter
                val currentPrimary = this.kfilter
                this.secondaryKfilter = currentPrimary
                this.kfilter = currentSecondary
            }
        }
        else {
            setPrimaryKfilter(kfilter)
            if(kfilter !== secondaryKfilter) {
                secondaryKfilter?.let { setSecondaryKfilter(it) }
            }
        }
    }

    private fun setPrimaryKfilter(kfilter: Kfilter) {
        if(this.kfilter?.kfilter === kfilter){
            return
        }

        queuedPrimaryKfilter = KfilterRenderer(kfilter).apply {
            setDimensions(mediaWidth, mediaHeight, width, height)
        }
    }

    private fun setSecondaryKfilter(kfilter: Kfilter) {
        var alreadySet = false
        secondaryKfilter?.let {
            if (it.kfilter === kfilter) alreadySet = true
        }
        if (alreadySet) return

        queuedSecondaryKfilter = KfilterRenderer(kfilter).apply {
            setDimensions(mediaWidth, mediaHeight, width, height)
        }
    }

    fun setFrameTime(milliseconds: Long){
        synchronized(this) {
            frameTime = milliseconds
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        synchronized(this) {
            frameAvailable = true
        }
    }
}