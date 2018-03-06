package com.isaacudy.kfilter

import android.opengl.GLES20
import com.isaacudy.kfilter.filters.metadata.Metadata
import com.isaacudy.kfilter.filters.metadata.MetadataProvider
import com.isaacudy.kfilter.utils.ExternalTexture

/**
 * Kfilter is an abstract class that represents a filter that can be applied to a photo or video.
 *
 * Kfilter
 */
abstract class Kfilter {

    var inputWidth: Int = -1
        private set
    var inputHeight: Int = -1
        private set

    var outputWidth: Int = -1
        private set
    var outputHeight: Int = -1
        private set

    var released = false
        private set
    var initialised = false
        private set

    private var activeShaderProgram: Int = -1

    private var _externalTexture: ExternalTexture = KfilterExternalTexture()

    var externalTexture: ExternalTexture
        get() = _externalTexture
        set(value) {
            if (_externalTexture is KfilterExternalTexture) {
                _externalTexture.release()
            }
            _externalTexture = value
        }

    private val metadataProvider = MetadataProvider(
        mapOf(
            "kfilterTime" to { long: Long ->
                Metadata.Uniform1i(long.toInt())
            }
        )
    )

    /**
     * Create a copy of this Kfilter. It is important that the output Kfilter does not
     * contain any information such as texture ids that are specific to a particular OpenGL context.
     *
     * This method is used to copy a Kfilter before initializing it for graphics rendering in both
     * the KfilterView and KfilterProcessor classes.
     *
     * @return A copy of this Kfilter, with no graphics memory allocated
     */
    abstract fun copy(): Kfilter

    /**
     * An implementation of Kfilter should return a GLSL fragment shader that defines how the
     * Kfilter will be applied to photos and videos. Uniform inputs to the fragment shader can
     * be presented in the onApply method.
     *
     * @return A GLSL fragment shader
     */
    abstract fun getShader(): String

    /**
     * onApply will be called immediately before the Kfilter is applied to a photo or video. This is
     * where any shader specific input can be performed. At it's most basic, this will involve binding
     * the Kfilter's external texture to a samplerExternalOES uniform within the shader.
     *
     * The default implementation will bind the externalTexture to the GL_TEXTURE0 target
     */
    open internal fun onApply() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(externalTexture.id, 0)
    }

    /**
     * Called when the shader is initialised. This is where any initialization code specific to this
     * Kfilter should be called, such as additional texture uploads.
     *
     * The default implementation simply binds the Kfilter's externalTexture to the input shaderProgram
     *
     * @param shaderProgram the created shaderProgram that this Kfilter will be rendered using
     */
    open internal fun onInitialise(shaderProgram: Int) {
        externalTexture.bind(shaderProgram)
    }

    /**
     * Called immediately after the Kfilter has been asked to resize
     */
    open internal fun onResize() {}

    /**
     * Called immediately after the Kfilter has been released. Perform any additional OpenGL cleanup here
     */
    open internal fun onRelease() {}

    /**
     * Initialise the Kfilter, binding the input shaderProgram as the shader that will be used to render
     * the Kfilter
     *
     * @param shaderProgram a valid OpenGL shader program
     * @see onInitialise
     */
    fun initialise(shaderProgram: Int) {
        if (shaderProgram != activeShaderProgram) release()
        if (initialised) return
        metadataProvider.initialiseMetadata(shaderProgram)
        onInitialise(shaderProgram)
        released = false
        initialised = true
    }

    /**
     * Set the width and height of the photo or video that this Kfilter will be rendered onto.
     * @see onResize
     */
    fun resize(width: Int, height: Int, outputWidth: Int = width, outputHeight: Int = height) {
        if (width == inputWidth && height == inputHeight) return

        this.inputWidth = width
        this.inputHeight = height
        this.outputWidth = outputWidth
        this.outputHeight = outputHeight
        onResize()
    }

    /**
     * Apply this Kfilter
     * @see onApply
     */
    fun apply(milliseconds: Long) {
        if (!initialised) {
            return
        }
        metadataProvider.applyMetadata(milliseconds)
        onApply()
    }

    /**
     * Release this Kfilter
     * @see onRelease
     */
    fun release() {
        if (released) return

        if (externalTexture is KfilterExternalTexture) {
            externalTexture.release()
        }
        onRelease()

        released = true
        initialised = false
        activeShaderProgram = -1
    }

    /**
     * JVM finalize implementation
     */
    protected open fun finalize() {
        release()
    }
}

/**
 * KfilterExternalTexture is used by Kfilter when it creates it's own ExternalTexture objects.
 * When Kfilter performs a release, it will release its external texture if it's a KfilterExternalTexture,
 * otherwise it's expected that the creator of the ExternalTexture will release it
 */
internal class KfilterExternalTexture : ExternalTexture()

open class BaseKfilter : Kfilter() {

    override fun copy(): Kfilter {
        return BaseKfilter()
    }

    override fun getShader(): String {
        return """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 textureCoord;
            uniform samplerExternalOES externalTexture;
            void main() {
                gl_FragColor = texture2D(externalTexture, textureCoord);
            }
        """
    }
}