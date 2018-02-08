package com.isaacudy.kfilter.filters

import com.isaacudy.kfilter.Kfilter

class PosterizeFilter(val colourCount: Int = 4) : Kfilter() {

    override fun getShader(): String {
        return """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 textureCoord;
            uniform samplerExternalOES externalTexture;
            void main() {
                vec4 color = texture2D(externalTexture, textureCoord);
                float outputRed =   float(int(color.r * $colourCount.0)) / $colourCount.0;
                float outputGreen = float(int(color.g * $colourCount.0)) / $colourCount.0;
                float outputBlue =  float(int(color.b * $colourCount.0)) / $colourCount.0;
                gl_FragColor = vec4(outputRed, outputGreen, outputBlue, 1.0);
            }
        """
    }

    override fun copy(): Kfilter {
        return PosterizeFilter(colourCount)
    }
}