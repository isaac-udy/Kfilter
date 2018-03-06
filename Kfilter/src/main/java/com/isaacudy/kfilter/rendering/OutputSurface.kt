/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications copyright (C) 2018 Isaac Udy
 */

package com.isaacudy.kfilter.rendering

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.util.Log
import android.view.Surface

import com.isaacudy.kfilter.Kfilter
import com.isaacudy.kfilter.utils.checkEglError

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * Holds state associated with a Surface used for MediaCodec decoder output.
 *
 *
 * The (width,height) constructor for this class will prepare GL, create a SurfaceTexture,
 * and then create a Surface for that SurfaceTexture.  The Surface can be passed to
 * MediaCodec.configure() to receive decoder output.  When a frame arrives, we latch the
 * texture with updateTexImage, then render the texture with GL to a pbuffer.
 *
 *
 * The no-arg constructor skips the GL preparation step and doesn't allocate a pbuffer.
 * Instead, it just creates the Surface and SurfaceTexture, and when a frame arrives
 * we just draw it on whatever surface is current.
 *
 *
 * By default, the Surface will be using a BufferQueue in asynchronous mode, so we
 * can potentially drop frames.
 */
internal class OutputSurface(kfilter: Kfilter, initEgl: Boolean = false, private val isImage : Boolean = false) : SurfaceTexture.OnFrameAvailableListener {
    private var egl: EGL10? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var surfaceTexture: SurfaceTexture? = null

    /**
     * Returns the Surface that we draw onto.
     */
    var surface: Surface? = null
        private set

    private val mutex = Object() // guards frameAvailable
    private var frameAvailable: Boolean = false
    private var textureRender: KfilterRenderer? = null

    private val width: Int = kfilter.inputWidth
    private val height: Int = kfilter.inputHeight

