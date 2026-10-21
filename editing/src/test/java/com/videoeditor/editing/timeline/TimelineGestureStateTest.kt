package com.videoeditor.editing.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one-gesture-at-a-time invariant: overlapping gesture starts are refused,
 * updates only touch the active gesture, and end/cancel always return to idle.
 */
class TimelineGestureStateTest {

    private val scrolling = TimelineGesture.Scrolling(isScrubbing = true)
    private val trimming = TimelineGesture.Trimming(
        clipId = "a",
        edge = TrimMath.Edge.Start,
        originalRangeMicros = 0L to 5_000_000L,
    )

    @Test
    fun `begins a gesture when idle`() {
        val state = TimelineGestureState()
        assertThat(state.isIdle).isTrue()
        val next = state.tryBegin(scrolling)
        assertThat(next.gesture).isEqualTo(scrolling)
        assertThat(next.isIdle).isFalse()
    }

    @Test
    fun `refuses overlapping gesture starts`() {
        val state = TimelineGestureState().tryBegin(scrolling)
        val refused = state.tryBegin(trimming)
        assertThat(refused.gesture).isEqualTo(scrolling)

        val refusedZoom = state.tryBegin(TimelineGesture.Zooming(1f, 100f))
        assertThat(refusedZoom.gesture).isEqualTo(scrolling)

        val refusedReorder = state.tryBegin(
            TimelineGesture.Reordering("b", proposedIndex = 0),
        )
        assertThat(refusedReorder.gesture).isEqualTo(scrolling)
    }

    @Test
    fun `update replaces transient gesture data`() {
        val state = TimelineGestureState().tryBegin(trimming)
        val updated = state.update { gesture ->
            (gesture as TimelineGesture.Trimming).copy(
                proposedRangeMicros = 1_000_000L to 5_000_000L,
            )
        }
        val trim = updated.gesture as TimelineGesture.Trimming
        assertThat(trim.proposedRangeMicros).isEqualTo(1_000_000L to 5_000_000L)
        assertThat(trim.clipId).isEqualTo("a")
    }

    @Test
    fun `update is a no-op when idle`() {
        val state = TimelineGestureState()
        val updated = state.update { TimelineGesture.Scrolling(isScrubbing = false) }
        assertThat(updated.isIdle).isTrue()
    }

    @Test
    fun `end and cancel return to idle from any gesture`() {
        for (gesture in listOf(
            scrolling,
            trimming,
            TimelineGesture.Zooming(2f, 300f),
            TimelineGesture.Reordering("c", 1, isSnapped = true),
        )) {
            assertThat(TimelineGestureState(gesture).end().isIdle).isTrue()
            assertThat(TimelineGestureState(gesture).cancel().isIdle).isTrue()
        }
    }

    @Test
    fun `a new gesture may begin after the previous ended`() {
        val state = TimelineGestureState().tryBegin(scrolling).end()
        val next = state.tryBegin(trimming)
        assertThat(next.gesture).isEqualTo(trimming)
    }
}
