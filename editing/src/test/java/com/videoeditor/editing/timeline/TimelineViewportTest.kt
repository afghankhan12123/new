package com.videoeditor.editing.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The timeline's coordinate system: time↔pixel conversions, the fixed-playhead
 * scroll mapping, and zoom focal-point preservation. Every gesture in the UI
 * resolves positions through these functions, so an error here misplaces
 * clips, handles, and the playhead everywhere at once.
 */
class TimelineViewportTest {

    // 15s project, 100px per second, 1000px viewport, playhead at x=500.
    private fun viewport(
        durationMicros: Long = 15_000_000L,
        pixelsPerMicro: Float = 100f / 1_000_000f,
        viewportWidthPx: Float = 1000f,
        playheadXpx: Float = 500f,
    ) = TimelineViewport(durationMicros, pixelsPerMicro, viewportWidthPx, playheadXpx)

    // -- time → pixel --

    @Test
    fun `content x grows linearly with time`() {
        val v = viewport()
        // 100px per second: t=0 at the start pad, t=5s at pad+500.
        assertThat(v.contentXAtTime(0L)).isEqualTo(500f)
        assertThat(v.contentXAtTime(5_000_000L)).isEqualTo(1000f)
        assertThat(v.contentXAtTime(15_000_000L)).isEqualTo(2000f)
    }

    @Test
    fun `screen x accounts for scroll`() {
        val v = viewport()
        assertThat(v.screenXAtTime(scrollXPx = 300f, timeMicros = 3_000_000L)).isEqualTo(500f)
        // Scrolling 300px moves content left; t=0 leaves the screen at x=200.
        assertThat(v.screenXAtTime(scrollXPx = 300f, timeMicros = 0L)).isEqualTo(200f)
    }

    @Test
    fun `scrollXForTime puts a time exactly under the playhead`() {
        val v = viewport()
        for (timeMicros in listOf(0L, 3_750_000L, 9_999_999L, 15_000_000L)) {
            val scroll = v.scrollXForTime(timeMicros)
            assertThat(v.screenXAtTime(scroll, timeMicros)).isEqualTo(v.playheadXpx)
        }
    }

    // -- pixel → time --

    @Test
    fun `time at content x inverts content x at time`() {
        val v = viewport()
        for (timeMicros in listOf(0L, 1L, 2_500_000L, 12_345_678L, 15_000_000L)) {
            val x = v.contentXAtTime(timeMicros)
            assertThat(v.timeAtContentX(x)).isEqualTo(timeMicros)
        }
    }

    @Test
    fun `playhead time is the scrub position and clamps to the project`() {
        val v = viewport()
        // Scroll 0 → playhead at time 0; scroll 700 → time 7s.
        assertThat(v.playheadTimeMicros(0f)).isEqualTo(0L)
        assertThat(v.playheadTimeMicros(700f)).isEqualTo(7_000_000L)
        // Max scroll puts the project end under the playhead, never beyond.
        assertThat(v.playheadTimeMicros(v.maxScrollPx)).isEqualTo(15_000_000L)
        assertThat(v.playheadTimeMicros(1e9f)).isEqualTo(15_000_000L)
    }

    @Test
    fun `playhead time snaps to exact ends despite float round-trip loss`() {
        // A messy real-device scale: 1080px viewport over 45s. Reconstructing
        // the duration through (d·ppm)/ppm lands one micro short in float; the
        // ends must still report exactly 0 and duration (regression from an
        // on-device test failure).
        val v = viewport(
            durationMicros = 15_000_000L,
            pixelsPerMicro = 1080f / 45_000_000f,
            viewportWidthPx = 1080f,
            playheadXpx = 540f,
        )
        assertThat(v.playheadTimeMicros(0f)).isEqualTo(0L)
        assertThat(v.playheadTimeMicros(v.maxScrollPx)).isEqualTo(15_000_000L)
        // Over-scroll clamps first, then snaps.
        assertThat(v.playheadTimeMicros(v.maxScrollPx + 500f)).isEqualTo(15_000_000L)
        assertThat(v.playheadTimeMicros(-500f)).isEqualTo(0L)
    }

