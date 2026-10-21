package com.videoeditor.core.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Isolate codec packet timestamps from Transformer, clipping and composition. */
@RunWith(AndroidJUnit4::class)
class AacPacketizationDeviceTest {
    @Test
    fun same_pcm_with_different_input_packet_sizes_retains_raw_codec_timestamps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "aac-packetization/run-${SystemClock.elapsedRealtimeNanos()}")
            .apply { check(mkdirs()) }
        val samples = ShortArray(192000) { i ->
            (4096 * sin(2 * PI * (if (i < 96000) 440 else 880) * i / 48000)).toInt().toShort()
        }
        val raw = java.nio.ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(raw::putShort)
        File(dir, "source.s16le").writeBytes(raw.array())
        for ((name, pattern) in listOf("full" to intArrayOf(1024), "partial" to intArrayOf(1024, 128, 1024, 768),
            "negative-pts" to intArrayOf(1024), "format-delay" to intArrayOf(1024))) {
            val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            val muxer = MediaMuxer(File(dir, "$name.m4a").absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false
            try {
                val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                var position = 0
                var packet = 0
                var inputEnded = false
                var outputEnded = false
                var track = -1
                val info = MediaCodec.BufferInfo()
                val deadline = SystemClock.elapsedRealtime() + 30000
                while (!outputEnded) {
                    check(SystemClock.elapsedRealtime() < deadline) { "AAC encode timed out" }
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                            val count = minOf(pattern[packet++ % pattern.size], samples.size - position, buffer.remaining() / 2)
                            repeat(count) { buffer.putShort(samples[position + it]) }
                            // Experimental codec/muxer characterization only: the measured
                            // delay of this fixture is never applied to production encoders.
                            val pts = (position - if (name == "negative-pts") 2048 else 0) * 1_000_000L / 48000
                            inputEnded = count == 0
                            File(dir, "$name-input.txt").appendText("pts=$pts samples=$count\n")
                            codec.queueInputBuffer(index, 0, count * 2, pts,
                                if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            position += count
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        File(dir, "$name-format.txt").writeText("codec=${codec.name} format=${codec.outputFormat}")
                        val outputFormat = codec.outputFormat
                        if (name == "format-delay") {
                            outputFormat.setInteger(MediaFormat.KEY_ENCODER_DELAY, 2048)
                            outputFormat.setInteger(MediaFormat.KEY_ENCODER_PADDING, 512)
                        }
                        track = muxer.addTrack(outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (index >= 0) {
                        File(dir, "$name-output.txt").appendText("pts=${info.presentationTimeUs} bytes=${info.size} flags=${info.flags}\n")
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
                assertEquals(192000, position)
            } finally {
                runCatching { codec.stop() }
                codec.release()
                if (muxerStarted) muxer.stop()
                muxer.release()
            }
        }
        android.util.Log.i("AacPacketizationTest", "Codec packetization evidence: $dir")
    }
}
