package com.isaacudy.kfilter.filters

import com.isaacudy.kfilter.Kfilter

class SepiaFilter : Kfilter() {

    override fun getShader(): String {
        return """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 textureCoord;
            uniform samplerExternalOES externalTexture;
            void main() {
                vec4 color = texture2D(externalTexture, textureCoord);
                float outputRed =   min((color.r * .393) + (color.g *.769) + (color.b * .189), 1.0);
                float outputGreen = min((color.r * .349) + (color.g *.686) + (color.b * .168), 1.0);
                float outputBlue =  min((color.r * .272) + (color.g *.534) + (color.b * .131), 1.0);
                gl_FragColor = vec4(outputRed, outputGreen, outputBlue, 1.0);
            }
        """
    }

    override fun copy(): Kfilter {
        return SepiaFilter()
    }
}