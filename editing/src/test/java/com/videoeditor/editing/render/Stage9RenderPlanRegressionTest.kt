package com.videoeditor.editing.render

import com.google.common.truth.Truth.assertThat
import com.videoeditor.core.model.*
import com.videoeditor.core.model.render.*
import com.videoeditor.editing.commands.*
import com.videoeditor.editing.history.EditHistory
import org.junit.Test

/** Owner-requested regression source only. These tests were not executed for this delivery. */
class Stage9RenderPlanRegressionTest {
    private fun project(count: Int = 4, trimmed: Boolean = true): Project {
        val duration = if (trimmed) 4_000_000L else 6_000_000L
        return Project("project", "fixture", 0, 0,
            mediaRefs = (0 until count).map { MediaRef("media$it", "file:///fixture$it.mp4", MediaType.Video, "fixture", durationMicros = 6_000_000L) },
            timeline = Timeline(videoClips = (0 until count).map {
                Clip("clip$it", "media$it", if (trimmed) 1_000_000L else 0L,
                    if (trimmed) 5_000_000L else 6_000_000L, it * duration)
            }))
    }

    @Test fun manyJoinsUseOnlyTwoReusableInputs() {
        val source = project(12)
        val joins = source.timeline.videoClips.zipWithNext().mapIndexed { index, (a, b) ->
            Transition("join$index", a.id, b.id, 1_000_000L)
        }
        val plan = ProjectRenderPlan.compile(source.copy(timeline = source.timeline.copy(transitions = joins)))
        assertThat(plan.transitions).hasSize(11)
        assertThat(plan.layers.map { it.id }).containsExactly(ProjectRenderPlan.OUTGOING_LANE, ProjectRenderPlan.INCOMING_LANE)
        assertThat(plan.durationMicros).isEqualTo(source.timeline.renderedDurationMicros)
        val first = plan.transitions.first()
        assertThat(plan.transitionAt(first.startMicros)?.transition?.id).isEqualTo(first.transition.id)
        assertThat(plan.transitionAt(first.endMicros)).isNull()
    }

    @Test fun visualChangesPreserveTopologyButSourceChangesDoNot() {
        val source = project()
        val visual = source.copy(timeline = source.timeline.copy(
            textOverlays = listOf(TextOverlay("text", "hello")),
            videoClips = source.timeline.videoClips.map { it.copy(effects = listOf(VisualEffect("effect", VisualEffectType.Sepia))) }))
        assertThat(ProjectRenderPlan.compile(source).hasSameTopology(ProjectRenderPlan.compile(visual))).isTrue()
        val shorter = source.copy(timeline = source.timeline.copy(videoClips = source.timeline.videoClips.dropLast(1)))
        assertThat(ProjectRenderPlan.compile(source).hasSameTopology(ProjectRenderPlan.compile(shorter))).isFalse()
    }

    @Test fun handleAdjustmentAndTransitionUndoTogetherAndKeepMusicPosition() {
        val initial = project(2, trimmed = false)
        val music = AudioClip("music", "media0", sourceEndMicros = 6_000_000L, timelineStartMicros = 1_000_000L)
        val before = initial.copy(timeline = initial.timeline.copy(audioClips = listOf(music)))
        val proposal = TransitionHandlePlanner.plan(before, Transition("join", "clip0", "clip1", 800_000L))
        assertThat(proposal.outgoingTrimMicros).isEqualTo(400_000L)
        assertThat(proposal.incomingTrimMicros).isEqualTo(400_000L)
        assertThat(proposal.timelineReductionMicros).isEqualTo(800_000L)
        val history = EditHistory()
        val after = history.apply(before, TransitionHandlePlanner.Apply(proposal))
        assertThat(after.timeline.audioClips).containsExactly(music)
        assertThat(after.timeline.transitions).hasSize(1)
        val restored = history.undo(after)
        assertThat(restored.timeline).isEqualTo(before.timeline)
        assertThat(history.redo(restored).timeline).isEqualTo(after.timeline)
    }

    @Test fun splitPreservesSmoothAnimationAcrossTheJoin() {
        val keys = listOf(VisualKeyframe("a", 0, 0f, KeyframeInterpolation.Smooth), VisualKeyframe("b", 4_000_000L, 1f))
        val base = project(1)
        val clip = base.timeline.videoClips.single().copy(effects = listOf(VisualEffect("effect", VisualEffectType.InvertColor, keyframes = keys)))
        val before = base.copy(timeline = base.timeline.copy(videoClips = listOf(clip)))
        val after = TimelineCommands.SplitClip(clip.id, 1_500_000L).apply(before)
        val right = after.timeline.videoClips[1]
        assertThat(right.effects.single().evaluateIntensityAt(700_000L))
            .isEqualTo(clip.effects.single().evaluateIntensityAt(2_200_000L))
    }

    @Test fun overlayPastPrimaryEndIsClippedOnlyInTheRenderPlan() {
        val initial = project(1)
        val original = OverlayClip("pip", "media0", timelineStartMicros = 2_000_000L,
            timelineDurationMicros = 4_000_000L, sourceEndMicros = 4_000_000L)
        val source = initial.copy(timeline = initial.timeline.copy(overlayClips = listOf(original)))
        val plan = ProjectRenderPlan.compile(source)
        assertThat(plan.layers.single().timelineEndMicros).isEqualTo(source.timeline.renderedDurationMicros)
        assertThat(plan.layers.single().sourceEndMicros).isEqualTo(2_000_000L)
        assertThat(source.timeline.overlayClips.single()).isEqualTo(original)
    }

    @Test fun decoderAndClipTimeAreDistinctAtFractionalSpeed() {
        val export = ItemTimeMapping(4_000_000L, 1.5f)
        val preview = ItemTimeMapping(4_000_000L)
        assertThat(export.projectTime(5_500_000L)).isEqualTo(preview.projectTime(5_000_000L))
        assertThat(export.clipLocalTime(5_500_000L, 4_000_000L)).isEqualTo(1_000_000L)
    }
}
