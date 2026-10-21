package com.videoeditor.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Project-time ↔ source-time mapping: the conversion shared by preview seeking
 * and export rendering. If these are wrong, seeking lands on the wrong frame
 * and the exported video diverges from the edit.
 */
class TimelineMappingTest {

    private fun clip(
        id: String,
        sourceStart: Long,
        sourceEnd: Long,
        timelineStart: Long,
        speed: Float = 1f,
    ) = Clip(
        id = id,
        mediaRefId = "ref-$id",
        sourceStartMicros = sourceStart,
        sourceEndMicros = sourceEnd,
        timelineStartMicros = timelineStart,
        playbackSpeed = speed,
    )

    /** a: source 2..10s trimmed, b: 4s, c: 6s at 2x. Packed timeline: 0..8, 8..12, 12..15. */
    private fun threeClips() = Timeline(
        videoClips = listOf(
            clip("a", sourceStart = 2_000_000, sourceEnd = 10_000_000, timelineStart = 0),
            clip("b", sourceStart = 0, sourceEnd = 4_000_000, timelineStart = 8_000_000),
            clip("c", sourceStart = 1_000_000, sourceEnd = 7_000_000, timelineStart = 12_000_000, speed = 2f),
        ),
    )

    @Test
    fun `source time accounts for trim offset`() {
        val timeline = threeClips()
        // 3s into clip a, which starts at source 2s.
        assertThat(timeline.sourceTimeAt(3_000_000)).isEqualTo(5_000_000)
    }

    @Test
    fun `source time crosses clip boundary correctly`() {
        val timeline = threeClips()
        // 9s on the timeline = 1s into clip b (source start 0).
        assertThat(timeline.sourceTimeAt(9_000_000)).isEqualTo(1_000_000)
        // 13s on the timeline = 1s into clip c; at 2x speed the source has
        // advanced 2s from its 1s start.
        assertThat(timeline.sourceTimeAt(13_000_000)).isEqualTo(3_000_000)
    }

    @Test
    fun `source time at exact boundary belongs to next clip`() {
        val timeline = threeClips()
        // timelineStartMicros is inclusive, timelineEndMicros exclusive.
        assertThat(timeline.sourceTimeAt(8_000_000)).isEqualTo(0L) // start of b
        assertThat(timeline.sourceTimeAt(7_999_999)).isEqualTo(9_999_999) // end of a
    }

    @Test
    fun `source time beyond track is null`() {
        val timeline = threeClips()
        assertThat(timeline.sourceTimeAt(15_000_000)).isNull()
        assertThat(timeline.sourceTimeAt(99_000_000)).isNull()
    }

    @Test
    fun `project time inverts source time within trim range`() {
        val timeline = threeClips()
        assertThat(timeline.projectTimeFor("a", 5_000_000)).isEqualTo(3_000_000)
        assertThat(timeline.projectTimeFor("c", 2_000_000)).isEqualTo(12_500_000)
    }

    @Test
    fun `project time outside trim range is null`() {
        val timeline = threeClips()
        assertThat(timeline.projectTimeFor("a", 1_000_000)).isNull() // before trim in
        assertThat(timeline.projectTimeFor("a", 10_000_000)).isNull() // at trim out (exclusive)
        assertThat(timeline.projectTimeFor("missing", 0)).isNull()
    }

    @Test
    fun `mapping round-trips at speed 1`() {
        val timeline = threeClips()
        for (t in 0L..7_999_000L step 1_000_000) {
            val source = timeline.sourceTimeAt(t)!!
            assertThat(timeline.projectTimeFor("a", source)).isEqualTo(t)
        }
    }

    @Test
    fun `clip timeline duration respects speed`() {
        val fast = clip("x", sourceStart = 0, sourceEnd = 6_000_000, timelineStart = 0, speed = 2f)
        assertThat(fast.timelineDurationMicros).isEqualTo(3_000_000)
        val slow = clip("y", sourceStart = 0, sourceEnd = 6_000_000, timelineStart = 0, speed = 0.5f)
        assertThat(slow.timelineDurationMicros).isEqualTo(12_000_000)
    }

    @Test
    fun `clipAt finds containing clip and index`() {
        val timeline = threeClips()
        assertThat(timeline.clipAt(12_500_000)?.first).isEqualTo(2)
        assertThat(timeline.clipAt(12_500_000)?.second?.id).isEqualTo("c")
        assertThat(timeline.clipAt(-1)).isNull()
    }
    @Test
    fun `long music is clipped for rendering without changing saved trims`() {
        val audio = AudioClip("music", "music-ref", sourceEndMicros = 20_000_000)
        val timeline = Timeline(videoClips = listOf(clip("v", 0, 6_000_000, 0)), audioClips = listOf(audio))
        assertThat(timeline.renderedDurationMicros).isEqualTo(6_000_000)
        assertThat(timeline.renderedAudioClip(audio)?.sourceEndMicros).isEqualTo(6_000_000)
        assertThat(timeline.audioClips.single().sourceEndMicros).isEqualTo(20_000_000)
    }

    @Test
    fun `audio-only project keeps full music duration and offset`() {
        val audio = AudioClip("music", "ref", sourceEndMicros = 20_000_000, timelineStartMicros = 2_000_000)
        val timeline = Timeline(audioClips = listOf(audio))
        assertThat(timeline.renderedDurationMicros).isEqualTo(22_000_000)
        assertThat(timeline.renderedAudioClip(audio)).isEqualTo(audio)
    }

    @Test
    fun `photo end card explicitly extends visual canvas`() {
        val timeline = Timeline(videoClips = listOf(clip("v", 0, 6_000_000, 0),
            Clip("card", "photo", timelineStartMicros = 6_000_000, photoDurationMicros = 3_000_000)))
        assertThat(timeline.renderedDurationMicros).isEqualTo(9_000_000)
    }

    @Test
    fun `render window respects offset trim and audio speed`() {
        val timeline = Timeline(videoClips = listOf(clip("v", 0, 6_000_000, 0)))
        val audio = AudioClip("music", "ref", sourceStartMicros = 1_000_000,
            sourceEndMicros = 20_000_000, timelineStartMicros = 2_000_000, playbackSpeed = 2f)
        assertThat(timeline.renderedAudioClip(audio)?.sourceEndMicros).isEqualTo(9_000_000)
        assertThat(timeline.renderedAudioClip(audio.copy(timelineStartMicros = 6_000_000))).isNull()
    }

}
