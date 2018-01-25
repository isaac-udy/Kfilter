package com.isaacudy.kfilter.utils

import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.lang.IllegalStateException
import java.nio.IntBuffer

open class ExternalTexture {

    var id: Int = -1
        get
        private set

    fun initialise(){
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        // Load the data from the buffer into the texture handle.
        GLES20.glTexImage2D( GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, 0, 0, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null)
        id = textures[0]
    }

    fun bind(shaderProgram: Int) {
        if(id == -1) initialise()

        val externalTextureHandle = GLES20.glGetUniformLocation(shaderProgram, "externalTexture")
        checkGlError("glGetUniformLocation externalTexture")
        if (externalTextureHandle == -1) {
            throw IllegalStateException("Failed to get texture handle for externalTexture")
        }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        checkGlError("glBindTexture id")
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter")
    }

    fun release(){
        GLES20.glDeleteTextures(1, IntBuffer.wrap(intArrayOf(id)))
        id = -1
    }
}

val TAG = "KGLU"

fun checkGlError(op: String) {
    val error = GLES20.glGetError()
    if (error != GLES20.GL_NO_ERROR) {
        Log.e(TAG, op + ": glError " + error)
//        throw RuntimeException(op + ": glError " + error)
    }
}

fun checkEglError(msg: String) {
    var failed = false
    while (true) {
        val error = EGL14.eglGetError()
        if(error != EGL14.EGL_SUCCESS) {
            Log.e(TAG, msg + ": EGL error: 0x" + Integer.toHexString(error))
            failed = true
        }
        else {
            break
        }
    }

    if (failed) {
        throw RuntimeException("EGL error encountered (see log)")
    }
}

fun loadShader(shaderType: Int, source: String): Int {
    var shader = GLES20.glCreateShader(shaderType)
    checkGlError("glCreateShader type=" + shaderType)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
        Log.e(TAG, "Could not compile shader $shaderType:")
        Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader))
        GLES20.glDeleteShader(shader)
        shader = 0
    }
    return shader
}

fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    if (vertexShader == 0) {
        return 0
    }
    val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    if (pixelShader == 0) {
        return 0
    }
    var program = GLES20.glCreateProgram()
    checkGlError("glCreateProgram")
    if (program == 0) {
        Log.e(TAG, "Could not create program")
    }
    GLES20.glAttachShader(program, vertexShader)
    checkGlError("glAttachShader")
    GLES20.glAttachShader(program, pixelShader)
    checkGlError("glAttachShader")
    GLES20.glLinkProgram(program)
    val linkStatus = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
    if (linkStatus[0] != GLES20.GL_TRUE) {
        Log.e(TAG, "Could not link program: ")
        Log.e(TAG, GLES20.glGetProgramInfoLog(program))
        GLES20.glDeleteProgram(program)
        program = 0
    }
    return program
}