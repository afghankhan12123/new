package com.videoeditor.core.media.audio

import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.DuckingSettings
import com.videoeditor.core.model.Project
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds computed ducking curves so a slider move or a seek does not re-decode
 * trigger audio, and so preview and export replay the *same* analysis.
 *
 * # Cache identity
 *
 * An entry is keyed by everything that can change the measured curve:
 *
 * - the target clip id,
 * - each trigger clip's id, source identity (media reference URI), audible trim
 *   range, playback speed and mute state,
 * - the ducking parameters ([DuckingSettings.analysisKey]),
 * - the analysis window length.
 *
 * Any change to one of those produces a different key, so the entry is simply
 * not found and the curve is recomputed. That is the documented invalidation
 * rule: a trigger whose trim range moved, whose speed changed, which was muted,
 * or whose media was relinked no longer matches its cached analysis.
 *
 * # Durability
>
 * The cache is process-local and never persisted. Reopening a project, or
 * restarting the process, always recomputes ducking analysis from the source
 * files; a project can never require a previously generated temporary analysis
 * to open successfully. The cache is bounded by [MAX_ENTRIES], evicting the
 * oldest entry when full, so a long session cannot grow it without limit.
 *
 * # Failure policy
 *
 * A failed analysis is cached as [Entry.Failure] under the same key so a
 * repeated rebuild does not re-decode an undecodable source in a tight loop. The
 * caller treats a failure as "no attenuation applied" and surfaces the state in
 * the UI, which is the documented safe policy — never an arbitrary reduction.
 */
@Singleton
class DuckingAnalysisCache @Inject constructor() {

    private sealed interface Entry {
        data class Success(val curve: DuckingCurve) : Entry
        data object Failure : Entry
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val insertionOrder = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /** The computed curve for [target], or null when it is not cached or failed. */
    fun curveFor(project: Project, target: AudioClip): DuckingCurve? {
        if (!target.ducking.isActive) return null
        val key = keyFor(project, target) ?: return null
        return when (val entry = entries[key]) {
            is Entry.Success -> entry.curve
            else -> null
        }
    }

    /** Whether a cached curve exists for [target], so callers can skip analysis. */
    fun hasCurve(project: Project, target: AudioClip): Boolean = curveFor(project, target) != null

    /** Whether the last analysis for [target] is a recorded failure. */
    fun hasFailure(project: Project, target: AudioClip): Boolean {
        if (!target.ducking.isActive) return false
        val key = keyFor(project, target) ?: return false
        return entries[key] is Entry.Failure
    }

    fun store(project: Project, target: AudioClip, curve: DuckingCurve) {
        val key = keyFor(project, target) ?: return
        put(key, Entry.Success(curve))
    }

    fun storeFailure(project: Project, target: AudioClip) {
        val key = keyFor(project, target) ?: return
        put(key, Entry.Failure)
    }

    /** Drops everything. Used when a project is closed or media is relinked. */
    fun clear() {
        entries.clear()
        insertionOrder.clear()
    }

    private fun put(key: String, entry: Entry) {
        entries[key] = entry
        insertionOrder.addLast(key)
        while (entries.size > MAX_ENTRIES) {
            val oldest = insertionOrder.pollFirst() ?: break
            entries.remove(oldest)
        }
    }

    /**
     * Builds the cache key from the current project state. Returns null when a
     * trigger clip is no longer in the project, which makes the entry unfindable
     * and forces recomputation against whatever the project now contains.
     */
    private fun keyFor(project: Project, target: AudioClip): String? {
        val settings = target.ducking.coerced()
        val builder = StringBuilder()
        builder.append(target.id).append('#')
            .append(settings.analysisKey()).append('#')
            .append(DuckingSettings.ANALYSIS_WINDOW_MILLIS).append('#')
            // Curves include release/silent windows through the project end.
            // Extending an edit must recompute that tail, including when the
            // renderer supplies a non-destructively shortened target copy.
            .append(project.timeline.totalDurationMicros).append('#')
        for (triggerId in settings.triggerClipIds.sorted()) {
            val trigger = project.timeline.audioClips.firstOrNull { it.id == triggerId }
                ?: return null
            if (trigger.muted) {
                // A muted trigger contributes silence by contract; encode that
                // explicitly so unmuting it invalidates the entry.
                builder.append(triggerId).append(":muted;")
                continue
            }
            builder.append(triggerId).append(':')
                .append(project.mediaRef(trigger.mediaRefId)?.uri ?: return null).append(':')
                .append(trigger.sourceStartMicros).append('-').append(trigger.sourceEndMicros).append(':')
                .append(trigger.playbackSpeed).append(':')
                .append(trigger.timelineStartMicros).append(';')
        }
        return builder.toString()
    }

    private companion object {
        /** Bounds the process-local cache. Each entry is a few hundred floats. */
        const val MAX_ENTRIES = 64
    }
}
