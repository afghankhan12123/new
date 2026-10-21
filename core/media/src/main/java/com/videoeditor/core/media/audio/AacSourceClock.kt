package com.videoeditor.core.media.audio

import java.nio.ByteBuffer

/** Immutable source timing, retained independently from clipped Media3 formats. */
internal data class AacSourceProvenance(
    /** Content hash when already established; runtime URI identity does not invent one. */
    val sourceSha256: String?,
    val trackIndex: Int,
    val sampleRate: Int,
    val channels: Int,
    val originalEncoderDelay: Int?,
    val originalEncoderPadding: Int?,
    /** Add to unedited packet sample positions to obtain extractor-edited positions. */
    val uneditedToEditedSamples: Long,
    val sourceIdentity: String = sourceSha256.orEmpty(),
) {
    init {
        require(sourceSha256 == null || sourceSha256.matches(Regex("[0-9a-f]{64}")))
        require(sourceIdentity.isNotBlank())
        require(trackIndex >= 0 && sampleRate > 0 && channels > 0)
        require(originalEncoderDelay == null || originalEncoderDelay >= 0)
        require(originalEncoderPadding == null || originalEncoderPadding >= 0)
    }

    fun editedPacketSample(uneditedSample: Long): Long =
        Math.addExact(uneditedSample, uneditedToEditedSamples)

    /** First sample whose start lies at or after a nonnegative model timestamp. */
    fun boundarySample(timeUs: Long): Long {
        require(timeUs >= 0)
        val whole = Math.multiplyExact(timeUs / 1_000_000, sampleRate.toLong())
        val remainder = (timeUs % 1_000_000) * sampleRate
        return Math.addExact(whole, (remainder + 999_999) / 1_000_000)
    }
}

/** One decoder generation only. Caller must close on seek, retry, cancellation or release. */
internal class AacSourceClock private constructor(
    val provenance: AacSourceProvenance,
    startUs: Long,
    endUs: Long,
    firstEditedPacketSample: Long,
    configuredDecoderDelay: Int,
) : AutoCloseable {
    val startSample = provenance.boundarySample(startUs)
    val endSample = provenance.boundarySample(endUs)
    private var nextSample = Math.addExact(firstEditedPacketSample, configuredDecoderDelay.toLong())
    private var emitted = 0L
    private var closed = false

    init {
        require(endUs > startUs && configuredDecoderDelay >= 0)
        require(nextSample <= startSample) { "Decoder starts after audible boundary" }
    }

    data class Slice(val pcm: ByteBuffer, val firstSourceSample: Long, val clipSampleOffset: Long)

    /** PCM16 interleaved input; returns a view valid only while the codec buffer is owned. */
    fun trim(decoded: ByteBuffer): Slice {
        check(!closed) { "Stale decoder generation" }
        val frameBytes = Math.multiplyExact(provenance.channels, 2)
        require(decoded.remaining() % frameBytes == 0) { "Partial PCM frame" }
        val frames = decoded.remaining() / frameBytes
        val after = Math.addExact(nextSample, frames.toLong())
        val from = maxOf(nextSample, startSample).coerceAtMost(after)
        val until = minOf(after, endSample).coerceAtLeast(from)
        val result = decoded.duplicate()
        result.position(decoded.position() + ((from - nextSample) * frameBytes).toInt())
        result.limit(result.position() + ((until - from) * frameBytes).toInt())
        val offset = emitted
        emitted = Math.addExact(emitted, until - from)
        nextSample = after
        return Slice(result.slice().asReadOnlyBuffer(), from, offset)
    }

    /** Missing tail is an error, never synthesized silence. */
    fun finish() {
        check(!closed)
        check(emitted == endSample - startSample) { "Incomplete audible PCM: $emitted/${endSample - startSample}" }
        close()
    }

    override fun close() { closed = true }

    companion object {
        /** Caller has qualified source clocks, decoder preroll and bounded end decoding. */
        fun forDecoderGeneration(provenance: AacSourceProvenance, startUs: Long, endUs: Long,
            firstEditedPacketSample: Long, configuredDecoderDelay: Int): AacSourceClock =
            AacSourceClock(provenance, startUs, endUs, firstEditedPacketSample, configuredDecoderDelay)

        /** Explicit test-only entry; packet selection and decoder qualification remain caller gates. */
        fun experimental(provenance: AacSourceProvenance, startUs: Long, endUs: Long,
            firstEditedPacketSample: Long, configuredDecoderDelay: Int): AacSourceClock =
            AacSourceClock(provenance, startUs, endUs, firstEditedPacketSample, configuredDecoderDelay)
    }
}

/** Exact packet-domain selection. Qualification is supplied by a test, never inferred from MIME. */
internal data class ExperimentalAacPreroll(val precedingPackets: Int, val maximumPacketSamples: Int) {
    init { require(precedingPackets in 1..8 && maximumPacketSamples in 1..4096) }

    fun firstPacket(editedPacketSamples: List<Long>, requestedSample: Long): Int {
        require(editedPacketSamples.isNotEmpty())
        editedPacketSamples.zipWithNext().forEach { (a, b) ->
            require(b > a && Math.subtractExact(b, a) <= maximumPacketSamples) {
                "Packet cadence outside experimental qualification"
            }
        }
        val next = editedPacketSamples.indexOfFirst { it >= requestedSample }
        require(next >= 0) { "Requested boundary beyond indexed packets" }
        return (next - precedingPackets).coerceAtLeast(0)
    }
}
