package com.videoeditor.core.model

import kotlinx.serialization.Serializable

/**
 * Supported visual effect types in Stage 9.
 * All effects run deterministic GPU shaders or mathematical color transformations.
 */
@Serializable
enum class VisualEffectType(val displayName: String, val category: String) {
    // Color Adjustments
    Exposure("Exposure", "Color"),
    Brightness("Brightness", "Color"),
    Contrast("Contrast", "Color"),
    Saturation("Saturation", "Color"),
    Temperature("Temperature", "Color"),
    Tint("Tint", "Color"),
    Highlights("Highlights", "Color"),
    Shadows("Shadows", "Color"),
    Hue("Hue", "Color"),

    // Spatial & Image Effects
    GaussianBlur("Gaussian Blur", "Blur"),
    Sharpen("Sharpen", "Basic"),
    Vignette("Vignette", "Basic"),
    FilmGrain("Film Grain", "Stylize"),
    BlackAndWhite("Black & White", "Color"),
    Sepia("Sepia", "Stylize"),
    FadeWashed("Fade / Washed Film", "Stylize"),
    InvertColor("Color Invert", "Stylize"),

    // Creative Presets
    PresetCinematic("Cinematic", "Cinematic"),
    PresetVintage("Vintage", "Cinematic"),
    PresetWarmFilm("Warm Film", "Cinematic"),
    PresetCoolFilm("Cool Film", "Cinematic"),
    PresetBlackAndWhite("B&W Classic", "Cinematic"),
    PresetVibrant("Vibrant", "Cinematic"),
    PresetSoftPortrait("Soft Portrait", "Cinematic"),
}

@Serializable
enum class KeyframeInterpolation {
    Linear,
    Hold,
    Smooth,
}

@Serializable
data class VisualKeyframe(
    val id: String,
    val timeMicros: Long,
    val value: Float,
    val interpolation: KeyframeInterpolation = KeyframeInterpolation.Linear,
)

@Serializable
data class KeyframeTrack(
    val propertyName: String,
    val keyframes: List<VisualKeyframe> = emptyList(),
) {
    fun evaluate(timeMicros: Long, defaultValue: Float): Float {
        if (keyframes.isEmpty()) return defaultValue
        var first = keyframes[0]
        var last = first
        var before: VisualKeyframe? = null
        var after: VisualKeyframe? = null
        // No sorting/allocation on the frame thread; tolerate legacy unsorted documents.
        for (key in keyframes) {
            if (key.timeMicros < first.timeMicros) first = key
            if (key.timeMicros > last.timeMicros) last = key
            if (key.timeMicros <= timeMicros && (before == null || key.timeMicros >= before.timeMicros)) before = key
            if (key.timeMicros > timeMicros && (after == null || key.timeMicros < after.timeMicros)) after = key
        }
        val k0 = before ?: return first.value
        val k1 = after ?: return last.value
        val duration = (k1.timeMicros - k0.timeMicros).toDouble()
        if (duration <= 0.0) return k0.value
        val progress = ((timeMicros - k0.timeMicros) / duration).coerceIn(0.0, 1.0).toFloat()
        return when (k0.interpolation) {
            KeyframeInterpolation.Hold -> k0.value
            KeyframeInterpolation.Linear -> k0.value + (k1.value - k0.value) * progress
            KeyframeInterpolation.Smooth -> {
                val smoothProgress = progress * progress * (3f - 2f * progress)
                k0.value + (k1.value - k0.value) * smoothProgress
            }
        }
    }
}

/**
 * A discrete visual effect attached to a video, photo, or overlay layer.
 * Parameters are stored as key-value pairs with documented neutral defaults.
 */
