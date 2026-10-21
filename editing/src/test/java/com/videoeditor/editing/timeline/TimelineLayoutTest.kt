package com.videoeditor.editing.timeline

import com.google.common.truth.Truth.assertThat
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.TextOverlay
import com.videoeditor.core.model.Timeline
import org.junit.Test

/**
 * Row geometry and touch resolution. The renderer and the hit tester share
 * [TimelineLayout], so a touch must resolve to exactly the element drawn at
 * that position — including the rule that only the selected clip exposes
 * trim handles.
 */
class TimelineLayoutTest {

    private val hitTester = TimelineHitTester()

    // 15s project, 100px/s, 1000px viewport, playhead at x=500, scroll 0.
    private val viewport = TimelineViewport(
        durationMicros = 15_000_000L,
        pixelsPerMicro = 100f / 1_000_000f,
        viewportWidthPx = 1000f,
        playheadXpx = 500f,
    )

    private fun project(): Project {
        val refs = listOf(
            MediaRef("ref-a", "file:///a.mp4", MediaType.Video, "a.mp4"),
            MediaRef("ref-b", "file:///b.mp4", MediaType.Video, "b.mp4"),
            MediaRef("ref-m", "file:///m.m4a", MediaType.Audio, "m.m4a"),
        )
        return Project(
            id = "p",
            name = "p",
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
            mediaRefs = refs,
            timeline = Timeline(
                videoClips = listOf(
                    Clip("a", "ref-a", 0L, 8_000_000L, timelineStartMicros = 0L),
                    Clip("b", "ref-b", 0L, 7_000_000L, timelineStartMicros = 8_000_000L),
                ),
                audioClips = listOf(
                    AudioClip("m1", "ref-m", 0L, 5_000_000L, timelineStartMicros = 0L),
                ),
                textOverlays = listOf(
                    TextOverlay("t1", "Hello title", startMicros = 2_000_000L, durationMicros = 3_000_000L),
                ),
            ),
        )
    }

    // -- row geometry --

    @Test
    fun `rows partition the layout height without gaps or overlap`() {
        assertThat(TimelineLayout.rowAtY(-1f)).isNull()
        assertThat(TimelineLayout.rowAtY(TimelineLayout.TOTAL_HEIGHT)).isNull()
        // A sweep through the layout must visit rows in order.
        val rows = (0..99).mapNotNull {
            TimelineLayout.rowAtY(TimelineLayout.TOTAL_HEIGHT * it / 100f)
        }
        assertThat(rows.distinct()).containsExactly(
            TimelineLayout.ROW_RULER,
            TimelineLayout.ROW_VIDEO,
            TimelineLayout.ROW_TEXT,
            TimelineLayout.ROW_AUDIO,
        ).inOrder()
    }

    @Test
    fun `rowTop plus rowHeight tiles the layout`() {
        var cursor = 0f
        for (row in listOf(
            TimelineLayout.ROW_RULER,
            TimelineLayout.ROW_VIDEO,
            TimelineLayout.ROW_TEXT,
            TimelineLayout.ROW_AUDIO,
        )) {
            assertThat(TimelineLayout.rowTop(row)).isWithin(1e-4f).of(cursor)
            cursor += TimelineLayout.rowHeight(row)
        }
        assertThat(cursor).isWithin(1e-4f).of(TimelineLayout.TOTAL_HEIGHT)
    }

    // -- hit testing --

    private fun hit(
        x: Float,
        y: Float,
        selectedClipId: String? = null,
        selectedAudioId: String? = null,
    ): TimelineHit = hitTester.hitTest(
        project = project(),
        viewport = viewport,
        scrollXPx = 0f,
        screenXPx = x,
        y = y,
        selection = when {
            selectedClipId != null -> TimelineSelection.Video(selectedClipId)
            selectedAudioId != null -> TimelineSelection.Audio(selectedAudioId)
            else -> TimelineSelection.None
        },
        handleHitSlopPx = 20f,
    )

    private fun videoY(): Float = TimelineLayout.rowTop(TimelineLayout.ROW_VIDEO) +
        TimelineLayout.rowHeight(TimelineLayout.ROW_VIDEO) / 2

    private fun audioY(): Float = TimelineLayout.rowTop(TimelineLayout.ROW_AUDIO) +
        TimelineLayout.rowHeight(TimelineLayout.ROW_AUDIO) / 2

