package com.videoeditor.editing.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackLanesTest {
    @Test fun expandedGeometryKeepsEveryOverlappingLaneFullHeightAndSelectable() {
        val geometry = TimelineGeometry(textLanes = 4, audioLanes = 3)
        val lanes = (0..3).associate { "caption-$it" to it }
        val row = TimelineLayout.ROW_TEXT
        for (lane in 0..3) {
            val center = geometry.rowTop(row) + TimelineLayout.TEXT_HEIGHT * (lane + .5f)
            assertThat(geometry.rowAtY(center)).isEqualTo(row)
            assertThat(geometry.laneAt(center, row, lanes)).isEqualTo(lane)
        }
        assertThat(geometry.rowTop(TimelineLayout.ROW_AUDIO)).isWithin(.0001f)
            .of(TimelineLayout.RULER_HEIGHT + TimelineLayout.VIDEO_HEIGHT + 4 * TimelineLayout.TEXT_HEIGHT)
        assertThat(geometry.rowHeight(TimelineLayout.ROW_AUDIO) / 3).isWithin(.0001f).of(TimelineLayout.AUDIO_HEIGHT)
        assertThat(geometry.rowAtY(geometry.totalHeight)).isNull()
    }
    @Test fun overlapsUseSeparateLanesAndTouchingEndpointsReuseThem() {
        val intervals = listOf(Triple("a", 0L, 10L), Triple("b", 5L, 15L), Triple("c", 10L, 20L))
        val lanes = TrackLanes.assign(intervals)
        assertThat(lanes["a"]).isEqualTo(0)
        assertThat(lanes["b"]).isEqualTo(1)
        assertThat(lanes["c"]).isEqualTo(0)
        assertThat(TrackLanes.assign(intervals.reversed())).isEqualTo(lanes)
        assertThat(TrackLanes.at(TimelineLayout.rowTop(TimelineLayout.ROW_TEXT) +
            TimelineLayout.TEXT_HEIGHT * 0.75f, TimelineLayout.ROW_TEXT, lanes)).isEqualTo(1)
    }
}
