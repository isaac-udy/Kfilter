package com.isaacudy.kfilter.utils

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import com.isaacudy.kfilter.KfilterMediaFile
import com.isaacudy.kfilter.MediaType

internal fun loadBitmap(mediaFile: KfilterMediaFile): Bitmap{
    if(mediaFile.mediaType != MediaType.IMAGE){
        throw IllegalArgumentException("Input KfilterMediaFile was not MediaType.IMAGE")
    }

    var bitmap = BitmapFactory.decodeFile(mediaFile.path)

    if (mediaFile.imageOrientation > 0) {
        val matrix = Matrix()
        matrix.postRotate(mediaFile.imageOrientation.toFloat())
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    return bitmap
}