    private fun textY(): Float = TimelineLayout.rowTop(TimelineLayout.ROW_TEXT) +
        TimelineLayout.rowHeight(TimelineLayout.ROW_TEXT) / 2

    @Test
    fun `ruler row hit starts a scrub`() {
        // Layout units, not pixels: the ruler row is 0..0.55 units tall.
        val y = TimelineLayout.rowTop(TimelineLayout.ROW_RULER) + 0.1f
        assertThat(hit(500f, y)).isEqualTo(TimelineHit.Ruler)
    }

    @Test
    fun `video clip hit resolves the clip under the touch`() {
        // At 100px/s and scroll 0, screen x = 500 (pad) + t*100. x=800 → t=3s → clip a.
        val a = hit(800f, videoY())
        assertThat(a).isInstanceOf(TimelineHit.VideoClip::class.java)
        assertThat((a as TimelineHit.VideoClip).clipId).isEqualTo("a")
        assertThat(a.handle).isNull()

        // x=1500 → t=10s → clip b (8..15s).
        val b = hit(1500f, videoY())
        assertThat((b as TimelineHit.VideoClip).clipId).isEqualTo("b")
    }

    @Test
    fun `clip boundary is start-inclusive and end-exclusive`() {
        // t=8s is exactly clip b's start: x = 500 + 800 = 1300.
        val atBoundary = hit(1300f, videoY())
        assertThat((atBoundary as TimelineHit.VideoClip).clipId).isEqualTo("b")
    }

    @Test
    fun `trim handles only hit on the selected clip`() {
        // Clip a's start edge is at t=0 → x=500; end edge at t=8s → x=1300.
        val unselected = hit(500f, videoY(), selectedClipId = null)
        assertThat((unselected as TimelineHit.VideoClip).handle).isNull()

        val otherSelected = hit(500f, videoY(), selectedClipId = "b")
        assertThat((otherSelected as TimelineHit.VideoClip).handle).isNull()

        val startHandle = hit(500f, videoY(), selectedClipId = "a")
        assertThat((startHandle as TimelineHit.VideoClip).handle).isEqualTo(TrimMath.Edge.Start)

        val endHandle = hit(1300f, videoY(), selectedClipId = "a")
        assertThat((endHandle as TimelineHit.VideoClip).handle).isEqualTo(TrimMath.Edge.End)
    }

    @Test
    fun `handle slop widens the grab area`() {
        // 15px inside the 20px slop around clip a's start edge (x=500).
        val withinSlop = hit(485f, videoY(), selectedClipId = "a")
        assertThat((withinSlop as TimelineHit.VideoClip).handle).isEqualTo(TrimMath.Edge.Start)
    }

    @Test fun `selected text duration handles share exact drawn interval edges`() {
        fun textHit(x: Float, selection: TimelineSelection) = hitTester.hitTest(
            project(), viewport, 0f, x, textY(), selection, 20f)
        assertThat((textHit(700f, TimelineSelection.None) as TimelineHit.TextClip).handle).isNull()
        assertThat((textHit(690f, TimelineSelection.Text("t1")) as TimelineHit.TextClip).handle).isEqualTo(TrimMath.Edge.Start)
        assertThat((textHit(1010f, TimelineSelection.Text("t1")) as TimelineHit.TextClip).handle).isEqualTo(TrimMath.Edge.End)
    }

    @Test
    fun `audio and text rows resolve their clips`() {
        // m1 spans t=0..5s → x 500..1000.
        val audio = hit(700f, audioY())
        assertThat((audio as TimelineHit.AudioClip).audioClipId).isEqualTo("m1")

        // Outside m1 (t=7s → x=1200) is empty.
        assertThat(hit(1200f, audioY())).isEqualTo(TimelineHit.Empty)

        // t1 spans t=2..5s → x 700..1000.
        val text = hit(800f, textY())
        assertThat((text as TimelineHit.TextClip).overlayId).isEqualTo("t1")
        assertThat(hit(600f, textY())).isEqualTo(TimelineHit.Empty)
    }

