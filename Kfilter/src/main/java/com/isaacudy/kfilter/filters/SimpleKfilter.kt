package com.isaacudy.kfilter.filters

import com.isaacudy.kfilter.Kfilter

internal class SimpleKfilter internal constructor(val transforms: List<SimpleKfilterTransform>): Kfilter(){
    override fun getShader(): String {
        return """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 textureCoord;
            uniform samplerExternalOES externalTexture;

            const vec3 W = vec3(0.2125, 0.7154, 0.0721);

            vec3 brightness(vec3 color, float brightness) {
                float scaled = brightness / 2.0;
                if (scaled < 0.0) {
                    return color * (1.0 + scaled);
                } else {
                    return color + ((1.0 - color) * scaled);
                }
            }

            vec3 contrast(vec3 color, float contrast) {
                const float PI = 3.14159265;
                return min(vec3(1.0), ((color - 0.5) * (tan((contrast + 1.0) * PI / 4.0) ) + 0.5));
            }

            vec3 saturation(vec3 color, float sat) {
                float luminance = dot(color, W);
                vec3 gray = vec3(luminance, luminance, luminance);

                vec3 satColor = mix(gray, color, sat+1.0);
                return satColor;
            }

            vec3 overlayBlender(vec3 Color, vec3 filter){
                vec3 filter_result;
                float luminance = dot(filter, W);
                if(luminance < 0.5)
                    filter_result = 2. * filter * Color;
                else
                    filter_result = 1. - (1. - (2. *(filter - 0.5)))*(1. - Color);
                return filter_result;
            }

            vec3 multiplyBlender(vec3 Color, vec3 filter){
                vec3 filter_result;
                float luminance = dot(filter, W);

                if(luminance < 0.5)
                    filter_result = 2. * filter * Color;
                else
                    filter_result = Color;

                return filter_result;
            }

            void main(){
                vec3 color = texture2D(externalTexture, textureCoord).rgb;
                ${processTransforms()}
                gl_FragColor = vec4(color, 1.0);
            }

        """
    }

    override fun copy(): Kfilter {
        return SimpleKfilter(transforms)
    }

    private fun processTransforms(): String {
        var output = ""
        for (transform in transforms){
            output += transform.getShaderCode() + "\n"
        }
        return output
    }
}

internal fun Float.format(digits: Int) = java.lang.String.format("%.${digits}f", this)

sealed class SimpleKfilterTransform {
    abstract fun getShaderCode(): String
}

class BrightnessTransform(val brightness: Float): SimpleKfilterTransform(){
    override fun getShaderCode(): String {
        return "color = brightness(color, ${brightness.format(5)});"
    }
}

class ContrastTransform(val contrast: Float): SimpleKfilterTransform(){
    override fun getShaderCode(): String {
        return "color = contrast(color, ${contrast.format(5)});"
    }
}

class SaturationTransform(val saturation: Float): SimpleKfilterTransform(){
    override fun getShaderCode(): String {
        return "color = saturation(color, ${saturation.format(5)});"
    }
}


class SimpleKfilterBuilder {
    private val transforms: MutableList<SimpleKfilterTransform> = ArrayList()

    fun brightness(brightness: Float): SimpleKfilterBuilder{
        transforms.add(BrightnessTransform(brightness))
        return this
    }

    fun contrast(contrast: Float): SimpleKfilterBuilder{
        transforms.add(ContrastTransform(contrast))
        return this
    }

    fun saturation(saturation: Float): SimpleKfilterBuilder{
        transforms.add(SaturationTransform(saturation))
        return this
    }

    fun build(): Kfilter {
        return SimpleKfilter(transforms)
    }
}