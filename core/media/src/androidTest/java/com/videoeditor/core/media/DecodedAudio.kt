package com.videoeditor.core.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/** Decode the committed AAC output, retaining its presentation clock. */
internal fun visitAudioSamples(file: File, consume: (Long, Int, Double) -> Unit) {
    val extractor = MediaExtractor()
    extractor.setDataSource(file.absolutePath)
    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
    extractor.selectTrack(track)
    val format = extractor.getTrackFormat(track)
    val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
    var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    try {
        decoder.configure(format, null, null, 0); decoder.start()
        var inputEnded = false; var outputEnded = false
        val info = MediaCodec.BufferInfo()
        val deadline = android.os.SystemClock.elapsedRealtime() + 30000
        while (!outputEnded) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Audio decode timed out" }
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(10000)
                if (index >= 0) {
                    val buffer = decoder.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                    else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 10000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (index >= 0) {
                val buffer = decoder.getOutputBuffer(index)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                var frame = 0L
                while (buffer.remaining() >= channels * 2) {
                    val time = info.presentationTimeUs + frame * 1_000_000L / rate
                    repeat(channels) { channel -> consume(time, channel, buffer.short.toDouble()) }
                    frame++
                }
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                decoder.releaseOutputBuffer(index, false)
            }
        }
    } finally { runCatching { decoder.stop() }; decoder.release(); extractor.release() }
}

internal fun audioRms(file: File, windows: List<LongRange>): List<Double> {
    val squares = DoubleArray(windows.size)
    val counts = LongArray(windows.size)
    visitAudioSamples(file) { time, _, sample ->
        windows.forEachIndexed { index, range -> if (time in range) { squares[index] += sample * sample; counts[index]++ } }
    }
    check(counts.all { it > 0 }) { "Missing decoded samples: ${counts.toList()}" }
    return squares.indices.map { kotlin.math.sqrt(squares[it] / counts[it]) }
}

/** Hysteresis crossing measurement for the deterministic single-tone fixture. */
internal fun audioToneFrequency(file: File, window: LongRange): Double {
    var sign = 0; var crossings = 0
    var first = -1L; var last = -1L
    var previousCrossing = -1L
    val periods = mutableListOf<Long>()
    visitAudioSamples(file) { time, channel, sample ->
        if (channel == 0 && time in window) {
            if (first < 0) first = time
            last = time
            val next = if (sample > 100) 1 else if (sample < -100) -1 else sign
            if (next == 1 && sign == -1) {
                crossings++
                if (previousCrossing >= 0) periods += time - previousCrossing
                previousCrossing = time
            }
            sign = next
        }
    }
    check(last > first) { "Missing tone window" }
    // Tempo processing can insert a phase discontinuity at an overlap boundary.
    // Estimate the audible carrier from complete cycles rather than counting
    // those boundaries as pitch changes. Require substantial tone coverage.
    check(periods.size > 50) { "Too few audible cycles: $crossings" }
    val sorted = periods.sorted()
    val median = sorted[sorted.size / 2]
    check(sorted.count { kotlin.math.abs(it - median) < median * .1 } > sorted.size * .8) {
        "Unstable pitch: median=$median periods=$sorted"
    }
    return 1_000_000.0 / median
}