    @Test
    fun `audio trim handles only hit on the selected audio clip`() {
        // m1 spans t=0..5s -> x 500 (start) .. 1000 (end).
        val unselected = hit(700f, audioY())
        assertThat((unselected as TimelineHit.AudioClip).handle).isNull()

        val startHandle = hit(500f, audioY(), selectedAudioId = "m1")
        assertThat((startHandle as TimelineHit.AudioClip).handle)
            .isEqualTo(TrimMath.Edge.Start)

        val endHandle = hit(1000f, audioY(), selectedAudioId = "m1")
        assertThat((endHandle as TimelineHit.AudioClip).handle)
            .isEqualTo(TrimMath.Edge.End)

        // Selecting a video clip must not expose audio handles.
        val videoSelected = hit(500f, audioY(), selectedClipId = "a")
        assertThat((videoSelected as TimelineHit.AudioClip).handle).isNull()
    }

    @Test
    fun `video selection does not leak audio selection`() {
        val h = hit(700f, audioY(), selectedClipId = "a")
        assertThat(h).isInstanceOf(TimelineHit.AudioClip::class.java)
    }

    @Test
    fun `hit accounts for scroll`() {
        // With scroll 300, content shifts left: t=3s now sits at x = 500+300-300 = 500.
        val scrolled = hitTester.hitTest(
            project = project(),
            viewport = viewport,
            scrollXPx = 300f,
            screenXPx = 500f,
            y = videoY(),
            selection = TimelineSelection.None,
            handleHitSlopPx = 20f,
        )
        assertThat((scrolled as TimelineHit.VideoClip).clipId).isEqualTo("a")
    }

    // -- reorder index --

    @Test
    fun `reorder index counts clips whose center the drop passed`() {
        val timeline = project().timeline
        // Dragged clip "a" removed; others = [b: 8..15s]. Dropping at 4s → index 0.
        assertThat(hitTester.reorderIndexAt(timeline, "a", 4_000_000L)).isEqualTo(0)
        // Dropping past b's center (11.5s) → index 1.
        assertThat(hitTester.reorderIndexAt(timeline, "a", 13_000_000L)).isEqualTo(1)
    }

    @Test
    fun `reorder index with three clips`() {
        val timeline = Timeline(
            videoClips = listOf(
                Clip("a", "r", 0L, 4_000_000L, timelineStartMicros = 0L),
                Clip("b", "r", 0L, 4_000_000L, timelineStartMicros = 4_000_000L),
                Clip("c", "r", 0L, 4_000_000L, timelineStartMicros = 8_000_000L),
            ),
        )
        // Drag "b"; others a (center 2s), c (center 10s).
        assertThat(hitTester.reorderIndexAt(timeline, "b", 1_000_000L)).isEqualTo(0)
        assertThat(hitTester.reorderIndexAt(timeline, "b", 5_000_000L)).isEqualTo(1)
        assertThat(hitTester.reorderIndexAt(timeline, "b", 11_000_000L)).isEqualTo(2)
    }

    // -- content derivation --

    @Test
    fun `content mirrors the model`() {
        val content = TimelineContent.from(project())
        assertThat(content.durationMicros).isEqualTo(15_000_000L)
        assertThat(content.videoItems.map { it.clipId }).containsExactly("a", "b").inOrder()
        assertThat(content.videoItems[0].endMicros).isEqualTo(8_000_000L)
        assertThat(content.audioItems.single().audioClipId).isEqualTo("m1")
        assertThat(content.textItems.single().label).isEqualTo("Hello title")
    }

    @Test
    fun `long text labels are truncated`() {
        val long = "A".repeat(40)
        val content = TimelineContent.from(
            project().let { p ->
                p.copy(
                    timeline = p.timeline.copy(
                        textOverlays = listOf(
                            TextOverlay("t", long, startMicros = 0L, durationMicros = 1_000_000L),
                        ),
                    ),
                )
            },
        )
        val label = content.textItems.single().label
        assertThat(label.length).isAtMost(24)
        assertThat(label.endsWith("…")).isTrue()
    }

    @Test
    fun `multiline text labels collapse to one line`() {
        val content = TimelineContent.from(
            project().let { p ->
                p.copy(
                    timeline = p.timeline.copy(
                        textOverlays = listOf(
                            TextOverlay("t", "first\nsecond", startMicros = 0L, durationMicros = 1_000_000L),
                        ),
                    ),
                )
            },
        )
        assertThat(content.textItems.single().label).isEqualTo("first second")
    }
}
