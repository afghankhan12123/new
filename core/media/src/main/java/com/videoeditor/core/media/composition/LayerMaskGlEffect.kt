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
import com.videoeditor.core.model.LayerMask
import com.videoeditor.core.model.MaskType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPU shader effect that applies analytical geometric or gradient masks to a layer.
 * Modulates the texture's alpha channel based on signed distance fields (SDF).
 */
@UnstableApi
class LayerMaskGlEffect(private val mask: LayerMask?, private val startMicros: Long = 0L, private val liveMask: (() -> LayerMask?)? = null) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        liveMask == null && (mask == null || !mask.enabled)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(useHdr, mask ?: LayerMask(), startMicros, liveMask)

    private class Program(useHdr: Boolean, private val baseMask: LayerMask, private val startMicros: Long, private val liveMask: (() -> LayerMask?)?) : BaseGlShaderProgram(useHdr, 1) {
        private val gl = GlProgram(VERTEX, FRAGMENT)

        init {
            gl.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            gl.setBufferAttribute("aTexCoord", GlUtil.getTextureCoordinateBounds(), 4)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            gl.use()

            val current = if (liveMask != null) liveMask.invoke() else baseMask
            val mask = (current ?: baseMask).evaluatedMaskAt((presentationTimeUs - startMicros).coerceAtLeast(0L))
            gl.setFloatUniform("uEnabled", if (current?.enabled == true) 1f else 0f)

            // Shape type integer: 0=Rect, 1=RoundedRect, 2=Circle, 3=LinearGradient, 4=Polygon
            val shapeInt = when (mask.type) {
                MaskType.Rectangle -> 0
                MaskType.RoundedRectangle -> 1
                MaskType.Circle -> 2
                MaskType.LinearGradient -> 3
                MaskType.Polygon -> 4
            }
            gl.setIntUniform("uShapeType", shapeInt)

            // Center in UV space (0..1), mapping from normalized (-1..1)
            val centerUv = floatArrayOf(
                (mask.centerX + 1.0f) * 0.5f,
                (1.0f - mask.centerY) * 0.5f,
            )
            gl.setFloatsUniform("uCenter", centerUv)

            // Scale half-sizes in UV space
            val scaleUv = floatArrayOf(
                (mask.scaleX * 0.5f).coerceAtLeast(0.001f),
                (mask.scaleY * 0.5f).coerceAtLeast(0.001f),
            )
            gl.setFloatsUniform("uScale", scaleUv)

            val rad = (-mask.rotationDegrees * PI / 180.0).toFloat()
            gl.setFloatsUniform("uRot", floatArrayOf(cos(rad), sin(rad)))

            gl.setFloatUniform("uCornerRadius", (mask.cornerRadius * 0.25f).coerceAtLeast(0f))
            gl.setFloatUniform("uFeather", mask.feather.coerceIn(0.0001f, 1.0f))
            gl.setFloatUniform("uInvert", if (mask.invert) 1.0f else 0.0f)
            gl.setFloatUniform("uOpacity", mask.opacity.coerceIn(0f, 1f))
            gl.setIntUniform("uPolygonSides", mask.polygonSides.coerceIn(3, 8))

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
            uniform int uShapeType;      // 0=Rect, 1=RoundedRect, 2=Circle, 3=LinearGradient, 4=Polygon
            uniform vec2 uCenter;
            uniform vec2 uScale;
            uniform vec2 uRot;           // cos, sin
            uniform float uCornerRadius;
            uniform float uFeather;
            uniform float uInvert;
            uniform float uOpacity;
            uniform float uEnabled;
            uniform int uPolygonSides;
            varying vec2 vTexCoord;

            void main() {
                vec4 source = texture2D(uTexture, vTexCoord);

                // Transform UV coordinate relative to mask center & rotation
                vec2 delta = vTexCoord - uCenter;
                vec2 p = vec2(
                    delta.x * uRot.x + delta.y * uRot.y,
                    -delta.x * uRot.y + delta.y * uRot.x
                );

                float dist = 0.0;

                if (uShapeType == 0) {
                    // Rectangle SDF
                    vec2 d = abs(p) - uScale;
                    dist = max(d.x, d.y);
                } else if (uShapeType == 1) {
                    // Rounded Rectangle SDF
                    float r = min(uCornerRadius, min(uScale.x, uScale.y));
                    vec2 d = abs(p) - uScale + vec2(r);
                    dist = min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
                } else if (uShapeType == 2) {
                    // Ellipse / Circle SDF
                    dist = length(p / uScale) - 1.0;
                    dist *= min(uScale.x, uScale.y);
                } else if (uShapeType == 3) {
                    // Linear Gradient wipe SDF
                    dist = p.y / uScale.y;
                } else {
                    // A regular 3..8-sided polygon in independently scaled layer coordinates.
                    vec2 polygonPoint = p / uScale;
                    float angle = atan(polygonPoint.y, polygonPoint.x);
                    float r = length(polygonPoint);
                    float n = float(uPolygonSides);
                    float slice = 6.2831853 / n;
                    float modAngle = mod(angle + slice * 0.5, slice) - slice * 0.5;
                    float maxR = cos(slice * 0.5) / cos(modAngle);
                    dist = (r - maxR) * min(uScale.x, uScale.y);
                }

                // Feathered alpha evaluation
                float maskAlpha = 0.0;
                if (uShapeType == 3) {
                    // Gradient: smooth progression across center
                    maskAlpha = clamp(0.5 - dist / max(uFeather * 2.0, 0.001), 0.0, 1.0);
                } else {
                    float f = max(uFeather * 0.5, 0.001);
                    maskAlpha = 1.0 - smoothstep(-f, f, dist);
                }

                if (uInvert > 0.5) {
                    maskAlpha = 1.0 - maskAlpha;
                }

                gl_FragColor = vec4(source.rgb, source.a * mix(1.0, maskAlpha * uOpacity, uEnabled));
            }
        """
    }
}
