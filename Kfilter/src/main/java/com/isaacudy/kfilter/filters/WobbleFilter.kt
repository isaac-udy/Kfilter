package com.isaacudy.kfilter.filters

import com.isaacudy.kfilter.Kfilter


class WobbleFilter : Kfilter() {
    override fun copy(): Kfilter {
        return WobbleFilter()
    }

    override fun getShader(): String {
        return """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 textureCoord;
            uniform samplerExternalOES externalTexture;

            uniform int kfilterTime;

            const float A = 0.03;
            const float B = 16.0;
            const float C = 3.0;

            const float D = 0.02;
            const float E = 45.0;
            const float F = 9.0;

            float rand(vec2 co){
                return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
            }

            void main() {
                float time = float(kfilterTime) / 1000.0;
                float timeX = time * 0.2;
                float timeY = time * 0.1;
                float x =  A * sin(B * textureCoord.x) * sin(C * timeX);
                float y =  D * sin(E * textureCoord.y) * sin(F * timeY);
                vec2 c = vec2(textureCoord.x + x, textureCoord.y +y);
                vec3 diffuse_color =  texture2D(externalTexture, c).rgb;
                gl_FragColor = vec4(diffuse_color, 1.0);
            }
        """
    }

}