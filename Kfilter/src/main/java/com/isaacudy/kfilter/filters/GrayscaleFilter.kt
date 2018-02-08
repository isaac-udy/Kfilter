package com.isaacudy.kfilter.filters

import com.isaacudy.kfilter.Kfilter

class GrayscaleFilter : Kfilter() {

    override fun getShader(): String {
        return """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 textureCoord;
            uniform samplerExternalOES externalTexture;
            void main() {
                vec4 color = texture2D(externalTexture, textureCoord);
                float avg = (color.r + color.b + color.g) / 3.0;
                gl_FragColor = vec4(avg, avg, avg, 1.0);
            }
        """
    }

    override fun copy(): Kfilter {
        return GrayscaleFilter()
    }
}