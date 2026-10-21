package com.videoeditor.core.media.audio

import android.media.MediaCodec
import androidx.media3.common.Format
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.Codec

/** Candidate: output AAC frame sample clock, independent from source decoder timing. */
@androidx.media3.common.util.UnstableApi
internal class CanonicalAacEncoder(private val delegate: Codec,
    val evidence: AacEncoderPrimingProbe.Evidence) : Codec by delegate {
    var inputFrames = 0L
        private set
    private var packet = 0L
    private var releasedPacket = true
    private val packetInfo = MediaCodec.BufferInfo()
    override fun queueInputBuffer(input: DecoderInputBuffer) {
        val bytes = input.data?.remaining() ?: 0
        check(bytes % (evidence.channels*2) == 0)
        inputFrames += bytes/(evidence.channels*2)
        delegate.queueInputBuffer(input)
    }
    override fun getOutputFormat(): Format? = delegate.outputFormat?.also { format ->
        check(format.sampleRate == evidence.sampleRate && format.channelCount == evidence.channels)
        check(format.initializationData.size == 1 && format.initializationData[0].contentEquals(evidence.config)) {
            "Actual encoder ASC differs from calibrated configuration"
        }
    }
    override fun getOutputBufferInfo(): MediaCodec.BufferInfo? {
        val original = delegate.outputBufferInfo ?: return null
        if(releasedPacket) {
            packetInfo.set(original.offset,original.size,packet*1024L*1000000/evidence.sampleRate,original.flags)
            releasedPacket=false
        }
        return packetInfo
    }
    override fun releaseOutputBuffer(render: Boolean) {
        check(!render)
        // Encoder consumers may call getOutputBuffer without requesting info first.
        if(releasedPacket) checkNotNull(outputBufferInfo)
        delegate.releaseOutputBuffer(false);packet++;releasedPacket=true
    }
    val encodedFrames: Long get() = packet*1024
}
