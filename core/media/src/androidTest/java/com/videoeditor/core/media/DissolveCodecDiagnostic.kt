package com.videoeditor.core.media

import android.media.MediaCodec
import android.media.MediaExtractor
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.Codec
import com.videoeditor.core.media.preview.PreviewPhaseTrace
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Test-only passive wrappers. Packet fingerprints identify the actual compressed source. */
internal object DissolveCodecDiagnostic {
    fun install(sources: List<String>, outgoingOffset: Long, incomingOffset: Long) {
        fun fingerprint(buffer: ByteBuffer): String {
            val bytes = ByteArray(buffer.remaining()); buffer.duplicate().get(bytes)
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        val packets = mutableMapOf<String,String>()
        for (source in sources) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(android.net.Uri.parse(source).path!!)
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString("mime")!!.startsWith("video/") }
                extractor.selectTrack(track)
                val buffer = ByteBuffer.allocate(2*1024*1024)
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer,0)
                    if (size < 0) break
                    buffer.position(0); buffer.limit(size)
                    packets[fingerprint(buffer)] = source
                    if (!extractor.advance()) break
                }
            } finally { extractor.release() }
        }
        fun decorate(delegate: Codec, encoder: Boolean): Codec = object : Codec by delegate {
            init { PreviewPhaseTrace.identity(this,"codecCreated",-1,androidx.media3.common.C.TIME_UNSET,"encoder=$encoder codec=${delegate.name}") }
            var source = "unidentified"
            var sourcePts = Long.MIN_VALUE
            fun event(phase: String, rawPts: Long, detail: String = "") {
                val actualSourcePts = rawPts % 1_000_000_000_000L
                val offset = if (source == sources[0]) outgoingOffset else incomingOffset
                val projectPts = if (encoder) rawPts else actualSourcePts-offset
                PreviewPhaseTrace.identity(this,phase,-1,projectPts,
                    "source=$source sourcePts=$actualSourcePts codec=${delegate.name} $detail")
            }
            override fun queueInputBuffer(buffer: DecoderInputBuffer) {
                if (!buffer.isEndOfStream) buffer.data?.let { packets[fingerprint(it)]?.let { id -> source=id } }
                if (buffer.isEndOfStream) PreviewPhaseTrace.identity(this,"exportDecoderEOS",-1,androidx.media3.common.C.TIME_UNSET,"source=$source rawPts=${buffer.timeUs}")
                else event("exportDecoderQueued",buffer.timeUs)
                delegate.queueInputBuffer(buffer)
            }
            override fun getOutputBufferInfo(): MediaCodec.BufferInfo? {
                val info=delegate.outputBufferInfo
                if(info!=null) { sourcePts=info.presentationTimeUs;event(if(encoder) "encoded" else "exportDecoded",sourcePts) }
                return info
            }
            override fun releaseOutputBuffer(renderPresentationTimeUs: Long) {
                event("exportProcessorAccepted",sourcePts,"renderPts=$renderPresentationTimeUs")
                delegate.releaseOutputBuffer(renderPresentationTimeUs)
            }
            override fun releaseOutputBuffer(render: Boolean) {
                if(!encoder) event(if(render) "exportProcessorAccepted" else "exportDecodedDropped",sourcePts)
                delegate.releaseOutputBuffer(render)
            }
        }
        PreviewPhaseTrace.diagnosticVideoDecoder = java.util.function.Function { decorate(it,false) }
        PreviewPhaseTrace.diagnosticVideoEncoder = java.util.function.Function { decorate(it,true) }
    }
}
