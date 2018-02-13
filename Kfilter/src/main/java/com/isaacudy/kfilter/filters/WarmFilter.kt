package com.isaacudy.kfilter.filters

import com.isaacudy.kfilter.Kfilter

class WarmFilter : Kfilter() {

    override fun getShader(): String {
        return """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 textureCoord;
            uniform samplerExternalOES externalTexture;

            //https://raw.githubusercontent.com/yulu/ShaderFilter/master/res/raw/warm_fragment_shader.glsl
            //Color gradient
            vec4 RandomGradientWarm() {
                vec4 color;
                vec3 purple = vec3(180./255., 151./255., 202./255.);
                vec3 pink = vec3(213./255., 66./255., 108./255.);

                color.r = textureCoord.y * (purple.r - pink.r) + pink.r;
                color.g = textureCoord.y * (purple.g - pink.g) + pink.g;
                color.b = textureCoord.x * (purple.b - pink.b) + pink.b;
                color.a = 1.;

                return color;
            }

            //Screen blend
            vec3 ScreenBlend(vec3 maskPixelComponent, float alpha, vec3 imagePixelComponent) {
                return 1.0 - (1.0 - (maskPixelComponent * alpha)) * (1.0 - imagePixelComponent);
            }

            //contrast adjust
            vec3 contrast(vec3 color, float contrast) {
                const float PI = 3.14159265;
                return min(vec3(1.0), ((color - 0.5) * (tan((contrast + 1.0) * PI / 4.0) ) + 0.5));
            }

            void main() {
                vec3 color = texture2D(externalTexture, textureCoord).rgb;
                vec4 layer_color = RandomGradientWarm();
                color = ScreenBlend(layer_color.rgb,  .4,  color);
                color = contrast(color, 0.2);
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }

    override fun copy(): Kfilter {
        return WarmFilter()
    }
}