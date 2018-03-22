package com.isaacudy.kfilter.utils

import android.content.ContentValues.TAG
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.util.Log


private fun getMediaCodecInfo(mimeType: String): MediaCodecInfo? {
    val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    for (codecInfo in codecList.codecInfos) {
        if (!codecInfo.isEncoder) {
            continue
        }
        val types = codecInfo.supportedTypes
        for (j in types.indices) {
            if (types[j].equals(mimeType, ignoreCase = true)) {
                return codecInfo
            }
        }
    }
    return null
}

fun getCodecInfo(mimeType: String): CodecInfo? {
    val mediaCodecInfo = getMediaCodecInfo(mimeType)
    val cap = mediaCodecInfo?.getCapabilitiesForType(mimeType) ?: return null

    val info = CodecInfo()
    var highestLevel = 0
    for (lvl in cap.profileLevels) {
        if (lvl.level > highestLevel) {
            highestLevel = lvl.level
        }
    }
    var maxW = 0
    var maxH = 0
    var bitRate = 0
    var fps = 0 // frame rate for the max resolution
    when (highestLevel) {
    // Do not support Level 1 to 2.
        CodecProfileLevel.AVCLevel1, CodecProfileLevel.AVCLevel11, CodecProfileLevel.AVCLevel12, CodecProfileLevel.AVCLevel13, CodecProfileLevel.AVCLevel1b, CodecProfileLevel.AVCLevel2 -> return null
        CodecProfileLevel.AVCLevel21 -> {
            maxW = 352
            maxH = 576
            bitRate = 4000000
            fps = 25
        }
        CodecProfileLevel.AVCLevel22 -> {
            maxW = 720
            maxH = 480
            bitRate = 4000000
            fps = 15
        }
        CodecProfileLevel.AVCLevel3 -> {
            maxW = 720
            maxH = 480
            bitRate = 10000000
            fps = 30
        }
        CodecProfileLevel.AVCLevel31 -> {
            maxW = 1280
            maxH = 720
            bitRate = 14000000
            fps = 30
        }
        CodecProfileLevel.AVCLevel32 -> {
            maxW = 1280
            maxH = 720
            bitRate = 20000000
            fps = 60
        }
        CodecProfileLevel.AVCLevel4 // only try up to 1080p
        -> {
            maxW = 1920
            maxH = 1080
            bitRate = 20000000
            fps = 30
        }
        else -> {
            maxW = 1920
            maxH = 1080
            bitRate = 20000000
            fps = 30
        }
    }
    info.maxWidth = maxW
    info.maxHeight = maxH
    info.maxFps = fps
    info.maxBitrate = bitRate
    Log.d(TAG, "AVC Level 0x" + Integer.toHexString(highestLevel) + " bit rate " + bitRate + " fps " + info.maxFps + " w " + maxW + " h " + maxH)
    return info
}

data class CodecInfo(
    var maxWidth: Int = 0,
    var maxHeight: Int = 0,
    var maxFps: Int = 0,
    var maxBitrate: Int = 0
)