    /**
     * Creates an OutputSurface backed by a pbuffer with the specifed dimensions.  The new
     * EGL context and surface will be made current.  Creates a Surface that can be passed
     * to MediaCodec.configure().
     */
    init {
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException()
        }
        if(initEgl) {
            eglSetup(width, height)
            makeCurrent()
        }
        setup(kfilter)
    }

    /**
     * Creates instances of KfilterRenderer and SurfaceTexture, and a Surface associated
     * with the SurfaceTexture.
     */
    private fun setup(kfilterShader: Kfilter) {
        kfilterShader.externalTexture.initialise()
        textureRender = KfilterRenderer(kfilterShader).apply { initialise() }

        // Even if we don't access the SurfaceTexture after the constructor returns, we
        // still need to keep a reference to it.  The Surface doesn't retain a reference
        // at the Java level, so if we don't either then the object can get GCed, which
        // causes the native finalizer to run.
        if (VERBOSE) Log.d(TAG, "textureID=" + textureRender!!.textureId)
        surfaceTexture = SurfaceTexture(textureRender!!.textureId)
        surfaceTexture!!.setDefaultBufferSize(width, height)

        // This doesn't work if OutputSurface is created on the thread that CTS started for
        // these test cases.
        //
        // The CTS-created thread has a Looper, and the SurfaceTexture constructor will
        // create a Handler that uses it.  The "frame available" message is delivered
        // there, but since we're not a Looper-based thread we'll never see it.  For
        // this to do anything useful, OutputSurface must be created on a thread without
        // a Looper, so that SurfaceTexture uses the main application Looper instead.
        //
        // Java language note: passing "this" out of a constructor is generally unwise,
        // but we should be able to get away with it here.
        surfaceTexture!!.setOnFrameAvailableListener(this)
        surface = Surface(surfaceTexture)
    }

    /**
     * Prepares EGL.  We want a GLES 2.0 context and a surface that supports pbuffer.
     */
    private fun eglSetup(width: Int, height: Int) {
        egl = EGLContext.getEGL() as EGL10
        eglDisplay = egl!!.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (!egl!!.eglInitialize(eglDisplay, null)) {
            throw RuntimeException("unable to initialize EGL10")
        }
        // Configure EGL for pbuffer and OpenGL ES 2.0.  We want enough RGB bits
        // to be able to tell if the frame is reasonable.
        val attributes = intArrayOf(EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!egl!!.eglChooseConfig(eglDisplay, attributes, configs, 1, numConfigs)) {
            throw RuntimeException("unable to find RGB888+pbuffer EGL config")
        }
        // Configure context for OpenGL ES 2.0.
        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE)
        eglContext = egl!!.eglCreateContext(eglDisplay, configs[0], EGL10.EGL_NO_CONTEXT, contextAttributes)
        checkEglError("eglCreateContext")
        if (eglContext == null) {
            throw RuntimeException("null context")
        }
        // Create a pbuffer surface.  By using this for output, we can use glReadPixels
        // to test values in the output.
        val surfaceAttribs = intArrayOf(EGL10.EGL_WIDTH, width, EGL10.EGL_HEIGHT, height, EGL10.EGL_NONE)
        eglSurface = egl!!.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttribs)
        checkEglError("eglCreatePbufferSurface")
        if (eglSurface == null) {
            throw RuntimeException("surface was null")
        }
    }

    /**
     * Discard all resources held by this class, notably the EGL context.
     */
    fun release() {
        egl?.apply {
            if (eglGetCurrentContext() == eglContext) {
                // Clear the current context and surface to ensure they are discarded immediately.
                eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE,
                        EGL10.EGL_NO_CONTEXT)
            }
            eglDestroySurface(eglDisplay, eglSurface)
            eglDestroyContext(eglDisplay, eglContext)
        }
        surface?.release()

        // this causes a bunch of warnings that appear harmless but might confuse someone:
        //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
        //surfaceTexture.release();
        // null everything out so future attempts to use this object will cause an NPE
        eglDisplay = null
        eglContext = null
        eglSurface = null
        egl = null
        textureRender = null
        surface = null
        surfaceTexture = null
    }

    /**
     * Makes our EGL context and surface current.
     */
    fun makeCurrent() {
        if (egl == null) {
            throw RuntimeException("not configured for makeCurrent")
        }
        try {
            checkEglError("before makeCurrent")
        }
        catch (e: Exception){
            // Pass here, and attempt to make current anyway. If that fails, we'll crash.
        }
        if (!egl!!.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    /**
     * Latches the next buffer into the texture.  Must be called from the thread that created
     * the OutputSurface object, after the onFrameAvailable callback has signaled that new
     * data is available.
     */
    fun awaitNewImage() {
        val TIMEOUT_MS = 1000
        synchronized(mutex) {
            while (!frameAvailable) {
                try {
                    // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                    // stalling the test if it doesn't arrive.
                    mutex.wait(TIMEOUT_MS.toLong())
                    if (!frameAvailable) {
                        // TODO: if "spurious wakeup", continue while loop
                        throw RuntimeException("Surface frame wait timed out")
                    }
                }
                catch (ie: InterruptedException) {
                    // shouldn't happen
                    throw RuntimeException(ie)
                }

            }
            frameAvailable = false
        }
        surfaceTexture?.updateTexImage()
    }

    /**
     * Draws the data from SurfaceTexture onto the current EGL surface.
     */
    fun drawImage() {
        surfaceTexture?.let {
            val time = if(isImage) 0 else it.timestamp / 1_000_000L
            textureRender?.draw(time, it)
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (VERBOSE) Log.d(TAG, "new frame available")
        synchronized(mutex) {
            if (frameAvailable) {
                throw RuntimeException("frameAvailable already set, frame could be dropped")
            }
            frameAvailable = true
            mutex.notifyAll()
        }
    }

    companion object {
        private val TAG = "OutputSurface"
        private val VERBOSE = false
        private val EGL_OPENGL_ES2_BIT = 4
    }
}