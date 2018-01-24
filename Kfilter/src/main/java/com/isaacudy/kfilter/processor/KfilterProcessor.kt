package com.isaacudy.kfilter.processor

import com.isaacudy.kfilter.Kfilter
import com.isaacudy.kfilter.KfilterMediaFile
import com.isaacudy.kfilter.MediaType
import java.io.File

class KfilterProcessor(kfilter: Kfilter, path: String) {
    private val kfilter = kfilter.copy()
    private val mediaFile = KfilterMediaFile(path)

    var onError: (error: Exception) -> Unit = {}
    var onSuccess: () -> Unit = {}
    var onProgress: (progress: Float) -> Unit = {}

    private var activeThread: Thread? = null

    fun save(saveFile: File){
        save(saveFile.absolutePath)
    }

    fun save(savePath: String){
        val finalPath = getSavePathWithExtension(mediaFile.mediaType, savePath)
        if(activeThread?.isAlive == true){
            throw IllegalStateException("This KfilterProcessor is active. Cannot trigger another save action at this time.")
        }

        if(mediaFile.mediaType == MediaType.NONE){
            onError(IllegalArgumentException("Input file was not a photo or video"))
            return
        }

        val delegate = if(mediaFile.mediaType == MediaType.IMAGE){
            KfilterImageProcessor(kfilter, mediaFile.path, finalPath)
        }
        else {
            KfilterVideoProcessor(kfilter, mediaFile.path, finalPath)
        }

        delegate.onError = onError
        delegate.onSuccess = onSuccess
        delegate.onProgress = onProgress

        activeThread = Thread {
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            try {
                delegate.execute()
            }
            catch (e: Exception) {
                onError(e)
            }
        }.apply { start() }
    }


    abstract class Delegate {
        var onError: (error: Exception) -> Unit = {}
        var onSuccess: () -> Unit = {}
        var onProgress: (progress: Float) -> Unit = {}

        abstract fun execute()
    }
}

internal const val IMAGE_EXTENSION = ".png"
internal const val VIDEO_EXTENSION = ".mp4"

internal fun getSavePathWithExtension(mediaType: MediaType, savePath: String): String {
    var extensionIndex = -1
    for(i in (0 until savePath.length).reversed()){
        val c = savePath[i]
        if(c == '/' || c == '\\'){
            extensionIndex = -1
            break
        }
        if(c == '.'){
            extensionIndex = i
            break
        }
    }

    var noExtension = savePath
    if(extensionIndex > -1){
        noExtension = savePath.substring(0 until extensionIndex)
    }

    if(mediaType == MediaType.IMAGE){
        return noExtension + IMAGE_EXTENSION
    }
    if(mediaType == MediaType.VIDEO){
        return noExtension + VIDEO_EXTENSION
    }
    return noExtension
}