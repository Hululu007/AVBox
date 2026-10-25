package com.github.avbox.osc.player.effect

import android.content.Context
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

internal class ColorToneAdjustEffect : GlEffect {

    @Volatile
    var parameters: Parameters = Parameters(PictureProfile.OFF, colorMatrixOf(PictureProfile.OFF))
        private set

    fun setProfile(profile: PictureProfile) {
        parameters = Parameters(profile, colorMatrixOf(profile))
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ColorToneAdjustShaderProgram(useHdr, this)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = parameters.noOp

    class Parameters(profile: PictureProfile, val colorMatrix: FloatArray) {
        val gamma: Float = profile.gamma
        val hue: Float = profile.hue
        val noOp: Boolean = profile.isColorNoOp && profile.isToneNoOp
    }

    private companion object {

        const val LUMA_R = 0.2126f
        const val LUMA_G = 0.7152f
        const val LUMA_B = 0.0722f

        fun colorMatrixOf(profile: PictureProfile): FloatArray {
            val saturation = profile.saturation
            val contrast = profile.contrast
            val brightness = profile.brightness
            val redGain = profile.redGain
            val blueGain = profile.blueGain
            val invSat = 1.0f - saturation
            val offset = brightness + 0.5f * (1.0f - contrast)
            val rr = (LUMA_R * invSat + saturation) * contrast * redGain
            val rg = (LUMA_G * invSat) * contrast * redGain
            val rb = (LUMA_B * invSat) * contrast * redGain
            val gr = (LUMA_R * invSat) * contrast
            val gg = (LUMA_G * invSat + saturation) * contrast
            val gb = (LUMA_B * invSat) * contrast
            val br = (LUMA_R * invSat) * contrast * blueGain
            val bg = (LUMA_G * invSat) * contrast * blueGain
            val bb = (LUMA_B * invSat + saturation) * contrast * blueGain
            return floatArrayOf(
                rr, gr, br, 0.0f,
                rg, gg, bg, 0.0f,
                rb, gb, bb, 0.0f,
                offset * redGain, offset, offset * blueGain, 1.0f,
            )
        }
    }
}

private class ColorToneAdjustShaderProgram(
    useHdr: Boolean,
    private val effect: ColorToneAdjustEffect,
) : VideoAdjustShaderProgram(useHdr, FRAGMENT_SHADER) {

    override fun bindUniforms() {
        val parameters = effect.parameters
        glProgram.setFloatsUniform("uColorMatrix", parameters.colorMatrix)
        glProgram.setFloatUniform("uGamma", parameters.gamma)
        glProgram.setFloatUniform("uHue", parameters.hue)
    }

    private companion object {
        const val FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform mat4 uColorMatrix;
            uniform float uGamma;
            uniform float uHue;
            varying vec2 vTexSamplingCoord;
            vec3 rotateHue(vec3 color, float hue) {
              float angle = radians(hue);
              float s = sin(angle);
              float c = cos(angle);
              float y = dot(color, vec3(0.299, 0.587, 0.114));
              float i = dot(color, vec3(0.596, -0.274, -0.322));
              float q = dot(color, vec3(0.211, -0.523, 0.312));
              float ii = i * c - q * s;
              float qq = i * s + q * c;
              return clamp(vec3(y + 0.956 * ii + 0.621 * qq, y - 0.272 * ii - 0.647 * qq, y - 1.106 * ii + 1.703 * qq), 0.0, 1.0);
            }
            void main() {
              vec4 sample = texture2D(uTexSampler, vTexSamplingCoord);
              vec3 color = clamp((uColorMatrix * vec4(sample.rgb, 1.0)).rgb, 0.0, 1.0);
              if (abs(uGamma - 1.0) > 0.0001) color = pow(color, vec3(1.0 / max(uGamma, 0.0001)));
              if (abs(uHue) > 0.0001) color = rotateHue(color, uHue);
              gl_FragColor = vec4(color, sample.a);
            }
            """
    }
}
