package com.videoeditor.core.media.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.Codec
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import java.nio.ByteBuffer

/** Qualified AAC-LC source decoding. Every method runs on its owning renderer thread. */
@androidx.media3.common.util.UnstableApi
class SourceClockAacDecoder private constructor(
    private val configuration: Format,
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    private val clock: AacSourceClock,
    private val endUs: Long,
    private val rate: Int,
    private val channels: Int,
    private val preroll: java.util.ArrayDeque<Pair<Long, ByteArray>>,
    private val nativeFormat:MediaFormat,
    private val sourceHead:Pair<Long,ByteArray>,
    private val sourceScratch:ByteBuffer,
) : Codec {
    private var discardedInput: ByteBuffer? = null
    var queuedPacketCount = 0
        private set
    private var upstreamEnded = false
    private var inputEnded = false
    private var ended = false
    private var released = false
    private var outputIndex = -1
    private var output: ByteBuffer? = null
    private val info = MediaCodec.BufferInfo()
    private val exposedInfo = MediaCodec.BufferInfo()
    private var format: Format? = null
    private val generationId = SystemClock.elapsedRealtimeNanos()
    private var previousInputSample: Long? = null
    private var lastProgressMs = SystemClock.elapsedRealtime()

    override fun getConfigurationFormat() = configuration
    override fun getName(): String = codec.name
    override fun getInputSurface(): Surface = error("Audio decoder has no surface")
    // The upstream compressed stream remains responsible for the asset-loader clock only.
    // It cannot supply native-extractor packets: its edit-list clock is different.
    override fun maybeDequeueInputBuffer(buffer: DecoderInputBuffer): Boolean {
        val data = discardedInput ?: ByteBuffer.allocateDirect(65536).also { discardedInput = it }
        data.clear()
        buffer.data = data
        return !released && !ended && !upstreamEnded
    }
    override fun queueInputBuffer(buffer: DecoderInputBuffer) { upstreamEnded = buffer.isEndOfStream; buffer.clear() }
    override fun signalEndOfInputStream() = Unit
    override fun getOutputFormat(): Format? { pump(); return format }
    override fun getOutputBuffer(): ByteBuffer? { pump(); return output }
    override fun getOutputBufferInfo(): MediaCodec.BufferInfo? = if (output == null) null else exposedInfo
    override fun isEnded() = ended

    private inline fun guarded(action: () -> Unit) {
        try { action() } catch (e: Exception) {
            throw ExportException.createForAssetLoader(e, ExportException.ERROR_CODE_DECODING_FAILED)
        }
    }

    private fun pump() = guarded {
        check(!released) { "Released AAC generation" }
        if (output != null || ended) return@guarded
        // Bounded nonblocking work; backpressure leaves the codec-owned view intact.
        repeat(4) {
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(0)
                if (index >= 0) {
                    val packet = preroll.peekFirst()
                    val pts = packet?.first ?: extractor.sampleTime
                    // AAC-LC packet span is qualified as 1024 samples below. Two complete
                    // packets past the audible end cover overlap/delayed output, then EOS.
                    inputEnded = pts < 0 && extractor.sampleTrackIndex < 0 ||
                        pts >= endUs + (3072L * 1_000_000 + rate - 1) / rate
                    val bytes = if (inputEnded) -1 else if (packet != null) {
                        codec.getInputBuffer(index)!!.put(packet.second); packet.second.size
                    } else extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (bytes < 0) inputEnded = true
                    if (!inputEnded) {
                        val sample = (pts * rate + if (pts >= 0) 500000 else -500000) / 1000000
                        previousInputSample?.let { require(sample-it == 1024L) { "Discontinuous AAC packet clock" } }
                        previousInputSample = sample
                        queuedPacketCount++
                        packetObserver?.let { observer ->
                            val payload=codec.getInputBuffer(index)!!.duplicate()
                            payload.position(0);payload.limit(bytes)
                            val digest=java.security.MessageDigest.getInstance("SHA-256")
                            digest.update(payload)
                            observer.accept(PacketRecord(generationId,clock.provenance.sourceIdentity,
                                clock.provenance.trackIndex,pts,sample,bytes,
                                digest.digest().joinToString("") { "%02x".format(it) }))
                        }
                    }
                    codec.queueInputBuffer(index, 0, maxOf(0, bytes), if (inputEnded) endUs else pts,
                        if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (!inputEnded) { if (packet != null) preroll.removeFirst() else extractor.advance() }
                    lastProgressMs = SystemClock.elapsedRealtime()
                }
            }
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val actual = codec.outputFormat
                check(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == rate &&
                    actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == channels)
                check(!actual.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                    actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == C.ENCODING_PCM_16BIT)
                format = Format.Builder().setSampleMimeType("audio/raw").setSampleRate(rate)
                    .setChannelCount(channels).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
            } else if (index >= 0) {
                lastProgressMs = SystemClock.elapsedRealtime()
                val view = codec.getOutputBuffer(index)!!.duplicate()
                view.position(info.offset); view.limit(info.offset + info.size)
                val slice = clock.trim(view)
                if (slice.pcm.hasRemaining()) {
                    outputIndex = index
                    output = slice.pcm
                    exposedInfo.set(0, slice.pcm.remaining(),
                        slice.clipSampleOffset * 1_000_000 / rate, 0)
                    return@guarded
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    clock.finish(); ended = true; return@guarded
                }
            }
        }
        check(SystemClock.elapsedRealtime() - lastProgressMs < 15000) { "AAC decoder made no progress" }
    }

    override fun releaseOutputBuffer(render: Boolean) {
        check(!render && outputIndex >= 0)
        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        codec.releaseOutputBuffer(outputIndex, false)
        outputIndex = -1; output = null
        lastProgressMs = SystemClock.elapsedRealtime()
        if (eos) { clock.finish(); ended = true }
    }
    override fun releaseOutputBuffer(renderPresentationTimeUs: Long) = error("Audio decoder")
    override fun release() {
        if (released) return
        released = true; clock.close()
        try { codec.stop() } finally { try { codec.release() } finally { extractor.release() } }
    }

    /** Transfer the configured codec only after flushing all old-generation buffers. */
    fun detachReusableCodec(): ReusableCodec? {
        if (released || format == null) { release(); return null }
        released = true
        clock.close()
        try {
            codec.flush()
            return ReusableCodec(ReusableState(codec,extractor,nativeFormat,clock.provenance,sourceHead,sourceScratch),
                configuration.initializationData.map { it.copyOf() })
        } catch (e: Exception) {
            try { codec.release() } finally { extractor.release() }
            throw e
        }
    }

    internal data class ReusableState(val codec:MediaCodec,val extractor:MediaExtractor,
        val nativeFormat:MediaFormat,val provenance:AacSourceProvenance,
        val head:Pair<Long,ByteArray>,val scratch:ByteBuffer)

    class ReusableCodec internal constructor(private var state:ReusableState?,private val config:List<ByteArray>) {
        internal fun take(identity:String,configuration:Format):ReusableState? {
            if(state?.provenance?.sourceIdentity!=identity || config.size!=configuration.initializationData.size ||
                config.indices.any { !config[it].contentEquals(configuration.initializationData[it]) }) return null
            return state.also { state=null }
        }
        fun release() {
            state?.let { active ->
                state=null
                try { active.codec.stop() } finally {
                    try { active.codec.release() } finally { active.extractor.release() }
                }
            }
        }
    }

    companion object {
        /** Enabled only for the source/codec configurations validated below. */
        @JvmField val enabledForSupportedSources = true
        @Volatile @JvmField var legacyForCharacterization = false
        @Volatile @JvmField var enabledForQualification = false
        @JvmStatic fun isEnabled() = !legacyForCharacterization && (enabledForSupportedSources || enabledForQualification)
        data class PacketRecord(val generation:Long,val source:String,val track:Int,val ptsUs:Long,
            val editedSample:Long,val bytes:Int,val sha256:String)
        @Volatile @JvmField var packetObserver: java.util.function.Consumer<PacketRecord>? = null
        data class Preparation(val startUs:Long,val endUs:Long,val nanos:Long,val scannedPackets:Int,
            val compressedCapacity:Int,val retainedBytes:Int,val codecName:String)
        @Volatile @JvmField var preparationObserver: java.util.function.Consumer<Preparation>? = null
        @Volatile @JvmField var previewPcmObserver: java.util.function.BiConsumer<Long, ByteBuffer>? = null

        @JvmStatic @JvmOverloads fun create(context: Context, item: EditedMediaItem, configuration: Format,
            reusable:ReusableCodec? = null): Codec? {
            if (!isEnabled() || configuration.sampleMimeType != "audio/mp4a-latm" ||
                item.flattenForSlowMotion || item.effects.audioProcessors.any { it is androidx.media3.common.audio.SonicAudioProcessor }) {
                reusable?.release(); return null
            }
            val prepareStarted = SystemClock.elapsedRealtimeNanos()
            val local = item.mediaItem.localConfiguration ?: run { reusable?.release(); return null }
            val retained = reusable?.take(local.uri.toString(),configuration)
            val extractor = retained?.extractor ?: MediaExtractor()
            var codec: MediaCodec? = retained?.codec
            var transferred = false
            try {
                if (retained == null) extractor.setDataSource(context, local.uri, null)
                val tracks = retained?.let { listOf(it.provenance.trackIndex) } ?: (0 until extractor.trackCount).filter {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
                }
                if (tracks.size != 1) return null
                val track = tracks.single()
                val original = retained?.nativeFormat ?: extractor.getTrackFormat(track)
                val rate = original.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = original.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val csd = original.getByteBuffer("csd-0")!!.duplicate()
                val profile = (csd.get(0).toInt() and 255) shr 3
                val frequencyIndex = ((csd.get(0).toInt() and 7) shl 1) or ((csd.get(1).toInt() and 128) shr 7)
                val channelConfig = (csd.get(1).toInt() shr 3) and 15
                if (profile != 2 || frequencyIndex == 15 || csd.get(1).toInt() and 4 != 0 ||
                    channelConfig != 1 || rate !in listOf(44100, 48000) || channels != 1) {
                    return null
                }
                val delay = if (retained != null) retained.provenance.originalEncoderDelay else
                    if (original.containsKey("encoder-delay")) original.getInteger("encoder-delay") else null
                val padding = if (retained != null) retained.provenance.originalEncoderPadding else
                    if (original.containsKey("encoder-padding")) original.getInteger("encoder-padding") else null
                if (!original.containsKey(MediaFormat.KEY_DURATION)) return null
                val sourceDurationUs = original.getLong(MediaFormat.KEY_DURATION)
                val start = item.mediaItem.clippingConfiguration.startPositionUs
                val requestedEnd = item.mediaItem.clippingConfiguration.endPositionUs
                val end = if (requestedEnd == C.TIME_END_OF_SOURCE) sourceDurationUs else requestedEnd
                // A video can legitimately outlast its audio track. Preserve the
                // existing natural-EOS policy outside the qualified audible span.
                if (start >= sourceDurationUs || end > sourceDurationUs) return null
                if (retained == null) extractor.selectTrack(track)
                val initialPts = retained?.head?.first ?: extractor.sampleTime
                val initialSample = (initialPts * rate + if (initialPts >= 0) 500000 else -500000) / 1000000
                // This qualification covers the native edited MP4 clock established
                // by the original delay metadata and initial packet identity. Other
                // containers/edit contracts stay on the existing path.
                if (delay == null || initialSample + delay != 0L ||
                    (retained == null && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0)) return null
                val maximum = if (original.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    original.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 65536
                require(maximum in 1..1048576)
                val compressed = retained?.scratch ?: ByteBuffer.allocate(maximum.coerceAtLeast(4096))
                val head = retained?.head ?: run {
                    compressed.clear()
                    val size = extractor.readSampleData(compressed,0)
                    require(size in 1..compressed.capacity())
                    val bytes=ByteArray(size);compressed.position(0);compressed.get(bytes)
                    initialPts to bytes
                }
                // The retained first packet restores negative edited preroll when a
                // native seek to zero skips it. No rounded second seek is required.
                val seek = start - 6144L * 1_000_000 / rate
                if (retained != null || seek > 0)
                    extractor.seekTo(maxOf(0,seek),MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val preceding = java.util.ArrayDeque<Pair<Long, ByteArray>>()
                if (retained != null && seek <= 0 && extractor.sampleTime > head.first) preceding.addLast(head)
                var scannedPackets = 0
                var previous = Long.MIN_VALUE
                while (extractor.sampleTrackIndex >= 0 && extractor.sampleTime < start) {
                    require(++scannedPackets <= 8) { "Source seek exceeds bounded AAC packet qualification" }
                    val pts = extractor.sampleTime
                    if (previous != Long.MIN_VALUE) require(kotlin.math.abs((pts - previous) * rate - 1024_000_000L) <= rate * 2L)
                    compressed.clear()
                    val size = extractor.readSampleData(compressed, 0)
                    require(size in 1..compressed.capacity())
                    val payload = ByteArray(size); compressed.position(0); compressed.get(payload)
                    preceding.addLast(pts to payload); if (preceding.size > 2) preceding.removeFirst()
                    previous = pts; extractor.advance()
                }
                val first = if (preceding.isEmpty()) extractor.sampleTime else preceding.first.first
                val firstSample = (first * rate + if (first >= 0) 500000 else -500000) / 1_000_000
                // URI + original native track index is the immutable source identity for
                // this generation. No full-file hash scan is needed for bounded seeking.
                // The content digest remains explicitly unknown rather than hashing the URI
                // and presenting that value as a source-content hash.
                val provenance = AacSourceProvenance(null, track, rate, channels, delay, padding, 0,
                    sourceIdentity = local.uri.toString())
                val clock = AacSourceClock.forDecoderGeneration(provenance, start, end, firstSample, 0)
                // Native extractor timestamps are already in the edited domain. Configure
                // zero trimming and let the source sample clock select audible samples.
                original.setInteger("encoder-delay", 0); original.setInteger("encoder-padding", 0)
                // At the original source head there are no earlier packets to erase
                // decoder overlap history. The qualified codec's flush retains that
                // history, so recreate it for an origin replay while keeping the
                // bounded extractor and original packet provenance.
                if (codec != null && first == head.first) {
                    runCatching { codec.stop() }
                    codec.release()
                    codec = null
                }
                val reused = codec != null
                if (codec == null) codec = MediaCodec.createDecoderByType("audio/mp4a-latm")
                if (codec.name != "c2.android.aac.decoder") return null
                if (!reused) { codec.configure(original, null, null, 0); codec.start() }
                return SourceClockAacDecoder(configuration, extractor, codec, clock, end, rate, channels, preceding, original, head, compressed).also {
                    transferred = true
                    if (reused) it.format = Format.Builder().setSampleMimeType("audio/raw")
                        .setSampleRate(rate).setChannelCount(channels).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
                    val preparationNanos = SystemClock.elapsedRealtimeNanos() - prepareStarted
                    if (android.os.Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled()) {
                        val prefix = "sourceClock.${System.identityHashCode(it)}"
                        com.videoeditor.core.media.preview.PreviewPhaseTrace.counter("$prefix.prepareDurationNs", preparationNanos)
                        com.videoeditor.core.media.preview.PreviewPhaseTrace.counter("$prefix.startUs", start)
                        com.videoeditor.core.media.preview.PreviewPhaseTrace.counter("$prefix.endUs", end)
                        com.videoeditor.core.media.preview.PreviewPhaseTrace.counter("$prefix.scannedPackets", scannedPackets.toLong())
                        com.videoeditor.core.media.preview.PreviewPhaseTrace.counter("$prefix.reusedCodec", if (reused) 1L else 0L)
                    }
                    preparationObserver?.accept(Preparation(start,end,preparationNanos,
                        scannedPackets,compressed.capacity(),preceding.sumOf { packet -> packet.second.size },codec.name))
                }
            } catch (e: Exception) {
                throw ExportException.createForAssetLoader(e, ExportException.ERROR_CODE_DECODING_FAILED)
            } finally {
                if (!transferred) {
                    try { codec?.let { runCatching { it.stop() }; it.release() } } finally { extractor.release() }
                }
                reusable?.release()
            }
        }
    }
}