@Serializable
data class VisualEffect(
    val id: String,
    val type: VisualEffectType,
    val enabled: Boolean = true,
    val startTimeMicros: Long = 0L,
    val durationMicros: Long? = null,
    val intensity: Float = 1.0f,
    val parameters: Map<String, Float> = emptyMap(),
    val keyframes: List<VisualKeyframe> = emptyList(),
) {
    /**
     * Retrieves a named parameter value, with fallback to default.
     */
    fun param(name: String, default: Float = 0f): Float = parameters[name] ?: default

    /**
     * Evaluates effective intensity at a given clip-relative timestamp, accounting for keyframes.
     */
    fun evaluateIntensityAt(clipRelativeMicros: Long): Float {
        if (keyframes.isEmpty()) return intensity
        return KeyframeTrack("intensity", keyframes).evaluate(clipRelativeMicros, intensity)
    }

    /**
     * Checks whether this effect is active at the given clip-relative timestamp.
     */
    fun isActiveAt(clipRelativeMicros: Long): Boolean {
        if (!enabled) return false
        if (clipRelativeMicros < startTimeMicros) return false
        val dur = durationMicros
        if (dur != null && clipRelativeMicros >= startTimeMicros + dur) return false
        return true
    }

    companion object {
        fun createDefault(type: VisualEffectType, id: String = java.util.UUID.randomUUID().toString()): VisualEffect {
            val params = mutableMapOf<String, Float>()
            var defaultIntensity = 1.0f
            when (type) {
                VisualEffectType.Exposure -> { params["exposure"] = 0.2f }
                VisualEffectType.Brightness -> { params["brightness"] = 0.15f }
                VisualEffectType.Contrast -> { params["contrast"] = 0.2f }
                VisualEffectType.Saturation -> { params["saturation"] = 0.25f }
                VisualEffectType.Temperature -> { params["temperature"] = 0.3f }
                VisualEffectType.Tint -> { params["tint"] = 0.2f }
                VisualEffectType.Highlights -> { params["highlights"] = 0.2f }
                VisualEffectType.Shadows -> { params["shadows"] = 0.2f }
                VisualEffectType.Hue -> { params["hue"] = 0.0f }
                VisualEffectType.GaussianBlur -> {
                    params["radius"] = 12.0f
                    defaultIntensity = 0.8f
                }
                VisualEffectType.Sharpen -> {
                    params["strength"] = 0.5f
                    defaultIntensity = 0.7f
                }
                VisualEffectType.Vignette -> {
                    params["radius"] = 0.75f
                    params["softness"] = 0.45f
                    defaultIntensity = 0.6f
                }
                VisualEffectType.FilmGrain -> {
                    params["grainAmount"] = 0.25f
                    params["grainSize"] = 1.5f
                    defaultIntensity = 0.5f
                }
                VisualEffectType.BlackAndWhite -> { defaultIntensity = 1.0f }
                VisualEffectType.Sepia -> { defaultIntensity = 0.85f }
                VisualEffectType.FadeWashed -> {
                    params["liftBlacks"] = 0.15f
                    defaultIntensity = 0.7f
                }
                VisualEffectType.InvertColor -> { defaultIntensity = 1.0f }
                VisualEffectType.PresetCinematic -> {
                    params["contrast"] = 0.18f
                    params["saturation"] = -0.1f
                    params["vignette"] = 0.35f
                    params["temperature"] = -0.05f
                    defaultIntensity = 0.8f
                }
                VisualEffectType.PresetVintage -> {
                    params["temperature"] = 0.25f
                    params["saturation"] = -0.2f
                    params["contrast"] = 0.15f
                    params["vignette"] = 0.4f
                    params["grain"] = 0.2f
                    defaultIntensity = 0.75f
                }
                VisualEffectType.PresetWarmFilm -> {
                    params["temperature"] = 0.35f
                    params["tint"] = 0.1f
                    params["contrast"] = 0.1f
                    defaultIntensity = 0.8f
                }
                VisualEffectType.PresetCoolFilm -> {
                    params["temperature"] = -0.35f
                    params["tint"] = -0.05f
                    params["contrast"] = 0.12f
                    defaultIntensity = 0.8f
                }
                VisualEffectType.PresetBlackAndWhite -> {
                    params["contrast"] = 0.25f
                    defaultIntensity = 1.0f
                }
                VisualEffectType.PresetVibrant -> {
                    params["saturation"] = 0.45f
                    params["contrast"] = 0.15f
                    params["brightness"] = 0.05f
                    defaultIntensity = 0.85f
                }
                VisualEffectType.PresetSoftPortrait -> {
                    params["contrast"] = -0.1f
                    params["brightness"] = 0.08f
                    params["warmth"] = 0.15f
                    defaultIntensity = 0.7f
                }
            }
            return VisualEffect(
                id = id,
                type = type,
                intensity = defaultIntensity,
                parameters = params,
            )
        }
    }
}
