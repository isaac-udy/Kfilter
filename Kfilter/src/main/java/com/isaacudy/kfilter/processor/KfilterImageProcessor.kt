package com.isaacudy.kfilter.processor

import android.graphics.Bitmap
import com.isaacudy.kfilter.rendering.OutputSurface
import android.opengl.*
import com.isaacudy.kfilter.*
import com.isaacudy.kfilter.utils.checkGlError
import com.isaacudy.kfilter.utils.loadBitmap
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.IntBuffer

internal class KfilterImageProcessor(val shader: Kfilter, val mediaFile: KfilterMediaFile, val pathOut: String) : KfilterProcessor.Delegate(){

    override fun execute() {
        Executor().execute()
    }

    private inner class Executor {
        fun execute() {
            onProgress(0f)
            performExecute()
            onProgress(1f)
            onSuccess()
        }

        fun performExecute(){
            shader.resize(mediaFile.mediaWidth, mediaFile.mediaHeight)
            val outputSurface = OutputSurface(shader, true, true)
            outputSurface.makeCurrent()

            val bitmap = loadBitmap(mediaFile)
            val surface = outputSurface.surface ?: return
            val canvas = surface.lockCanvas(null)
            canvas.drawARGB(255, 0, 0, 0)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)

            outputSurface.awaitNewImage()
            outputSurface.drawImage()

            val width =  mediaFile.mediaWidth
            val height =  mediaFile.mediaHeight
            val buffer = IntBuffer.allocate(width * height)
            val flippedBuffer = IntBuffer.allocate(width * height)

            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            checkGlError("glReadPixels")

            buffer.rewind()
            // Convert upside down mirror-reversed image to right-side up normal image.
            for (i in 0 until height) {
                for (j in 0 until width) {
                    flippedBuffer.put((height - i - 1) * width + j, buffer.get(i * width + j))
                }
            }
            flippedBuffer.rewind()

            var bos: BufferedOutputStream? = null
            try {
                bos = BufferedOutputStream(FileOutputStream(pathOut))
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(flippedBuffer)
                bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
                bmp.recycle()
            }
            finally {
                if (bos != null) bos.close()
            }

            outputSurface.release()
        }
    }
}