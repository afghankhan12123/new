package com.videoeditor.core.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.ClippingMediaPeriod
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Loading must not finish while a selected AVC stream still needs its bounded dependency drain. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ClippingReorderReadinessDeviceTest {
    private fun selectedPeriod(mime: String, enabled: Boolean): Pair<ClippingMediaPeriod, SampleStream> {
        val format = Format.Builder().setSampleMimeType(mime).build()
        var formatRead = false
        val child = object : SampleStream {
            override fun isReady() = true
            override fun maybeThrowError() = Unit
            override fun skipData(positionUs: Long) = 0
            override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, flags: Int): Int {
                if (!formatRead) {
                    formatRead = true
                    holder.format = format
                    return C.RESULT_FORMAT_READ
                }
                buffer.timeUs = 3_100_000
                return C.RESULT_BUFFER_READ
            }
        }
        val period = Proxy.newProxyInstance(MediaPeriod::class.java.classLoader, arrayOf(MediaPeriod::class.java)) { _, method, args ->
            when (method.name) {
                "selectTracks" -> {
                    @Suppress("UNCHECKED_CAST")
                    val streams = args!![2] as Array<SampleStream?>
                    streams[0] = child
                    args[4]
                }
                "getBufferedPositionUs", "getNextLoadPositionUs" -> 4_000_000L
                else -> error("Unexpected period call: ${method.name}")
            }
        } as MediaPeriod
        val selection = Proxy.newProxyInstance(ExoTrackSelection::class.java.classLoader, arrayOf(ExoTrackSelection::class.java)) { _, method, _ ->
            when (method.name) {
                "getSelectedFormat" -> format
                else -> error("Unexpected selection call: ${method.name}")
            }
        } as ExoTrackSelection
        val clipped = ClippingMediaPeriod(period, false, 1_000_000, 3_000_000)
        if (enabled) clipped.enableVideoReorderDrain(MediaItem.fromUri("file:///owned-avc-fixture.mp4"))
        val streams = arrayOfNulls<SampleStream>(1)
        clipped.selectTracks(arrayOf(selection), booleanArrayOf(false), streams, booleanArrayOf(false), 1_000_000)
        return clipped to checkNotNull(streams[0])
    }

    @Test fun selected_avc_keeps_loading_before_first_renderer_format_read() {
        val (period, stream) = selectedPeriod(MimeTypes.VIDEO_H264, true)
        assertEquals(4_000_000L, period.bufferedPositionUs)
        assertEquals(4_000_000L, period.nextLoadPositionUs)
        val holder = FormatHolder()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        assertEquals(C.RESULT_FORMAT_READ, stream.readData(holder, buffer, 0))
        repeat(32) {
            buffer.clear()
            assertEquals(C.RESULT_BUFFER_READ, stream.readData(holder, buffer, 0))
            org.junit.Assert.assertFalse(buffer.isEndOfStream)
        }
        assertEquals(C.TIME_END_OF_SOURCE, period.nextLoadPositionUs)
        buffer.clear()
        stream.readData(holder, buffer, 0)
        org.junit.Assert.assertTrue(buffer.isEndOfStream)
    }

    @Test fun non_avc_and_non_opted_in_streams_keep_pinned_clipping() {
        for ((mime, enabled) in listOf(MimeTypes.VIDEO_H265 to true, MimeTypes.VIDEO_AV1 to true, MimeTypes.VIDEO_H264 to false)) {
            val (period, _) = selectedPeriod(mime, enabled)
            assertEquals(C.TIME_END_OF_SOURCE, period.bufferedPositionUs)
            assertEquals(C.TIME_END_OF_SOURCE, period.nextLoadPositionUs)
        }
    }
}