    @Test
    fun `scroll range maps time 0 and duration under the playhead exactly`() {
        val v = viewport()
        // contentWidth = 500 + 1500 + 500 = 2500; maxScroll = 1500.
        assertThat(v.maxScrollPx).isEqualTo(1500f)
        assertThat(v.playheadTimeMicros(0f)).isEqualTo(0L)
        assertThat(v.playheadTimeMicros(v.maxScrollPx)).isEqualTo(v.durationMicros)
    }

    @Test
    fun `empty project has zero scroll range`() {
        val v = viewport(durationMicros = 0L)
        assertThat(v.maxScrollPx).isEqualTo(0f)
        assertThat(v.playheadTimeMicros(0f)).isEqualTo(0L)
        assertThat(v.clampScroll(-5f)).isEqualTo(0f)
        assertThat(v.clampScroll(999f)).isEqualTo(0f)
    }

    @Test
    fun `fit uses actual project duration instead of empty context`() {
        val zoom = TimelineViewport.fitZoomFor(13_000_000L, 1000f)
        assertThat(13_000_000L * zoom).isWithin(0.01f).of(450f)
        assertThat(TimelineViewport.fitZoomFor(0L, 1000f)).isEqualTo(450f / 250_000L)
    }

    // -- zoom --

    @Test
    fun `zoom limits bracket a project with min and max visible spans`() {
        val (minPpm, maxPpm) = TimelineViewport.zoomLimits(
            durationMicros = 15_000_000L,
            viewportWidthPx = 1000f,
        )
        // Min zoom: whole project + 30s slack across the viewport.
        assertThat(minPpm).isEqualTo(1000f / 45_000_000f)
        // Max zoom: 250ms visible span across the viewport.
        assertThat(maxPpm).isEqualTo(1000f / 250_000f)
        assertThat(maxPpm).isGreaterThan(minPpm)
    }

    @Test
    fun `zoom limits stay sane for empty and tiny projects`() {
        val (minPpm, maxPpm) = TimelineViewport.zoomLimits(
            durationMicros = 0L,
            viewportWidthPx = 1000f,
        )
        assertThat(minPpm).isEqualTo(1000f / 30_000_000f)
        assertThat(maxPpm).isGreaterThan(minPpm)
    }

    @Test
    fun `zoom preserves the time under the focal point`() {
        val v = viewport()
        val scroll = 400f
        val focalX = 620f
        val focalTime = v.timeAtScreenX(scroll, focalX)

        // Zoom in 4x around the focal point.
        val newPpm = v.pixelsPerMicro * 4f
        val zoomed = v.copy(pixelsPerMicro = newPpm)
        val newScroll = v.scrollXForZoom(newPpm, focalX, scroll)

        // The same timeline instant must still sit under the finger.
        assertThat(zoomed.timeAtScreenX(newScroll, focalX)).isEqualTo(focalTime)
    }

    @Test
    fun `zoom out preserves the focal time too`() {
        val v = viewport(pixelsPerMicro = 400f / 1_000_000f) // zoomed in
        val scroll = 900f
        val focalX = 250f
        val focalTime = v.timeAtScreenX(scroll, focalX)

        val newPpm = 20f / 1_000_000f // zoomed way out
        val zoomed = v.copy(pixelsPerMicro = newPpm)
        val newScroll = v.scrollXForZoom(newPpm, focalX, scroll)
        val preserved = zoomed.timeAtScreenX(newScroll, focalX)

        // Rounding to whole pixels can cost a fraction of the (now huge)
        // microsecond-per-pixel; assert within one pixel of timeline time.
        assertThat(kotlin.math.abs(preserved - focalTime))
            .isAtMost((1f / newPpm).toLong() + 1L)
    }

