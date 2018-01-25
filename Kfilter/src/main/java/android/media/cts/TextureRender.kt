package android.media.cts

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
 */

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix

import com.isaacudy.kfilter.Kfilter
import com.isaacudy.kfilter.utils.*

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Code for rendering a texture onto a surface using OpenGL ES 2.0.
 */
class TextureRender(var kfilter: Kfilter) {
    private val mTriangleVerticesData = floatArrayOf(
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0f, 0f, 0f, 1.0f, -1.0f, 0f, 1f, 0f, -1.0f, 1.0f, 0f, 0f, 1f, 1.0f, 1.0f, 0f, 1f, 1f)
    private val mTriangleVertices: FloatBuffer
    private val mMVPMatrix = FloatArray(16)
    private val mSTMatrix = FloatArray(16)
    private var mProgram: Int = 0
    private var muMVPMatrixHandle: Int = 0
    private var muSTMatrixHandle: Int = 0
    private var maPositionHandle: Int = 0
    private var maTextureHandle: Int = 0

    private var inputWidth: Int = kfilter.outputWidth
    private var inputHeight: Int = kfilter.outputHeight
    private var targetWidth: Int = kfilter.outputWidth
    private var targetHeight: Int = kfilter.outputHeight

    val textureId: Int
        get() = kfilter.externalTexture.id

    init {
        mTriangleVertices = ByteBuffer.allocateDirect(
                mTriangleVerticesData.size * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        mTriangleVertices.put(mTriangleVerticesData).position(0)
        Matrix.setIdentityM(mSTMatrix, 0)
    }

    fun draw(st: SurfaceTexture, scissorAmount: Float = 1f, offset: Boolean = false) {
        if(scissorAmount == 0f){
            return
        }
        if(scissorAmount != 1.0f){
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            var leftOffset = 0
            if (offset) leftOffset = (targetWidth * (1 - scissorAmount)).toInt()
            GLES20.glScissor(leftOffset, 0, (targetWidth * scissorAmount).toInt(), targetHeight)
        }

        checkGlError("onDrawFrame start")
        st.getTransformMatrix(mSTMatrix)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")
        kfilter.apply()
        GLES20.glGetError() // It appears "apply" can sometimes cause erroneous errors
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")
        Matrix.setIdentityM(mMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
        GLES20.glFinish()
    }

    /**
     * Initializes GL state.  Call this after the EGL surface has been created and made current.
     */
    fun surfaceCreated() {
        mProgram = createProgram(VERTEX_SHADER, kfilter.getShader())
        if (mProgram == 0) {
            throw RuntimeException("failed creating program")
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (maPositionHandle == -1) {
            throw RuntimeException("Could not get attrib location for aPosition")
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (maTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for aTextureCoord")
        }
        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (muMVPMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uMVPMatrix")
        }
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix")
        checkGlError("glGetUniformLocation uSTMatrix")
        if (muSTMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uSTMatrix")
        }
        kfilter.initialise(mProgram)
    }

    fun release() {
        GLES20.glDeleteProgram(mProgram)
        kfilter.release()
    }

    companion object {
        private val TAG = "TextureRender"
        private val FLOAT_SIZE_BYTES = 4
        private val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        private val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3
        private val VERTEX_SHADER = "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uSTMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 textureCoord;\n" +
                "void main() {\n" +
                "  gl_Position = uMVPMatrix * aPosition;\n" +
                "  textureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                "}\n"
    }

    fun setDimensions(inputWidth: Int, inputHeight: Int, targetWidth: Int, targetHeight: Int) {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        this.targetWidth = targetWidth
        this.targetHeight = targetHeight
    }
}