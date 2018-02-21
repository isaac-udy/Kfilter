package com.isaacudy.kfilter.utils

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import com.isaacudy.kfilter.KfilterMediaFile
import com.isaacudy.kfilter.MediaType

internal fun loadBitmap(mediaFile: KfilterMediaFile): Bitmap {
    if (mediaFile.mediaType != MediaType.IMAGE) {
        throw IllegalArgumentException("Input KfilterMediaFile was not MediaType.IMAGE")
    }

    val boundsOnly = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(mediaFile.path, boundsOnly)
    val scaling = getBitmapDownscaling(boundsOnly.outWidth, boundsOnly.outHeight)

    val options = BitmapFactory.Options().apply {
        inSampleSize = scaling
    }
    var bitmap = BitmapFactory.decodeFile(mediaFile.path, options)

    if (mediaFile.orientation > 0) {
        val matrix = Matrix()
        matrix.postRotate(mediaFile.orientation.toFloat())
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    return bitmap
}

internal fun getBitmapDownscaling(w: Int, h: Int): Int {
    var width = w
    var height = h
    var scaling = 1
    while (width > 4096 || height > 4096) {
        scaling *= 2
        width = w / scaling
        height = h / scaling
    }
    return scaling
}