    @Test
    fun `zoom clamps the resulting scroll into range`() {
        val v = viewport()
        val newPpm = v.pixelsPerMicro * 50f
        val scroll = v.scrollXForZoom(newPpm, focalScreenXPx = 0f, previousScrollXPx = v.maxScrollPx)
        val zoomed = v.copy(pixelsPerMicro = newPpm)
        assertThat(scroll).isAtLeast(0f)
        assertThat(scroll).isAtMost(zoomed.maxScrollPx)
    }

    // -- scrub policy boundary: playhead time during playback-follow --

    @Test
    fun `scroll for playback time keeps the playhead centered mid-timeline`() {
        val v = viewport()
        val time = 7_500_000L
        val scroll = v.scrollXForTime(time)
        assertThat(v.screenXAtTime(scroll, time)).isEqualTo(500f)
        // Following playback near the start: scroll stays clamped at 0 while
        // the playhead sweeps from x=500 toward the content — the timeline
        // does not jump for times whose centering scroll would be negative.
        assertThat(v.scrollXForTime(0L)).isEqualTo(0f)
    }

    // Fixed line must represent the clock at every zoom, including both edges.
    @Test
    fun `follow maps every playback sample exactly under the fixed line`() {
        for (scale in listOf(10f, 100f, 1000f)) {
            val v = viewport(pixelsPerMicro = scale / 1_000_000f)
            for (time in 0L..15_000_000L step 33_000L) {
                val scroll = v.scrollXForFollow(time)
                assertThat(v.screenXAtTime(scroll, time)).isWithin(0.01f).of(v.playheadXpx)
                assertThat(v.playheadTimeMicros(scroll)).isWithin(10L).of(time)
            }
            assertThat(v.scrollXForFollow(15_000_000L)).isEqualTo(v.maxScrollPx)
            assertThat(v.scrollXForFollow(0L)).isEqualTo(0f)
        }
    }

    @Test
    fun `follow reconciles backward seek replay and duration clamps`() {
        val v = viewport()
        assertThat(v.scrollXForFollow(8_000_000L)).isWithin(.01f).of(800f)
        assertThat(v.scrollXForFollow(2_000_000L)).isWithin(.01f).of(200f)
        assertThat(v.scrollXForFollow(0L)).isEqualTo(0f)
        assertThat(v.scrollXForFollow(-1L)).isEqualTo(0f)
        assertThat(v.scrollXForFollow(99_000_000L)).isEqualTo(v.maxScrollPx)
    }

    @Test
    fun `fit follows in the first second and the final tail`() {
        val v = viewport(pixelsPerMicro = TimelineViewport.fitZoomFor(15_000_000, 1000f))
        val first = v.scrollXForFollow(1_000_000)
        val last = v.scrollXForFollow(14_900_000)
        assertThat(first).isGreaterThan(0f)
        assertThat(last).isGreaterThan(v.scrollXForFollow(14_000_000))
        assertThat(v.screenXAtTime(first, 1_000_000)).isWithin(.01f).of(v.playheadXpx)
        assertThat(v.screenXAtTime(last, 14_900_000)).isWithin(.01f).of(v.playheadXpx)
    }

    @Test
    fun `follow distance uses project time and current scale without drift`() {
        for (scale in listOf(10f, 100f, 1000f)) {
            val v = viewport(pixelsPerMicro = scale / 1_000_000f)
            val initial = v.scrollXForFollow(3_000_000)
            assertThat(v.scrollXForFollow(4_000_000) - initial).isWithin(.01f).of(scale)
            assertThat(v.scrollXForFollow(5_000_000) - initial).isWithin(.01f).of(2 * scale)
        }
    }

    // -- validation --

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-positive pixels per micro`() {
        viewport(pixelsPerMicro = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects playhead outside viewport`() {
        viewport(playheadXpx = 1200f)
    }
}
