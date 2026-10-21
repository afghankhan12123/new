package com.videoeditor.core.media.composition

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.videoeditor.core.model.ChromaKeySettings

/**
 * Real GPU chroma key (green/blue screen removal) shader effect.
 * Converts RGB to YCbCr, computes chroma distance, and suppresses color spill.
 */
@UnstableApi
class ChromaKeyGlEffect(private val settings: ChromaKeySettings?, private val liveSettings: (() -> ChromaKeySettings?)? = null) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        liveSettings == null && (settings == null || !settings.enabled)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(useHdr, settings ?: ChromaKeySettings(), liveSettings)

    private class Program(useHdr: Boolean, private val config: ChromaKeySettings, private val liveSettings: (() -> ChromaKeySettings?)?) : BaseGlShaderProgram(useHdr, 1) {
        private val gl = GlProgram(VERTEX, FRAGMENT)

        init {
            gl.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            gl.setBufferAttribute("aTexCoord", GlUtil.getTextureCoordinateBounds(), 4)

        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            gl.use()
            val current = if (liveSettings != null) liveSettings.invoke() else config
            val config = current ?: ChromaKeySettings()
            gl.setFloatUniform("uEnabled", if (current?.enabled == true) 1f else 0f)
            gl.setFloatsUniform("uKeyColor", floatArrayOf(config.keyRed, config.keyGreen, config.keyBlue))
            gl.setFloatUniform("uTolerance", config.tolerance.coerceIn(0.01f, 1.0f))
            gl.setFloatUniform("uSoftness", config.softness.coerceIn(0.001f, 1.0f))
            gl.setFloatUniform("uSpillSuppression", config.spillSuppression.coerceIn(0f, 1f))
            gl.setFloatUniform("uOpacity", config.opacity.coerceIn(0f, 1f))
            gl.setSamplerTexIdUniform("uTexture", inputTexId, 0)
            gl.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }

        override fun release() {
            try {
                gl.delete()
            } finally {
                super.release()
            }
        }
    }

    companion object {
        private const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord.xy;
            }
        """

        private const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexture;
            uniform vec3 uKeyColor;
            uniform float uTolerance;
            uniform float uSoftness;
            uniform float uSpillSuppression;
            uniform float uOpacity;
            uniform float uEnabled;
            varying vec2 vTexCoord;

            // Standard ITU-R BT.601 RGB to YCbCr conversion
            vec2 rgbToCbCr(vec3 rgb) {
                float Cb = -0.168736 * rgb.r - 0.331264 * rgb.g + 0.500000 * rgb.b;
                float Cr =  0.500000 * rgb.r - 0.418688 * rgb.g - 0.081312 * rgb.b;
                return vec2(Cb, Cr);
            }

            void main() {
                vec4 source = texture2D(uTexture, vTexCoord);
                vec3 c = source.rgb;

                vec2 pixelChroma = rgbToCbCr(c);
                vec2 keyChroma = rgbToCbCr(uKeyColor);

                // Euclidean distance in chroma space
                float chromaDist = distance(pixelChroma, keyChroma);

                // Smooth edge feathering based on tolerance and softness
                float lowerBound = max(uTolerance - uSoftness * 0.5, 0.0);
                float upperBound = uTolerance + uSoftness * 0.5;
                float keyAlpha = smoothstep(lowerBound, upperBound, chromaDist);

                // Spill suppression: removes reflected key color on foreground edges
                vec3 despilled = c;
                if (uKeyColor.g > uKeyColor.r && uKeyColor.g > uKeyColor.b) {
                    // Green screen spill: limit green channel to max of red and blue
                    float maxOther = max(c.r, c.b);
                    if (c.g > maxOther) {
                        despilled.g = mix(c.g, maxOther, uSpillSuppression);
                    }
                } else if (uKeyColor.b > uKeyColor.r && uKeyColor.b > uKeyColor.g) {
                    // Blue screen spill: limit blue channel to max of red and green
                    float maxOther = max(c.r, c.g);
                    if (c.b > maxOther) {
                        despilled.b = mix(c.b, maxOther, uSpillSuppression);
                    }
                }

                gl_FragColor = vec4(mix(c, despilled, uEnabled), source.a * mix(1.0, keyAlpha * uOpacity, uEnabled));
            }
        """
    }
}
