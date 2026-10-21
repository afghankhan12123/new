package com.videoeditor.core.media.waveform

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.videoeditor.core.common.result.Result
import com.videoeditor.core.media.MediaError
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

/**
 * Peak amplitude samples for a time range of an audio source — the data the
 * timeline's waveform track draws. Peaks are normalized to 0..1 and evenly
 * spaced over the requested source range, so the renderer can aggregate them
 * into on-screen columns at any zoom without re-decoding.
 */
class WaveformPeaks(val peaks: FloatArray) {
    val size: Int get() = peaks.size

    /**
     * The maximum peak over the (clamped) bucket range [from] until [to],
     * used to aggregate cached peaks into one drawn column. Returns 0 for an
     * empty range.
     */
    fun peakInRange(from: Int, to: Int): Float {
        val start = from.coerceIn(0, size)
        val end = to.coerceIn(start, size)
        var peak = 0f
        for (i in start until end) peak = max(peak, peaks[i])
        return peak
    }

    companion object {
        val EMPTY = WaveformPeaks(FloatArray(0))
    }
}

/**
 * Extracts real waveform data by decoding the source audio to PCM with
 * [MediaExtractor]/[MediaCodec] and reducing it to per-bucket peaks. This is
 * the same decode-to-PCM path Ringdroid-style editors use; the output reflects
 * the actual signal, never a synthesized pattern.
 *
 * Blocking and CPU-bound — callers must run it on a background dispatcher.
 */
@Singleton
class WaveformExtractor @Inject constructor() {

    /**
     * Decodes [uri] between [sourceStartMicros] and [sourceEndMicros] and
     * reduces it to [bucketCount] peaks. Audio codecs decode from the
     * container's native sample times, so the result is exact to within one
     * codec frame at each edge.
     */
    fun extract(
        context: Context,
        uri: Uri,
        sourceStartMicros: Long,
        sourceEndMicros: Long,
        bucketCount: Int,
    ): Result<WaveformPeaks, MediaError> {
        require(bucketCount > 0) { "bucketCount must be positive" }
        val spanMicros = sourceEndMicros - sourceStartMicros
        if (spanMicros <= 0L) {
            return Result.Success(WaveformPeaks.EMPTY)
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor)
                ?: return Result.Failure(
                    MediaError.WaveformFailed("Source has no audio track"),
                )
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                0
            }
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                1
            }

            extractor.seekTo(sourceStartMicros, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            Result.Success(
                decodePeaks(
                    codec, extractor, sourceStartMicros, spanMicros, bucketCount,
                    sampleRate, channelCount,
                ),
            )
        } catch (t: Throwable) {
            Result.Failure(MediaError.WaveformFailed(t.message ?: "Waveform decode failed"))
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { extractor.release() }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME)
                .orEmpty()
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun decodePeaks(
        codec: MediaCodec,
        extractor: MediaExtractor,
        startMicros: Long,
        spanMicros: Long,
        bucketCount: Int,
        sampleRate: Int,
        channelCount: Int,
    ): WaveformPeaks {
        val peaks = FloatArray(bucketCount)
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sawAudioFrame = false
        // Bound the loop so a malformed source can never hang the worker: at
        // most (stream length + slack) of decoded time and a hard frame cap.
        val maxFrames = 40_000
        var frames = 0

        while (!outputDone && frames < maxFrames) {
            frames++

            if (!inputDone) {
                when (val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        val inputBuffer = codec.getInputBuffer(index)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                index, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                index, 0, sampleSize,
                                extractor.sampleTime, 0,
                            )
                            extractor.advance()
                        }
                    }
                }
            }

            when (val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) {
                        // No more output coming.
                        outputDone = true
                    }
                }

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit

                else -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (bufferInfo.size > 0 &&
                        bufferInfo.presentationTimeUs >= startMicros
                    ) {
                        val offsetMicros = bufferInfo.presentationTimeUs - startMicros
                        if (offsetMicros > spanMicros) {
                            outputDone = true
                        } else {
                            val outputBuffer = codec.getOutputBuffer(index)!!
                            val peak = framePeak(outputBuffer.asShortBuffer())
                            // A decoded frame covers a time *interval* (e.g.
                            // ~23ms for a 1024-sample AAC frame), which can
                            // span many buckets at fine resolutions. Fill the
                            // whole interval so the waveform is dense instead
                            // of one sample per frame with gaps between.
                            val frameDurationMicros = if (sampleRate > 0) {
                                val samples = bufferInfo.size / (2 * channelCount)
                                samples * 1_000_000L / sampleRate
                            } else {
                                0L
                            }
                            val firstBucket =
                                ((offsetMicros * bucketCount) / spanMicros).toInt()
                            val lastBucket =
                                (((offsetMicros + frameDurationMicros) * bucketCount) /
                                    spanMicros).toInt()
                            for (bucket in firstBucket.coerceIn(0, bucketCount - 1)..
                                lastBucket.coerceIn(0, bucketCount - 1)
                            ) {
                                peaks[bucket] = max(peaks[bucket], peak)
                            }
                            sawAudioFrame = true
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }

        if (!sawAudioFrame) return WaveformPeaks.EMPTY

        // A seek lands on the sync frame before the requested start, so the
        // first buckets can be empty even for loud audio; carry the first
        // non-zero peak backwards so the waveform starts at the clip edge.
        var firstNonZero = -1
        for (i in peaks.indices) {
            if (peaks[i] > 0f) {
                firstNonZero = i
                break
            }
        }
        if (firstNonZero > 0) {
            for (i in 0 until firstNonZero) peaks[i] = peaks[firstNonZero]
        }
        return WaveformPeaks(peaks)
    }

    /** Max |amplitude| of a 16-bit PCM frame, normalized to 0..1. */
    private fun framePeak(buffer: java.nio.ShortBuffer): Float {
        var peak = 0
        while (buffer.hasRemaining()) {
            peak = max(peak, abs(buffer.get().toInt()))
        }
        return peak / 32768f
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
