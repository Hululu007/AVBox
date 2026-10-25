package com.github.avbox.osc.player.effect

import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram

internal abstract class VideoAdjustShaderProgram(useHdr: Boolean, fragmentShader: String) :
    BaseGlShaderProgram(useHdr, 1) {

    protected val glProgram: GlProgram

    private val passthrough = useHdr

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, if (passthrough) PASSTHROUGH_SHADER else fragmentShader)
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            if (!passthrough) bindUniforms()
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    protected abstract fun bindUniforms()

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
            """

        const val PASSTHROUGH_SHADER = """
            precision highp float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
            }
            """
    }
}
