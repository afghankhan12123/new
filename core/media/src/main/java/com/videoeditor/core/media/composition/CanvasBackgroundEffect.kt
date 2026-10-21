package com.videoeditor.core.media.composition

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Fill uncovered canvas pixels before captions/stickers; primary and PiP textures remain mandatory. */
@UnstableApi
internal class CanvasBackgroundEffect(private val state: RenderPlanState) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : BaseGlShaderProgram(useHdr, 1) {
        private val gl = GlProgram("""
            attribute vec4 aPosition; attribute vec4 aTexCoord; varying vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aTexCoord.xy; }
        """.trimIndent(), """
            precision highp float; uniform sampler2D uTexture; uniform vec3 uBackground; varying vec2 vTexCoord;
            void main() { vec4 c = texture2D(uTexture, vTexCoord); gl_FragColor = vec4(c.rgb + uBackground * (1.0 - c.a), 1.0); }
        """.trimIndent()).apply {
            setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            setBufferAttribute("aTexCoord", GlUtil.getTextureCoordinateBounds(), 4)
        }
        override fun configure(inputWidth: Int, inputHeight: Int) = Size(inputWidth, inputHeight)
        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val color = state.plan.project.canvas.backgroundColorArgb
            // Media3's working RGB is linear; Android color controls contain sRGB components.
            fun linear(channel: Int): Float {
                val v = channel / 255f
                return if (v <= .04045f) v / 12.92f else Math.pow(((v + .055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
            gl.use()
            gl.setSamplerTexIdUniform("uTexture", inputTexId, 0)
            gl.setFloatsUniform("uBackground", floatArrayOf(linear(Color.red(color)), linear(Color.green(color)), linear(Color.blue(color))))
            gl.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }
        override fun release() { try { gl.delete() } finally { super.release() } }
    }
}
