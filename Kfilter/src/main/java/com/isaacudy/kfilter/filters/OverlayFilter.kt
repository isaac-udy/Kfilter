package com.isaacudy.kfilter.filters

import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.isaacudy.kfilter.Kfilter
import com.isaacudy.kfilter.utils.checkGlError
import java.lang.IllegalStateException
import java.lang.RuntimeException
import java.nio.IntBuffer

open class OverlayFilter(internal val overlayItems: List<OverlayItem> = listOf()) : Kfilter() {
    internal val TAG = "OverlayFilter"

    override fun getShader(): String {
        return """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 textureCoord;
            uniform samplerExternalOES externalTexture;
            uniform sampler2D overlayTexture;
            void main() {
                vec4 externalColor = texture2D(externalTexture, textureCoord);
                vec4 overlayColor = texture2D(overlayTexture, textureCoord);

                gl_FragColor = vec4(
                    overlayColor.r + (externalColor.r * (1.0 - overlayColor.a)),
                    overlayColor.g + (externalColor.g * (1.0 - overlayColor.a)),
                    overlayColor.b + (externalColor.b * (1.0 - overlayColor.a)),
                    1
                );
            }
        """
    }

    private var overlayTextureId: Int = -0xBADA55
    private var overlayTextureHandle: Int = -0xBADA55
    private var overlayBitmap: Bitmap? = null

    override fun onInitialise(shaderProgram: Int) {
        externalTexture.bind(shaderProgram)

        overlayTextureHandle = GLES20.glGetUniformLocation(shaderProgram, "overlayTexture")
        checkGlError("glGetUniformLocation overlayTexture")
        if (overlayTextureHandle == -1) {
            throw IllegalStateException("Failed to get texture handle for overlayTexture")
        }

        if(overlayBitmap == null) {
            overlayBitmap = OverlayItem.createOverlayBitmap(overlayItems, inputWidth, inputHeight)
        }
        else {
            overlayBitmap?.let {
                if(it.width != inputWidth || it.height != inputHeight){
                    it.recycle()
                    overlayBitmap = OverlayItem.createOverlayBitmap(overlayItems, inputWidth, inputHeight)
                }
            }
        }

        overlayTextureId = overlayBitmap?.let { OverlayItem.loadTexture(it) } ?: -1
        Log.d(TAG, "Loaded overlay texture with ID: " + overlayTextureId)
        checkGlError("loadTexture")
    }

    override fun onApply() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(externalTexture.id, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(overlayTextureHandle, 1)
    }

    override fun onRelease() {
        val overlayBuffer = IntBuffer.allocate(1)
        overlayBuffer.put(0, overlayTextureId)
        GLES20.glDeleteTextures(1, overlayBuffer)
    }

    override fun finalize() {
        super.finalize()
        overlayBitmap?.recycle()
    }

    override fun copy(): Kfilter {
        return OverlayFilter(overlayItems)
    }
}

data class OverlayItem(val bitmapLocation: String, val position: Long = OVERLAY, val size: Float = 0.2f, val index: Int = 0) {

    var bitmap: Bitmap? = null

    private constructor(): this("")

    companion object {
        const val TAG = "OverlayItem"

        const val OVERLAY = 0L
        const val TOP_LEFT = 1L
        const val TOP_CENTER = 2L
        const val TOP_RIGHT = 3L
        const val CENTER_LEFT = 4L
        const val CENTER_CENTER = 5L
        const val CENTER_RIGHT = 6L
        const val BOTTOM_LEFT = 7L
        const val BOTTOM_CENTER = 8L
        const val BOTTOM_RIGHT = 9L

        fun loadTexture(overlayBitmap: Bitmap): Int {
            if (overlayBitmap.width < 0 && overlayBitmap.height < 0) {
                Log.d(TAG, "Failed to loadTexture with width=${overlayBitmap.width} and height=${overlayBitmap.height}")
                return -1
            }
            val textureHandle = IntArray(1)
            GLES20.glGenTextures(1, textureHandle, 0)
            if (textureHandle[0] != 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0)
            }

            if (textureHandle[0] == 0) {
                throw RuntimeException("Error loading texture.")
            }
            return textureHandle[0]
        }

        fun createOverlayBitmap(overlayItems: List<OverlayItem>, outputWidth: Int, outputHeight: Int): Bitmap {
            val conf = Bitmap.Config.ARGB_8888
            val overlayBitmap = Bitmap.createBitmap(outputWidth, outputHeight, conf)

            val canvas = Canvas(overlayBitmap)
            val paint = Paint()
            paint.isAntiAlias = true
            paint.isFilterBitmap = true

            overlayItems.sortedBy({ it.position }).sortedBy { it.index }.forEach {
                item ->
                item.bitmap = BitmapFactory.decodeFile(item.bitmapLocation)
                val bitmap = item.bitmap ?: return@forEach

                val itemWidth = bitmap.width
                val itemHeight = bitmap.height
                val srcRect = Rect(0, 0, itemWidth, itemHeight)

                val itemAspect = itemHeight.toFloat() / itemWidth.toFloat()

                val dstWidth = (outputWidth * item.size).toInt()
                val dstHeight = (dstWidth * itemAspect).toInt()

                var dstRect: Rect
                when (item.position) {
                    OVERLAY ->
                        dstRect = Rect(0, 0, outputWidth, outputHeight)

                    TOP_LEFT ->
                        dstRect = Rect(0, 0, dstWidth, dstHeight)

                    TOP_CENTER ->
                        dstRect = Rect(outputWidth / 2 - dstWidth / 2, 0, outputWidth / 2 + dstWidth / 2, dstHeight)

                    TOP_RIGHT ->
                        dstRect = Rect(outputWidth - dstWidth, 0, outputWidth, dstHeight)

                    CENTER_LEFT ->
                        dstRect = Rect(0, outputHeight / 2 - dstHeight / 2, dstWidth, outputHeight / 2 + dstHeight / 2)

                    CENTER_CENTER ->
                        dstRect = Rect(outputWidth / 2 - dstWidth / 2, outputHeight / 2 - dstHeight / 2, outputWidth / 2 + dstWidth / 2, outputHeight / 2 + dstHeight / 2)

                    CENTER_RIGHT ->
                        dstRect = Rect(outputWidth - dstWidth, outputHeight / 2 - dstHeight / 2, outputWidth, outputHeight / 2 + dstHeight / 2)

                    BOTTOM_LEFT ->
                        dstRect = Rect(0, outputHeight - dstHeight, dstWidth, outputHeight)

                    BOTTOM_CENTER ->
                        dstRect = Rect(outputWidth / 2 - dstWidth / 2, outputHeight - dstHeight, outputWidth / 2 + dstWidth / 2, outputHeight)

                    BOTTOM_RIGHT ->
                        dstRect = Rect(outputWidth - dstWidth, outputHeight - dstHeight, outputWidth, outputHeight)

                    else -> dstRect = Rect(0, 0, dstWidth, dstHeight)
                }
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
                bitmap.recycle()
            }

            return overlayBitmap
        }
    }
}

