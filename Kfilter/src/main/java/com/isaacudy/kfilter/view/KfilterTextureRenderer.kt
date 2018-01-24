package com.isaacudy.kfilter.view

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.isaacudy.kfilter.Kfilter
import com.isaacudy.kfilter.utils.ExternalTexture
import com.isaacudy.kfilter.utils.checkGlError
import com.isaacudy.kfilter.utils.createProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val VERTEX_SHADER_CODE = """
    uniform mat4 uMVPMatrix;
    uniform mat4 uSTMatrix;
    attribute vec4 aPosition;
    attribute vec4 aTextureCoord;
    varying vec2 textureCoord;

    void main() {
        gl_Position = uMVPMatrix * aPosition;
        textureCoord = (uSTMatrix * aTextureCoord).xy;
    }
"""
private const val FLOAT_SIZE_BYTES = 4
private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3
private val triangleVerticesData = floatArrayOf(
        // X, Y, Z, U, V
        -1.0f, -1.0f, 0f, 0f, 0f,
        1.0f, -1.0f, 0f, 1f, 0f,
        -1.0f, 1.0f, 0f, 0f, 1f,
        1.0f, 1.0f, 0f, 1f, 1f
)

internal class KfilterTextureRenderer constructor(val kfilter: Kfilter) {
    private val triangleVertices = ByteBuffer
            .allocateDirect(triangleVerticesData.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(triangleVerticesData)

    private var shaderProgram: Int = 0

    private val mvpMatrix = FloatArray(16)
    private val surfaceMatrix = FloatArray(16)
    private var mvpMatrixHandle: Int = 0
    private var stMatrixHandle: Int = 0
    private var positionHandle: Int = 0
    private var textureHandle: Int = 0

    private var targetWidth: Int = kfilter.outputWidth
    private var targetHeight: Int = kfilter.outputWidth

    private var inputWidth: Int = kfilter.outputWidth
    private var inputHeight: Int = kfilter.outputWidth

    var isInitialised = false
        private set

    @JvmOverloads
    fun initialise(externalTexture: ExternalTexture = kfilter.externalTexture) {
        kfilter.externalTexture = externalTexture

        shaderProgram = createProgram(VERTEX_SHADER_CODE, kfilter.getShader())

        positionHandle = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (positionHandle == -1) {
            throw RuntimeException("Could not get attrib location for aPosition")
        }
        textureHandle = GLES20.glGetAttribLocation(shaderProgram, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (textureHandle == -1) {
            throw RuntimeException("Could not get attrib location for aTextureCoord")
        }
        mvpMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (mvpMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uMVPMatrix")
        }
        stMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uSTMatrix")
        checkGlError("glGetUniformLocation uSTMatrix")
        if (stMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uSTMatrix")
        }
        kfilter.resize(inputWidth, inputHeight)
        kfilter.initialise(shaderProgram)
        val status = IntArray(1)
        GLES20.glGetProgramiv(shaderProgram, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(shaderProgram)
            Log.e("SurfaceTest", "Error while linking program:\n" + error)
        }
        isInitialised = true
    }

    @JvmOverloads
    fun draw(surfaceTexture: SurfaceTexture, scissorAmount: Float = 1f, offset: Boolean = false) {
        if(!kfilter.initialised) kfilter.initialise(shaderProgram)
        surfaceTexture.getTransformMatrix(surfaceMatrix)

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        var leftOffset = 0
        if (offset) leftOffset = (targetWidth * (1 - scissorAmount)).toInt()
        GLES20.glScissor(leftOffset, 0, (targetWidth * scissorAmount).toInt(), targetHeight)

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(shaderProgram)
        checkGlError("glUseProgram")

        kfilter.apply()
        GLES20.glGetError() // This *always* seems to cause an error, but for no particular reason

        triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        checkGlError("glEnableVertexAttribArray positionHandle")
        triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices)
        checkGlError("glVertexAttribPointer textureHandle")
        GLES20.glEnableVertexAttribArray(textureHandle)
        checkGlError("glEnableVertexAttribArray textureHandle")
        Matrix.setIdentityM(mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, surfaceMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
    }

    fun setDimensions(inputWidth: Int, inputHeight: Int, targetWidth: Int, targetHeight: Int) {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        this.targetWidth = targetWidth
        this.targetHeight = targetHeight
    }

    fun release() {
        GLES20.glDeleteProgram(shaderProgram)
        kfilter.release()
    }

}