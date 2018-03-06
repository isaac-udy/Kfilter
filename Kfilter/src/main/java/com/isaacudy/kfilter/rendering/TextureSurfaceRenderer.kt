/*
 * https://github.com/izacus/AndroidOpenGLVideoDemo/blob/master/LICENSE
 *
 * Modifications copyright (C) 2018 Isaac Udy
 */

package com.isaacudy.kfilter.rendering

import android.graphics.SurfaceTexture
import android.opengl.*
import android.util.Log

//import javax.microedition.khronos.egl.EGL10
//import javax.microedition.khronos.egl.EGLConfig
//import javax.microedition.khronos.egl.EGLContext
//import javax.microedition.khronos.egl.EGLDisplay
//import javax.microedition.khronos.egl.EGLSurface

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

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var running: Boolean = false

    var isFinished: Boolean = false
        private set

    private val config: IntArray
        get() = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE)

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
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
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

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val eglConfig = chooseEglConfig()
        eglContext = createContext(eglDisplay, eglConfig)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, texture, surfaceAttribs, 0)
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("GL Error: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("GL Make current error: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
        }
    }

    private fun deinitGL() {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)

    }

    private fun createContext(eglDisplay: EGLDisplay?, eglConfig: EGLConfig?): EGLContext {
        val attributes = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        return EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, attributes, 0)
    }

    private fun chooseEglConfig(): EGLConfig? {
        val configsCount = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val attributes = config
        val configChosen = EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, configs.size, configsCount, 0)
        if (!configChosen) {
            throw IllegalArgumentException("Failed to choose config: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
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