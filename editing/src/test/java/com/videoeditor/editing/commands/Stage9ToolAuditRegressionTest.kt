package com.videoeditor.editing.commands

import com.videoeditor.core.model.*
import com.videoeditor.core.model.render.*
import com.videoeditor.editing.history.EditHistory
import org.junit.Assert.*
import org.junit.Test

/** Source-only regressions for owner-device qualification; not run by this build-only delivery. */
class Stage9ToolAuditRegressionTest {
    private val ref = MediaRef("media", "content://fixture", MediaType.Video, "fixture", durationMicros = 10_000_000)
    private val clip = Clip("main", ref.id, sourceEndMicros = 5_000_000)
    private fun project(layer: OverlayClip? = null) = Project("p", "p", 0, 0, mediaRefs = listOf(ref),
        timeline = Timeline(videoClips = listOf(clip), overlayClips = listOfNotNull(layer)))

    @Test fun pip_trim_undo_restores_duration_effect_keys_and_mask_keys() {
        val key = VisualKeyframe("k", 1_000_000, .5f)
        val layer = OverlayClip("pip", ref.id, sourceEndMicros = 3_000_000, timelineDurationMicros = 3_000_000,
            effects = listOf(VisualEffect("fx", VisualEffectType.Vignette, keyframes = listOf(key))),
            keyframeTracks = mapOf("opacity" to KeyframeTrack("opacity", listOf(key))),
            mask = LayerMask(keyframeTracks = mapOf("scaleX" to KeyframeTrack("scaleX", listOf(key)))))
        val original = project(layer); val history = EditHistory()
        val trimmed = history.apply(original, OverlayCommands.TrimOverlay(layer.id, 500_000, 2_500_000))
        val value = trimmed.timeline.overlayClips.single()
        assertEquals(2_000_000L, value.timelineDurationMicros)
        assertEquals(500_000L, value.keyframeTracks.getValue("opacity").keyframes.single().timeMicros)
        assertEquals(500_000L, value.mask!!.keyframeTracks.getValue("scaleX").keyframes.single().timeMicros)
        assertEquals(original, history.undo(trimmed))
        assertEquals(trimmed, history.redo(original))
    }
    @Test fun duplicate_redo_keeps_primary_and_pip_identities() {
        val base = project(); val history = EditHistory()
        val duplicated = history.apply(base, TimelineCommands.DuplicateClip(clip.id))
        assertEquals(duplicated, history.redo(history.undo(duplicated)))
        val layer = OverlayClip("pip", ref.id, sourceEndMicros = 1_000_000, timelineDurationMicros = 1_000_000)
        val withPip = project(layer); val pipHistory = EditHistory()
        val copy = pipHistory.apply(withPip, OverlayCommands.DuplicateOverlay(layer.id))
        assertEquals(copy, pipHistory.redo(pipHistory.undo(copy)))
    }
    @Test fun split_and_head_trim_restore_exact_authored_keys() {
        val animated = clip.copy(effects = listOf(VisualEffect("fx", VisualEffectType.Sepia, startTimeMicros = 2_000_000,
            keyframes = listOf(VisualKeyframe("k", 2_500_000, .5f)))))
        val base = project().copy(timeline = Timeline(videoClips = listOf(animated)))
        val history = EditHistory()
        val split = history.apply(base, TimelineCommands.SplitClip(clip.id, 2_000_000))
        assertEquals(500_000L, split.timeline.videoClips.last().effects.single().keyframes.single().timeMicros)
        assertEquals(base, history.undo(split))
        assertEquals(split, history.redo(base))
    }
    @Test fun canvas_uniform_edits_reuse_topology_but_dimensions_do_not() {
        val base = project(); val plan = ProjectRenderPlan.compile(base)
        assertTrue(plan.hasSameTopology(ProjectRenderPlan.compile(base.copy(canvas = base.canvas.copy(scaleMode = ScaleMode.Fill,
            backgroundColorArgb = -1)))))
        assertFalse(plan.hasSameTopology(ProjectRenderPlan.compile(base.copy(canvas = base.canvas.copy(width = 1080)))))
    }
    @Test fun new_caption_wrap_roundtrips_and_legacy_absence_keeps_unwrapped_layout() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val text = TextOverlay("caption", "افغانستان Afghanistan", maxWidthFraction = .9f)
        assertEquals(text, json.decodeFromString(TextOverlay.serializer(), json.encodeToString(TextOverlay.serializer(), text)))
        assertNull(json.decodeFromString(TextOverlay.serializer(), """{"id":"old","text":"caption"}""").maxWidthFraction)
    }
    @Test fun audio_library_undo_preserves_unused_preexisting_sources() {
        val base = project(); val history = EditHistory()
        val added = history.apply(base, AssetCommands.AddAudioClip(ref, AudioClip("audio", ref.id, sourceEndMicros = 1_000_000)))
        assertEquals(base, history.undo(added))
    }
    @Test fun audio_split_redo_retains_boundary_point_ids_for_later_commands() {
        val envelope = VolumeEnvelope(listOf(VolumePoint("a", 0, -10f), VolumePoint("b", 3_000_000, 0f)))
        val base = project().copy(timeline = project().timeline.copy(audioClips = listOf(
            AudioClip("audio", ref.id, sourceEndMicros = 3_000_000, volumeEnvelope = envelope))))
        val history = EditHistory()
        val split = history.apply(base, AssetCommands.SplitAudioClip("audio", 1_000_000))
        val boundary = split.timeline.audioClips.first().volumeEnvelope.points.last()
        val edited = history.apply(split, AssetCommands.MoveVolumePoint("audio", boundary.id, boundary.sourcePositionMicros, -4f))
        val restored = history.undo(history.undo(edited))
        assertEquals(base, restored)
        assertEquals(edited, history.redo(history.redo(restored)))
    }

}
