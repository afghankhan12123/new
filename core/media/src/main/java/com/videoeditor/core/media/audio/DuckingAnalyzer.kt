package com.videoeditor.core.media.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.videoeditor.core.common.result.Result
import com.videoeditor.core.media.MediaError
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.AudioLevelWindow
import com.videoeditor.core.model.DuckingSettings
import com.videoeditor.core.model.Project
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decodes ducking trigger clips to PCM and measures their per-window levels.
 *
 * This is the only Stage 8 component that performs I/O for ducking: it runs off
 * the playback thread, bounded per clip, and its result is cached by
 * [DuckingAnalysisCache]. The pure arithmetic that turns measured levels into a
 * gain curve lives in [DuckingEnvelopeDetector] and is host-tested without an
 * emulator.
 *
 * The decode pattern mirrors [com.videoeditor.core.media.waveform.WaveformExtractor]:
 * `MediaExtractor` selects the first audio track, `MediaCodec` decodes to PCM16,
 * and a hard frame bound guarantees a malformed source cannot hang the worker.
 * Unlike the waveform extractor, this measures RMS energy per fixed window
 * rather than peaks per bucket, because the ducking contract is defined on
 * windowed RMS levels.
 */
@Singleton
class DuckingAnalyzer @Inject constructor() {

    /**
     * Measures one trigger clip's levels on the timeline window grid.
     *
     * The clip's audible source window is decoded, windowed in source time and
     * labelled in timeline time, accounting for the clip's playback speed so a
     * sped-up or slowed trigger lands on the grid slot it actually occupies.
     *
     * A muted clip is never decoded: the documented contract is that a muted
     * trigger contributes silence and cannot request ducking, so measuring it
     * would waste a decode and could only produce the same all-silent curve.
     */
    fun measureTrigger(
        context: Context,
        project: Project,
        trigger: AudioClip,
        gridWindowMicros: Long,
    ): Result<List<AudioLevelWindow>, MediaError> {
        if (trigger.muted || trigger.sourceDurationMicros <= 0L) {
            return Result.Success(emptyList())
        }
        val mediaRef = project.mediaRef(trigger.mediaRefId)
            ?: return Result.Failure(MediaError.MediaNotFound("Ducking trigger media is missing"))

        val speed = if (trigger.playbackSpeed > 0f) trigger.playbackSpeed else 1f
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, Uri.parse(mediaRef.uri), null)
            val trackIndex = selectAudioTrack(extractor)
                ?: return Result.Failure(MediaError.UnsupportedFormat("Ducking trigger has no audio track"))
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                0
            }
            if (sampleRate <= 0) {
                return Result.Failure(MediaError.UnsupportedFormat("Ducking trigger has no usable sample rate"))
            }
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                1
            }

            extractor.seekTo(trigger.sourceStartMicros, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val accumulator = DuckingEnvelopeDetector.WindowAccumulator(
                gridStartMicros = trigger.timelineStartMicros,
                sampleRateHz = sampleRate,
                channelCount = channelCount,
                sourceWindowMicros = (gridWindowMicros * speed).toLong().coerceAtLeast(1L),
                gridWindowMicros = gridWindowMicros,
            )

            Result.Success(
                decodeWindows(
                    codec = codec,
                    extractor = extractor,
                    accumulator = accumulator,
                    sourceStartMicros = trigger.sourceStartMicros,
                    sourceEndMicros = trigger.sourceEndMicros,
                    channels = channelCount,
                ),
            )
        } catch (throwable: Throwable) {
            Result.Failure(
                MediaError.UnsupportedFormat(
                    throwable.message ?: "Could not analyse ducking trigger",
                ),
            )
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { extractor.release() }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return index
        }
        return null
    }

    private fun decodeWindows(
        codec: MediaCodec,
        extractor: MediaExtractor,
        accumulator: DuckingEnvelopeDetector.WindowAccumulator,
        sourceStartMicros: Long,
        sourceEndMicros: Long,
        channels: Int,
    ): List<AudioLevelWindow> {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sawFrame = false
        // Hard bound so a malformed source cannot loop forever. 40k frames at
        // ~1024 samples each covers well over 14 minutes of 48 kHz audio.
        var frames = 0
        // Reused across the whole decode: one bounded buffer, no per-frame
        // allocation, which keeps the analysis cheap on the playback budget.
        val samples = ShortArray(MAX_SAMPLES_PER_BUFFER)

        while (!outputDone && frames < MAX_FRAMES) {
            frames++
            if (!inputDone) {
                when (val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        val inputBuffer = codec.getInputBuffer(index)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            when (val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) outputDone = true
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (bufferInfo.size > 0 &&
                        bufferInfo.presentationTimeUs >= sourceStartMicros &&
                        bufferInfo.presentationTimeUs < sourceEndMicros
                    ) {
                        val outputBuffer = codec.getOutputBuffer(index)!!
                        outputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val shortBuffer = outputBuffer.asShortBuffer()
                        val available = minOf(shortBuffer.remaining(), samples.size)
                        shortBuffer.get(samples, 0, available)
                        // Count whole frames only; a truncated trailing frame at
                        // a buffer edge must not be treated as channel samples.
                        val wholeFrames = available / channels
                        accumulator.add(samples, wholeFrames * channels)
                        sawFrame = true
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }

        if (!sawFrame) return emptyList()
        return accumulator.finish()
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MAX_FRAMES = 40_000
        const val MAX_SAMPLES_PER_BUFFER = 16_384
    }
}
