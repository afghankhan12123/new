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
import com.videoeditor.core.model.ColorAdjustments
import com.videoeditor.core.model.FilterSettings

/** One SDR texture pass, before captions. Uniforms are immutable for this composition. */
@UnstableApi
internal class ColorGradeEffect(private val filter: FilterSettings, private val adjust: ColorAdjustments) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int) = filter.filterId == null && adjust.isIdentity()
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr, filter, adjust)

    private class Program(useHdr: Boolean, filter: FilterSettings, a: ColorAdjustments) : BaseGlShaderProgram(useHdr, 1) {
        private val gl = GlProgram(VERTEX, FRAGMENT)
        init {
            gl.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            gl.setBufferAttribute("aTexCoord", GlUtil.getTextureCoordinateBounds(), 4)
            gl.setFloatUniform("uStrength", filter.intensity.coerceIn(0f, 1f))
            val preset = com.videoeditor.core.model.FilterPresets.grade(filter.filterId)
            gl.setFloatsUniform("uFilterMatrix", preset.matrix)
            gl.setFloatsUniform("uFilterOffset", preset.offset)
            gl.setFloatsUniform("uAdjust", floatArrayOf(a.brightness, a.contrast, a.saturation, a.exposure))
            gl.setFloatsUniform("uTone", floatArrayOf(a.highlights, a.shadows, a.temperature, a.tint))
            gl.setFloatUniform("uSharp", a.sharpen)
            gl.setFloatUniform("uVignette", a.vignette)
        }
        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            gl.setFloatsUniform("uTexel", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            return Size(inputWidth, inputHeight)
        }
        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            gl.use()
            gl.setSamplerTexIdUniform("uTexture", inputTexId, 0)
            gl.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }
        override fun release() {
            try { gl.delete() } finally { super.release() }
        }
    }
    companion object {
        private const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aTexCoord.xy; }
        """
        private const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexture;
            uniform mat4 uFilterMatrix;
            uniform vec3 uFilterOffset;
            uniform float uStrength;
            uniform vec4 uAdjust;
            uniform vec4 uTone;
            uniform float uSharp;
            uniform float uVignette;
            uniform vec2 uTexel;
            varying vec2 vTexCoord;
            float luminance(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            void main() {
                vec4 source = texture2D(uTexture, vTexCoord);
                vec3 c = source.rgb;
                vec3 f = (uFilterMatrix * vec4(c, 1.0)).rgb + uFilterOffset;
                c = mix(c, f, uStrength);
                vec3 neighbors = (texture2D(uTexture, vTexCoord + vec2(uTexel.x,0.0)).rgb
                    + texture2D(uTexture, vTexCoord - vec2(uTexel.x,0.0)).rgb
                    + texture2D(uTexture, vTexCoord + vec2(0.0,uTexel.y)).rgb
                    + texture2D(uTexture, vTexCoord - vec2(0.0,uTexel.y)).rgb) * 0.25;
                c += (source.rgb - neighbors) * uSharp;
                c *= exp2(uAdjust.w);
                c += uAdjust.x * 0.25;
                c = (c - 0.5) * (1.0 + uAdjust.y) + 0.5;
                c = mix(vec3(luminance(c)), c, 1.0 + uAdjust.z);
                float toneL = clamp(luminance(c), 0.0, 1.0);
                c += uTone.x * 0.3 * smoothstep(0.4, 1.0, toneL);
                c += uTone.y * 0.3 * (1.0 - smoothstep(0.0, 0.6, toneL));
                c += vec3(uTone.z * 0.12, uTone.w * 0.08, -uTone.z * 0.12);
                float radius = length((vTexCoord - 0.5) * 1.41421356);
                c *= 1.0 - uVignette * smoothstep(0.25, 0.75, radius);
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), source.a);
            }
        """
    }
}
