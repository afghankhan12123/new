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
import com.videoeditor.core.model.VisualEffect
import com.videoeditor.core.model.VisualEffectType

/**
 * Unified Stage 9 visual effects and color grading shader pass.
 * Executes on GPU using OpenGL ES 2.0/3.0.
 * Supports static parameters, dynamic keyframes, and deterministic frame timing.
 */
@UnstableApi
class VisualEffectsGlEffect(
    private val filter: FilterSettings = FilterSettings(),
    private val adjust: ColorAdjustments = ColorAdjustments(),
    private val effects: List<VisualEffect> = emptyList(),
    private val timelineStartMicros: Long = 0L,
    private val effectOriginMicros: Long = timelineStartMicros,
    private val sourceSpeed: Float = 1f,
    private val liveClip: (() -> com.videoeditor.core.model.Clip)? = null,
    private val liveOverlay: (() -> com.videoeditor.core.model.OverlayClip)? = null,
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        val hasActiveFilter = filter.filterId != null && filter.intensity > 0f
        val hasActiveAdjust = !adjust.isIdentity()
        val hasActiveEffects = effects.any { it.enabled && it.intensity > 0f }
        return liveClip == null && liveOverlay == null && !hasActiveFilter && !hasActiveAdjust && !hasActiveEffects
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(useHdr, filter, adjust, effects, timelineStartMicros, effectOriginMicros, sourceSpeed, liveClip, liveOverlay)

    private class Program(
        useHdr: Boolean,
        private val filter: FilterSettings,
        private val baseAdjust: ColorAdjustments,
        private val effectsList: List<VisualEffect>,
        private val timelineStartMicros: Long,
        private val effectOriginMicros: Long,
        private val sourceSpeed: Float,
        private val liveClip: (() -> com.videoeditor.core.model.Clip)?,
        private val liveOverlay: (() -> com.videoeditor.core.model.OverlayClip)?,
    ) : BaseGlShaderProgram(useHdr, 1) {

        private val gl = GlProgram(VERTEX, FRAGMENT)
        private var texWidth = 1
        private var texHeight = 1

        init {
            gl.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            gl.setBufferAttribute("aTexCoord", GlUtil.getTextureCoordinateBounds(), 4)

            val preset = com.videoeditor.core.model.FilterPresets.grade(filter.filterId)
            gl.setFloatsUniform("uFilterMatrix", preset.matrix)
            gl.setFloatsUniform("uFilterOffset", preset.offset)
            gl.setFloatUniform("uFilterStrength", if (filter.filterId != null) filter.intensity.coerceIn(0f, 1f) else 0f)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            texWidth = inputWidth
            texHeight = inputHeight
            gl.setFloatsUniform("uTexel", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            return Size(inputWidth, inputHeight)
        }

        private val intermediateTextures = intArrayOf(0, 0)
        private val intermediateFbos = intArrayOf(0, 0)
        private val targetFbo = IntArray(1)
        private var allocatedWidth = 0
        private var allocatedHeight = 0

        private fun releaseIntermediate() {
            for (i in 0..1) {
                if (intermediateFbos[i] != 0) GlUtil.deleteFbo(intermediateFbos[i])
                if (intermediateTextures[i] != 0) GlUtil.deleteTexture(intermediateTextures[i])
                intermediateFbos[i] = 0; intermediateTextures[i] = 0
            }
            allocatedWidth = 0; allocatedHeight = 0
        }

        private fun ensureIntermediate() {
            if (allocatedWidth == texWidth && allocatedHeight == texHeight) return
            releaseIntermediate()
            for (i in 0..1) {
                intermediateTextures[i] = GlUtil.createTexture(texWidth, texHeight, false)
                intermediateFbos[i] = GlUtil.createFboForTexture(intermediateTextures[i])
            }
            allocatedWidth = texWidth; allocatedHeight = texHeight
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val clip = liveClip?.invoke()
            val overlay = liveOverlay?.invoke()
            val currentAdjust = clip?.colorAdjustments ?: baseAdjust
            val currentFilter = clip?.filter ?: filter
            val localTimeUs = com.videoeditor.core.model.render.ItemTimeMapping(timelineStartMicros, sourceSpeed)
                .clipLocalTime(presentationTimeUs, effectOriginMicros)
            val active = (clip?.effects ?: overlay?.effects ?: effectsList).filter {
                it.isActiveAt(localTimeUs) && it.evaluateIntensityAt(localTimeUs) > 0f
            }
            // Grade once, then execute the authored stack in order. Two owned intermediates
            // avoid reading and writing the same texture and are reused for the program lifetime.
            val hasGrade = currentFilter.filterId != null || !currentAdjust.isIdentity()
            val passCount = active.size + if (hasGrade || active.isEmpty()) 1 else 0
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, targetFbo, 0)
            if (passCount > 1) ensureIntermediate()
            var input = inputTexId
            try {
                for (pass in 0 until passCount) {
                    val last = pass == passCount - 1
                    GlUtil.focusFramebufferUsingCurrentContext(if (last) targetFbo[0] else intermediateFbos[pass % 2], texWidth, texHeight)
                    val grade = pass == 0 && (hasGrade || active.isEmpty())
                    val effectIndex = pass - if (hasGrade || active.isEmpty()) 1 else 0
                    renderPass(input, localTimeUs, presentationTimeUs,
                        if (grade) currentFilter else FilterSettings(),
                        if (grade) currentAdjust else ColorAdjustments(),
                        if (grade) emptyList() else listOf(active[effectIndex]))
                    if (!last) input = intermediateTextures[pass % 2]
                }
            } finally { GlUtil.focusFramebufferUsingCurrentContext(targetFbo[0], texWidth, texHeight) }
        }

        private fun renderPass(inputTexId: Int, localTimeUs: Long, presentationTimeUs: Long,
            currentFilter: FilterSettings, baseAdjust: ColorAdjustments, effectsList: List<VisualEffect>) {
            gl.use()
            val preset = com.videoeditor.core.model.FilterPresets.grade(currentFilter.filterId)
            gl.setFloatsUniform("uFilterMatrix", preset.matrix)
            gl.setFloatsUniform("uFilterOffset", preset.offset)
            gl.setFloatUniform("uFilterStrength", if (currentFilter.filterId != null) currentFilter.intensity.coerceIn(0f, 1f) else 0f)
            // Resolve effective adjustments and effects at this presentation timestamp
            var brightness = baseAdjust.brightness
            var contrast = baseAdjust.contrast
            var saturation = baseAdjust.saturation
            var exposure = baseAdjust.exposure
            var highlights = baseAdjust.highlights
            var shadows = baseAdjust.shadows
            var temperature = baseAdjust.temperature
            var tint = baseAdjust.tint
            var sharpen = baseAdjust.sharpen
            var vignetteIntensity = baseAdjust.vignette
            var vignetteRadius = 0.75f
            var vignetteSoftness = 0.45f
            var hue = baseAdjust.hue

            var blurRadius = 0f
            var blurIntensity = 0f
            var grainAmount = 0f
            var grainSize = 1.0f
            var grainEnabled = 0f
            var sepia = 0f
            var blackWhite = 0f
            var fadeWashed = 0f
            var invert = 0f

            for (effect in effectsList) {
                if (!effect.isActiveAt(localTimeUs)) continue
                val intensity = effect.evaluateIntensityAt(localTimeUs)
                if (intensity <= 0f) continue

                when (effect.type) {
                    VisualEffectType.Exposure -> {
                        exposure += effect.param("exposure", 0.2f) * intensity
                    }
                    VisualEffectType.Brightness -> {
                        brightness += effect.param("brightness", 0.15f) * intensity
                    }
                    VisualEffectType.Contrast -> {
                        contrast += effect.param("contrast", 0.2f) * intensity
                    }
                    VisualEffectType.Saturation -> {
                        saturation += effect.param("saturation", 0.25f) * intensity
                    }
                    VisualEffectType.Temperature -> {
                        temperature += effect.param("temperature", 0.3f) * intensity
                    }
                    VisualEffectType.Tint -> {
                        tint += effect.param("tint", 0.2f) * intensity
                    }
                    VisualEffectType.Highlights -> {
                        highlights += effect.param("highlights", 0.2f) * intensity
                    }
                    VisualEffectType.Shadows -> {
                        shadows += effect.param("shadows", 0.2f) * intensity
                    }
                    VisualEffectType.Hue -> {
                        hue += effect.param("hue", 0.1f) * intensity
                    }
                    VisualEffectType.GaussianBlur -> {
                        blurRadius = maxOf(blurRadius, effect.param("radius", 12f))
                        blurIntensity = maxOf(blurIntensity, intensity.coerceIn(0f, 1f))
                    }
                    VisualEffectType.Sharpen -> {
                        sharpen += effect.param("strength", 0.5f) * intensity
                    }
                    VisualEffectType.Vignette -> {
                        vignetteIntensity = maxOf(vignetteIntensity, intensity.coerceIn(0f, 1f))
                        vignetteRadius = effect.param("radius", 0.75f)
                        vignetteSoftness = effect.param("softness", 0.45f)
                    }
                    VisualEffectType.FilmGrain -> {
                        grainAmount = maxOf(grainAmount, effect.param("grainAmount", 0.25f) * intensity)
                        grainSize = effect.param("grainSize", 1.5f)
                        grainEnabled = 1f
                    }
                    VisualEffectType.BlackAndWhite -> {
                        blackWhite = maxOf(blackWhite, intensity.coerceIn(0f, 1f))
                    }
                    VisualEffectType.Sepia -> {
                        sepia = maxOf(sepia, intensity.coerceIn(0f, 1f))
                    }
                    VisualEffectType.FadeWashed -> {
                        fadeWashed = maxOf(fadeWashed, intensity.coerceIn(0f, 1f))
                    }
                    VisualEffectType.InvertColor -> {
                        invert = maxOf(invert, intensity.coerceIn(0f, 1f))
                    }
                    VisualEffectType.PresetCinematic -> {
                        contrast += 0.18f * intensity
                        saturation -= 0.1f * intensity
                        vignetteIntensity = maxOf(vignetteIntensity, 0.35f * intensity)
                        temperature -= 0.05f * intensity
                    }
                    VisualEffectType.PresetVintage -> {
                        temperature += 0.25f * intensity
                        saturation -= 0.2f * intensity
                        contrast += 0.15f * intensity
                        vignetteIntensity = maxOf(vignetteIntensity, 0.4f * intensity)
                        grainAmount = maxOf(grainAmount, 0.2f * intensity)
                        grainEnabled = 1f
                    }
                    VisualEffectType.PresetWarmFilm -> {
                        temperature += 0.35f * intensity
                        tint += 0.1f * intensity
                        contrast += 0.1f * intensity
                    }
                    VisualEffectType.PresetCoolFilm -> {
                        temperature -= 0.35f * intensity
                        tint -= 0.05f * intensity
                        contrast += 0.12f * intensity
                    }
                    VisualEffectType.PresetBlackAndWhite -> {
                        blackWhite = maxOf(blackWhite, intensity)
                        contrast += 0.25f * intensity
                    }
                    VisualEffectType.PresetVibrant -> {
                        saturation += 0.45f * intensity
                        contrast += 0.15f * intensity
                        brightness += 0.05f * intensity
                    }
                    VisualEffectType.PresetSoftPortrait -> {
                        contrast -= 0.1f * intensity
                        brightness += 0.08f * intensity
                        temperature += 0.15f * intensity
                        blurRadius = maxOf(blurRadius, 3f)
                        blurIntensity = maxOf(blurIntensity, 0.2f * intensity)
                    }
                }
            }

            // Set uniforms
            gl.setFloatsUniform("uAdjust", floatArrayOf(brightness, contrast, saturation, exposure))
            gl.setFloatsUniform("uTone", floatArrayOf(highlights, shadows, temperature, tint))
            gl.setFloatUniform("uHue", hue)
            gl.setFloatUniform("uSharp", sharpen)
            gl.setFloatsUniform("uVignette", floatArrayOf(vignetteIntensity, vignetteRadius, vignetteSoftness, 0f))
            gl.setFloatsUniform("uBlur", floatArrayOf(blurRadius, blurIntensity))

            // Deterministic frame seed for grain based on presentation timestamp
            val frameSeed = (presentationTimeUs / 1000L).toFloat() * 0.001f
            gl.setFloatsUniform("uGrain", floatArrayOf(grainAmount, grainSize, frameSeed, grainEnabled))
            gl.setFloatUniform("uSepia", sepia)
            gl.setFloatUniform("uBlackWhite", blackWhite)
            gl.setFloatUniform("uFadeWashed", fadeWashed)
            gl.setFloatUniform("uInvert", invert)

            gl.setSamplerTexIdUniform("uTexture", inputTexId, 0)
            gl.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }

        override fun release() {
            try {
                releaseIntermediate()
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
            uniform mat4 uFilterMatrix;
            uniform vec3 uFilterOffset;
            uniform float uFilterStrength;
            uniform vec4 uAdjust;       // x=brightness, y=contrast, z=saturation, w=exposure
            uniform vec4 uTone;         // x=highlights, y=shadows, z=temp, w=tint
            uniform float uHue;
            uniform float uSharp;
            uniform vec4 uVignette;     // x=intensity, y=radius, z=softness, w=unused
            uniform vec2 uBlur;         // x=radius, y=intensity
            uniform vec4 uGrain;        // x=amount, y=size, z=seed, w=enabled
            uniform float uSepia;
            uniform float uBlackWhite;
            uniform float uFadeWashed;
            uniform float uInvert;
            uniform vec2 uTexel;
            varying vec2 vTexCoord;

            float luminance(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            vec3 rgb2hsv(vec3 c) {
                vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
                vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
                vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
                float d = q.x - min(q.w, q.y);
                float e = 1.0e-10;
                return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
            }

            vec3 hsv2rgb(vec3 c) {
                vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
                vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
            }

            float hash(vec2 p, float seed) {
                return fract(sin(dot(p + fract(sin(seed) * 43758.5453), vec2(12.9898, 78.233))) * 43758.5453);
            }

            void main() {
                vec4 source = texture2D(uTexture, vTexCoord);
                vec3 c = source.rgb;

                // 1. Spatial Gaussian Blur
                if (uBlur.y > 0.0) {
                    vec2 r = uTexel * uBlur.x;
                    vec3 b = vec3(0.0);
                    b += texture2D(uTexture, vTexCoord).rgb * 0.227027;
                    b += texture2D(uTexture, vTexCoord + vec2(r.x, 0.0)).rgb * 0.1945946;
                    b += texture2D(uTexture, vTexCoord - vec2(r.x, 0.0)).rgb * 0.1945946;
                    b += texture2D(uTexture, vTexCoord + vec2(0.0, r.y)).rgb * 0.1945946;
                    b += texture2D(uTexture, vTexCoord - vec2(0.0, r.y)).rgb * 0.1945946;
                    b += texture2D(uTexture, vTexCoord + r).rgb * 0.1216216;
                    b += texture2D(uTexture, vTexCoord - r).rgb * 0.1216216;
                    b += texture2D(uTexture, vTexCoord + vec2(r.x, -r.y)).rgb * 0.1216216;
                    b += texture2D(uTexture, vTexCoord + vec2(-r.x, r.y)).rgb * 0.1216216;
                    float totalWeight = 0.227027 + 4.0 * 0.1945946 + 4.0 * 0.1216216;
                    c = mix(c, b / totalWeight, uBlur.y);
                }

                // 2. Base preset filter matrix
                if (uFilterStrength > 0.0) {
                    vec3 f = (uFilterMatrix * vec4(c, 1.0)).rgb + uFilterOffset;
                    c = mix(c, f, uFilterStrength);
                }

                // 3. Sharpen convolution
                if (abs(uSharp) > 0.001) {
                    vec3 neighbors = (texture2D(uTexture, vTexCoord + vec2(uTexel.x, 0.0)).rgb
                        + texture2D(uTexture, vTexCoord - vec2(uTexel.x, 0.0)).rgb
                        + texture2D(uTexture, vTexCoord + vec2(0.0, uTexel.y)).rgb
                        + texture2D(uTexture, vTexCoord - vec2(0.0, uTexel.y)).rgb) * 0.25;
                    c += (source.rgb - neighbors) * uSharp;
                }

                // 4. Color Adjustments: Exposure, Brightness, Contrast, Saturation
                c *= exp2(uAdjust.w);
                c += uAdjust.x * 0.25;
                c = (c - 0.5) * (1.0 + uAdjust.y) + 0.5;
                c = mix(vec3(luminance(c)), c, 1.0 + uAdjust.z);

                // 5. Highlights, Shadows, Temp, Tint
                float toneL = clamp(luminance(c), 0.0, 1.0);
                c += uTone.x * 0.3 * smoothstep(0.4, 1.0, toneL);
                c += uTone.y * 0.3 * (1.0 - smoothstep(0.0, 0.6, toneL));
                c += vec3(uTone.z * 0.12, uTone.w * 0.08, -uTone.z * 0.12);

                // 6. Hue Shift
                if (abs(uHue) > 0.001) {
                    vec3 hsv = rgb2hsv(c);
                    hsv.x = fract(hsv.x + uHue);
                    c = hsv2rgb(hsv);
                }

                // 7. Sepia
                if (uSepia > 0.0) {
                    vec3 sep = vec3(
                        dot(c, vec3(0.393, 0.769, 0.189)),
                        dot(c, vec3(0.349, 0.686, 0.168)),
                        dot(c, vec3(0.272, 0.534, 0.131))
                    );
                    c = mix(c, sep, uSepia);
                }

                // 8. Washed Film / Fade
                if (uFadeWashed > 0.0) {
                    c = c * (1.0 - uFadeWashed * 0.25) + vec3(uFadeWashed * 0.15);
                    c = mix(c, vec3(0.5) + (c - vec3(0.5)) * (1.0 - uFadeWashed * 0.2), uFadeWashed);
                }

                // 9. Black & White
                if (uBlackWhite > 0.0) {
                    c = mix(c, vec3(luminance(c)), uBlackWhite);
                }

                // 10. Color Inversion
                if (uInvert > 0.0) {
                    c = mix(c, 1.0 - c, uInvert);
                }

                // 11. Vignette
                if (uVignette.x > 0.0) {
                    float radius = length((vTexCoord - 0.5) * 1.41421356);
                    float vStart = max(uVignette.y - uVignette.z, 0.0);
                    float vEnd = uVignette.y + uVignette.z;
                    c *= 1.0 - uVignette.x * smoothstep(vStart, vEnd, radius);
                }

                // 12. Deterministic Film Grain
                if (uGrain.w > 0.5 && uGrain.x > 0.0) {
                    vec2 grainCoord = floor(vTexCoord / (uTexel * max(uGrain.y, 1.0)));
                    float noise = (hash(grainCoord, uGrain.z) - 0.5) * 2.0;
                    c += noise * uGrain.x;
                }

                gl_FragColor = vec4(clamp(c, 0.0, 1.0), source.a);
            }
        """
    }
}
