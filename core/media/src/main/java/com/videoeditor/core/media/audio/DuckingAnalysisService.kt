package com.videoeditor.core.media.audio

import android.content.Context
import com.videoeditor.core.common.result.Result
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.DuckingSettings
import com.videoeditor.core.model.Project
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes and caches ducking curves for a whole project.
 *
 * This is the single service preview and export both call before building their
 * composition, which is what guarantees they replay the same attenuation. It is
 * deliberately dumb about scheduling: the caller decides when to run it and on
 * which dispatcher, because the editor wants it fire-and-forget off the main
 * thread while export wants it completed before the render starts.
 *
 * For every audio clip with active ducking whose curve is not already cached,
 * this decodes each of its trigger clips via [DuckingAnalyzer], combines their
 * measured levels and builds the gain curve via [DuckingEnvelopeDetector], then
 * stores it in the shared [DuckingAnalysisCache]. A clip whose analysis fails
 * (missing or undecodable trigger) is recorded as a failure; the composition
 * builder then inserts no ducking processor for it, which is the documented
 * no-reduction policy.
 *
 * Everything here is CPU/IO-bound and blocking. Callers must run it on a
 * background dispatcher, never the playback or main thread.
 */
@Singleton
class DuckingAnalysisService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val analyzer: DuckingAnalyzer,
    private val cache: DuckingAnalysisCache,
) {

    /** Ensures every active ducking target in [project] has a cached curve. */
    fun ensureAnalyzed(project: Project) {
        val windowMicros = DuckingEnvelopeDetector.windowMicros()
        for (clip in project.timeline.audioClips) {
            val settings = clip.ducking.coerced()
            if (!settings.isActive || cache.hasCurve(project, clip)) continue
            ensureOne(project, clip, settings, windowMicros)
        }
    }

    /**
     * Result of analyzing a single target: the stored curve on success, or null
     * when the target produced no audible attenuation (all triggers silent) —
     * in which case no processor is needed at all.
     */
    fun analyzeOne(project: Project, clip: AudioClip): DuckingCurve? {
        val settings = clip.ducking.coerced()
        if (!settings.isActive) return null
        if (cache.hasCurve(project, clip)) return cache.curveFor(project, clip)
        return ensureOne(project, clip, settings, DuckingEnvelopeDetector.windowMicros())
    }

    /** True when the cached state for [clip] is a recorded analysis failure. */
    fun analysisFailed(project: Project, clip: AudioClip): Boolean = cache.hasFailure(project, clip)

    private fun ensureOne(
        project: Project,
        clip: AudioClip,
        settings: DuckingSettings,
        windowMicros: Long,
    ): DuckingCurve? {
        val measuredLevels = settings.triggerClipIds.distinct().mapNotNull { triggerId ->
            val trigger = project.timeline.audioClips.firstOrNull { it.id == triggerId }
                ?: return@mapNotNull null
            when (val result = analyzer.measureTrigger(context, project, trigger, windowMicros)) {
                is Result.Success -> result.value
                is Result.Failure -> {
                    cache.storeFailure(project, clip)
                    return null
                }
            }
        }
        if (measuredLevels.isEmpty()) {
            // Every named trigger is missing, muted or undecodable. Record the
            // failure state rather than leaving the target unanalysed, so the UI
            // can show it and repeated rebuilds do not re-decode in a loop.
            cache.storeFailure(project, clip)
            return null
        }

        val measuredWindowCount = measuredLevels.maxOf { it.size }
        if (measuredWindowCount <= 0) {
            val intentionallySilent = settings.triggerClipIds.all { id ->
                project.timeline.audioClips.firstOrNull { it.id == id }?.let {
                    it.muted || it.sourceDurationMicros <= 0L
                } == true
            }
            if (intentionallySilent) {
                // Muted/empty clips were deliberately not decoded. Cache a
                // neutral result rather than presenting a decoder error.
                val neutral = DuckingCurve(clip.timelineStartMicros, windowMicros, FloatArray(0))
                cache.store(project, clip, neutral)
                return neutral
            }
            cache.storeFailure(project, clip)
            return null
        }
        // The grid spans every measured trigger from the first window.
        val windows = measuredLevels.flatten()
        // Include silence before the first trigger and after the last one so
        // interpolation cannot extend an active endpoint across the project.
        val gridStart = minOf(0L, windows.minOf { it.timelineStartMicros })
        val gridLast = maxOf(project.timeline.totalDurationMicros, windows.maxOf { it.timelineStartMicros })
        // Duration alone omits later triggers when their timeline starts differ.
        // Cover their complete timeline extent before aligning each input.
        val windowCount = Math.toIntExact((gridLast - gridStart) / windowMicros + 1L)
        val aligned = measuredLevels.map {
            DuckingEnvelopeDetector.alignToGrid(it, gridStart, windowCount, windowMicros)
        }
        val curve = DuckingEnvelopeDetector.buildCurve(aligned, windowMicros, gridStart, settings)
        if (curve.gains.isEmpty()) {
            cache.storeFailure(project, clip)
            return null
        }
        // An all-unity curve means the detector found no speech; store nothing
        // so the builder inserts no processor and the audio passes through.
        if (curve.gains.all { it >= 0.999f }) {
            cache.store(project, clip, curve)
            return curve
        }
        cache.store(project, clip, curve)
        return curve
    }
}
