/*
 * https://github.com/izacus/AndroidOpenGLVideoDemo/blob/master/LICENSE
 *
 * Modifications copyright (C) 2018 Isaac Udy
 */

package com.isaacudy.kfilter.rendering

import android.graphics.SurfaceTexture
import android.opengl.GLUtils
import android.util.Log

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

private const val EGL_OPENGL_ES2_BIT = 4
private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
private const val LOG_TAG = "SurfaceTest.GL"

/**
 * Renderer which initializes OpenGL 2.0 context on a passed surface and starts a rendering thread
 *
 *
 * This class has to be subclassed to be used properly

 * @param texture Surface texture on which to render. This has to be called AFTER the texture became available
 * @param width   Width of the passed surface
 * @param height  Height of the passed surface
 */
internal abstract class TextureSurfaceRenderer(private val texture: SurfaceTexture, protected var width: Int, protected var height: Int) : Runnable {

    private var egl: EGL10? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var running: Boolean = false

    var isFinished: Boolean = false
        private set

    private var lastFpsOutput: Long = 0
    private var frames: Int = 0

    private val config: IntArray
        get() = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_NONE)

    init {
        this.running = true
        val thrd = Thread(this)
        thrd.start()
    }

    override fun run() {
        initGL()
        initGLComponents()
        Log.d(LOG_TAG, "OpenGL init OK.")
        while (running) {
            val loopStart = System.currentTimeMillis()

            if (draw()) {
                egl?.eglSwapBuffers(eglDisplay, eglSurface)
            }
            val waitDelta = 16 - (System.currentTimeMillis() - loopStart)    // Targeting 60 fps, no need for faster
            if (waitDelta > 0) {
                try {
                    Thread.sleep(waitDelta)
                }
                catch (e: InterruptedException) {
                    continue
                }

            }
        }
        deinitGLComponents()
        deinitGL()
        isFinished = true
    }

    /**
     * Main draw function, subclass this and add custom drawing code here. The rendering thread will attempt to limit
     * FPS to 60 to keep CPU usage low.
     */
    protected abstract fun draw(): Boolean

    /**
     * OpenGL component initialization funcion. This is called after OpenGL context has been initialized on the rendering thread.
     * Subclass this and initialize shaders / textures / other GL related components here.
     */
    protected abstract fun initGLComponents()

    protected abstract fun deinitGLComponents()

    /**
     * Call when activity pauses. This stops the rendering thread and deinitializes OpenGL.
     */
    fun onPause() {
        release()
    }

    private fun initGL() {
        (EGLContext.getEGL() as EGL10).let {
            eglDisplay = it.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            it.eglInitialize(eglDisplay, version)
            val eglConfig = chooseEglConfig()
            eglContext = createContext(it, eglDisplay, eglConfig)
            eglSurface = it.eglCreateWindowSurface(eglDisplay, eglConfig, texture, null)
            if (eglSurface == null || eglSurface === EGL10.EGL_NO_SURFACE) {
                throw RuntimeException("GL Error: " + GLUtils.getEGLErrorString(it.eglGetError()))
            }
            if (!it.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("GL Make current error: " + GLUtils.getEGLErrorString(it.eglGetError()))
            }

            egl = it
        }
    }

    private fun deinitGL() {
        egl?.apply {
            eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            eglDestroySurface(eglDisplay, eglSurface)
            eglDestroyContext(eglDisplay, eglContext)
            eglTerminate(eglDisplay)
        }
    }

    private fun createContext(egl: EGL10, eglDisplay: EGLDisplay?, eglConfig: EGLConfig?): EGLContext {
        val attributes = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
        return egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attributes)
    }

    private fun chooseEglConfig(): EGLConfig? {
        val configsCount = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val configSpec = config
        val configChosen = egl?.eglChooseConfig(eglDisplay, configSpec, configs, 1, configsCount) ?: false
        if (!configChosen) {
            throw IllegalArgumentException("Failed to choose config: " + GLUtils.getEGLErrorString(egl?.eglGetError() ?: 0))
        }
        else if (configsCount[0] > 0) {
            return configs[0]
        }
        return null
    }

    fun release() {
        running = false
    }

    fun finalize() {
        release()
    }
}