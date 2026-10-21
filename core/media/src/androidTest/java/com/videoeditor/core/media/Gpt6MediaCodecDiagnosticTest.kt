package com.videoeditor.core.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Temporary diagnostic that decodes the exported transition video on the device with a
 * plain, frame-accurate [MediaCodec] pipeline.
 *
 * The purpose is to separate two possible causes of the NE2211 retriever anomaly:
 *  a) the device's own H.264 decoder cannot decode the 2.0 s keyframe, or
 *  b) the device's decoder is fine and only [android.media.MediaMetadataRetriever]'s
 *     seek / frame-selection logic returns the wrong frame.
 *
 * The decoded luma plane mean is used as the luma proxy, which is sufficient to tell a
 * mid-transition ramp apart from a pure white frame.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Gpt6MediaCodecDiagnosticTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun meanLuma(image: android.media.Image): Double {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        var sum = 0L
        var count = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    sum += (buffer.get(index).toInt() and 0xFF)
                    count++
                }
                x += 20
            }
            y += 20
        }
        return if (count == 0) -1.0 else sum.toDouble() / count
    }

    @Test
    fun diagnostic_codec_decode_around_transition() {
        val candidates = listOf(
            File(context.getExternalFilesDir(null), "crossover/crossover_ne2211.mp4"),
            File(context.getExternalFilesDir(null), "crossover/crossover_emu.mp4")
        )

        val report = StringBuilder()
        report.append("device=").append(android.os.Build.MODEL)
            .append(" sdk=").append(android.os.Build.VERSION.SDK_INT).append('\n')

        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val avcDecoders = codecList.codecInfos
            .filter { it.isEncoder.not() && it.supportedTypes.any { type -> type.startsWith("video/avc") } }
            .map { it.name }
        report.append("avcDecoders=").append(avcDecoders.joinToString(",")).append('\n')

        for (file in candidates) {
            report.append("== ").append(file.name)
                .append(" exists=").append(file.exists())
                .append(" size=").append(file.length()).append('\n')
            if (!file.exists()) continue

            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidateFormat = extractor.getTrackFormat(i)
                if (candidateFormat.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    trackIndex = i
                    format = candidateFormat
                    break
                }
            }
            if (format == null) {
                report.append("   no video track\n")
                extractor.release()
                continue
            }
            extractor.selectTrack(trackIndex)
            report.append("   mime=").append(format.getString(MediaFormat.KEY_MIME))
                .append(" size=").append(format.getInteger(MediaFormat.KEY_WIDTH))
                .append('x').append(format.getInteger(MediaFormat.KEY_HEIGHT)).append('\n')

            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            // Start from the sync frame at or before 1.0 s so that the whole transition
            // (1.5 s .. 2.5 s) and the 2.0 s keyframe are decoded.
            extractor.seekTo(1_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            report.append("   extractorAfterSeek pts=").append(extractor.sampleTime).append('\n')

            var inputEnded = false
            var outputEnded = false
            var frameIndex = 0
            val outputs = StringBuilder()

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val info = android.media.MediaCodec.BufferInfo()
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val image = codec.getOutputImage(outputIndex)
                            val luma = if (image != null) meanLuma(image) else -1.0
                            outputs.append("   out pts=").append(info.presentationTimeUs)
                                .append(" lumaY=").append(luma)
                                .append(" size=").append(info.size)
                                .append('\n')
                            frameIndex++
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEnded = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        report.append("   outputFormat=").append(newFormat).append('\n')
                    }
                }
            }
            report.append("   decodedFrames=").append(frameIndex).append('\n')
            report.append(outputs)
            codec.stop()
            codec.release()
            extractor.release()
        }

        val text = report.toString()
        android.util.Log.i("Gpt6Codec", text)
        try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "gpt6_codec_${android.os.Build.MODEL.replace(' ', '_')}.txt")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (error: Exception) {
            android.util.Log.e("Gpt6Codec", "evidence write failed: ${error.message}")
        }
        assertTrue("report must be produced", text.isNotEmpty())
    }
}
