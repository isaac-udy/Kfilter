package com.isaacudy.kfilter

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import java.io.FileInputStream
import java.io.IOException

internal class KfilterMediaFile(val path: String) {

    var mediaWidth = 0
        private set

    var mediaHeight = 0
        private set

    var mediaType = MediaType.NONE

    var error = MediaError.NONE
        private set

    var imageOrientation = 0
        private set

    init {
        val extension = path.split(".").last()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

        when {
            mimeType.startsWith("video") -> processAsVideo()
            mimeType.startsWith("image") -> processAsImage()
            else -> error = MediaError.ERROR_INVALID_FILE_TYPE
        }
    }

    private fun processAsVideo() {
        val retriever = MediaMetadataRetriever()
        try {
            FileInputStream(path).use({
                retriever.setDataSource(it.fd, 0, 0x7ffffffffffffffL)
            })
        }
        catch (ex: IOException) {
            error = MediaError.ERROR_IO
            return
        }
        catch (ex: IllegalStateException) {
            error = MediaError.ERROR_UNKNOWN
            return
        }
        catch (ex: RuntimeException) {
            error = MediaError.ERROR_UNKNOWN
            return
        }

        val isVideoFile = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != null
        if (!isVideoFile) {
            error = MediaError.ERROR_INVALID_FILE_TYPE
            return
        }

        val widthString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val heightString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        mediaWidth = widthString.toInt()
        mediaHeight = heightString.toInt()

        mediaType = MediaType.VIDEO
    }

    private fun processAsImage() {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, options)

        if (options.outWidth > 0 && options.outHeight > 0) {
            mediaType = MediaType.IMAGE

            val exif = ExifInterface(path)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                mediaWidth = options.outHeight
                mediaHeight = options.outWidth
            } else {
                mediaWidth = options.outWidth
                mediaHeight = options.outHeight
            }

            when(orientation){
                ExifInterface.ORIENTATION_NORMAL -> imageOrientation = 0
                ExifInterface.ORIENTATION_ROTATE_90 -> imageOrientation = 90
                ExifInterface.ORIENTATION_ROTATE_180 -> imageOrientation = 180
                ExifInterface.ORIENTATION_ROTATE_270 -> imageOrientation = 270
            }
        }
        else {
            error = if (options.outMimeType.startsWith("image")) {
                MediaError.ERROR_UNKNOWN
            }
            else {
                MediaError.ERROR_INVALID_FILE_TYPE
            }
        }
    }
}

enum class MediaType {
    NONE,
    VIDEO,
    IMAGE
}

enum class MediaError {
    NONE,
    ERROR_IO,
    ERROR_UNKNOWN,
    ERROR_INVALID_FILE_TYPE,
}