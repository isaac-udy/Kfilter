package com.isaacudy.kfilter.filters.metadata

import android.opengl.GLES20
import android.util.Log

class MetadataProvider(private val metadataMap: Map<String, (Long) -> Metadata>) {

    private val metadataUniformMap = mutableMapOf<String, Int>()

    fun initialiseMetadata(shaderProgram: Int){
        for(name in metadataMap.keys){
            val location = GLES20.glGetUniformLocation(shaderProgram, name)
            if(location >= 0){
                metadataUniformMap[name] = location
            }
            else {
                Log.e("MetadataProvider", "Ignored uniform named $name as it was not present in the shaderProgram when initialising the MetadataProvider")
            }
        }
    }

    fun applyMetadata(milliseconds: Long){
        for(name in metadataMap.keys){
            val uniform = metadataUniformMap[name] ?: continue
            val metadataFunction = metadataMap[name] ?: continue
            val metadataItem = metadataFunction(milliseconds)

            when(metadataItem){
                is Metadata.Uniform1f -> GLES20.glUniform1f(uniform, metadataItem.value)
                is Metadata.Uniform1i -> GLES20.glUniform1i(uniform, metadataItem.value)
            }
        }
    }
}

sealed class Metadata {
    class Uniform1i(val value: Int) : Metadata()
    class Uniform1f(val value: Float) : Metadata()
}