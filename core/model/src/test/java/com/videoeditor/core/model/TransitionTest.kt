package com.videoeditor.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TransitionTest {
    private fun project(duration: Long = 1_000_001): Project {
        val refs = listOf("a", "b").map { MediaRef(it, "file:///$it", MediaType.Video, it, durationMicros = 10_000_000) }
        return Project("p", "Transitions", 0, 0, mediaRefs = refs, timeline = Timeline(
            videoClips = listOf(Clip("a", "a", 1_000_000, 4_000_000), Clip("b", "b", 2_000_000, 5_000_000, 3_000_000)),
            transitions = listOf(Transition("t", "a", "b", duration))))
    }
    @Test fun odd_centered_window_preserves_duration_and_maps_both_sources() {
        val p = project(); val t = p.resolveTransitions().single()
        assertEquals(2_500_000L, t.startMicros); assertEquals(3_500_001L, t.endMicros)
        assertEquals(6_000_000L, p.timeline.renderedDurationMicros)
        assertEquals(3_500_000L, t.outgoingSourceAt(t.startMicros))
        assertEquals(1_500_000L, t.incomingSourceAt(t.startMicros))
        assertEquals(4_500_001L, t.outgoingSourceAt(t.endMicros))
        assertEquals(0f, t.alphaAt(t.startMicros)); assertEquals(1f, t.alphaAt(t.endMicros))
        assertEquals(.5f, t.alphaAt(3_000_000), .000001f)
    }
    @Test fun fractional_speed_uses_floor_in_both_directions() {
        assertEquals(-2L, ResolvedTransition.sourceDelta(-1, 1.5f))
        assertEquals(1L, ResolvedTransition.sourceDelta(1, 1.5f))
        assertEquals(100_000L, ResolvedTransition.sourceDelta(1_000_000, .1f))
    }
    @Test fun legacy_document_and_transition_round_trip() {
        val p = project(); assertEquals(p, Json.decodeFromString(Project.serializer(), Json.encodeToString(Project.serializer(), p)))
        val old = """{"id":"p","name":"Old","createdAtMillis":0,"updatedAtMillis":0}"""
        assertTrue(Json.decodeFromString(Project.serializer(), old).timeline.transitions.isEmpty())
    }
    @Test fun rejects_unavailable_handles_nonadjacency_unknown_duration_and_bad_speed() {
        val p = project()
        val variants = listOf(
            p.copy(timeline = p.timeline.copy(videoClips = p.timeline.videoClips.map { it.copy(sourceStartMicros = 0) })),
            p.copy(timeline = p.timeline.copy(transitions = listOf(Transition("t", "b", "a", 1000)))),
            p.copy(mediaRefs = p.mediaRefs.map { it.copy(durationMicros = null) }),
            p.copy(timeline = p.timeline.copy(videoClips = p.timeline.videoClips.map { it.copy(playbackSpeed = Float.NaN) })),
            project(0), project(Long.MAX_VALUE),
            p.copy(timeline = p.timeline.copy(transitions = p.timeline.transitions + p.timeline.transitions)))
        variants.forEach { bad -> assertThrows(IllegalArgumentException::class.java) { bad.resolveTransitions() } }
    }
    @Test fun adjacent_transitions_can_touch_but_cannot_overlap() {
        val p = project(2_000_000)
        val c = Clip("c", "b", 2_000_000, 5_000_000, 6_000_000)
        val timeline = p.timeline.copy(videoClips = p.timeline.videoClips + c,
            transitions = p.timeline.transitions + Transition("u", "b", "c", 4_000_000))
        assertEquals(2, p.copy(timeline = timeline).resolveTransitions().size)
        assertThrows(IllegalArgumentException::class.java) {
            p.copy(timeline = timeline.copy(transitions = timeline.transitions.map {
                if (it.id == "u") it.copy(durationMicros = 4_000_002) else it
            })).resolveTransitions()
        }
    }
    @Test fun speed_scaled_handles_and_exact_media_boundary_are_validated() {
        val p = project(1_000_000)
        val a = p.timeline.videoClips[0].copy(playbackSpeed = 1.5f)
        val b = p.timeline.videoClips[1].copy(timelineStartMicros = 2_000_000, playbackSpeed = .5f)
        val good = p.copy(timeline = p.timeline.copy(videoClips = listOf(a,b)),
            mediaRefs = p.mediaRefs.map { if (it.id == "a") it.copy(durationMicros = 4_750_000) else it })
        val t = good.resolveTransitions().single()
        assertEquals(4_750_000L, t.outgoingSourceAt(t.endMicros))
        assertEquals(1_750_000L, t.incomingSourceAt(t.startMicros))
        assertThrows(IllegalArgumentException::class.java) {
            good.copy(mediaRefs = good.mediaRefs.map { if (it.id == "a") it.copy(durationMicros = 4_749_999) else it }).resolveTransitions()
        }
    }

}
