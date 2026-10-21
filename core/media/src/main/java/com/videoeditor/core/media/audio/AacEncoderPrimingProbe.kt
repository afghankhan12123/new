package com.videoeditor.core.media.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Candidate only: measure the chosen component/configuration; never infer a global delay. */
internal object AacEncoderPrimingProbe {
    val enabledForSupportedEncoders = true
    @Volatile var enabledForQualification = false
    data class Evidence(val codecName: String, val sampleRate: Int, val channels: Int,
        val bitrate: Int, val delaySamples: Int, val correlation: Double, val config: ByteArray)
    private data class Packet(val pts: Long, val bytes: ByteArray)

    fun measure(codecName: String, rate: Int, channels: Int, bitrate: Int): Evidence {
        require(channels in 1..2 && rate in listOf(44100,48000))
        val attempts=(0..2).map { probe(codecName,rate,channels,bitrate,it) }
        check(attempts.map { it.delaySamples }.distinct().size==1) { "Unstable actual encoder delay" }
        check(attempts.all { it.config.contentEquals(attempts.first().config) })
        return attempts.minBy { it.correlation }
    }

    private fun probe(codecName: String, rate: Int, channels: Int, bitrate: Int, seed: Int): Evidence {
        val samples=ShortArray(8192 * channels)
        // Smooth, broad-band, nonrepeating chirp with a different trajectory per probe.
        for (i in 0 until 2048) for (channel in 0 until channels) {
            val x=i/2048.0
            val trajectory = seed + channel * 3
            val phase=2*PI*((750+trajectory*173)*i/rate.toDouble()+
                (7600+trajectory*211)*i.toDouble()*i/(2*2048*rate))
            val envelope=sin(PI*x).pow(2)
            samples[(1024+i)*channels+channel]=(9000*envelope*(sin(phase)+.3*sin(phase*1.71))).toInt().coerceIn(-32768,32767).toShort()
        }
        val raw=ByteBuffer.allocate(samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { raw.putShort(it) };raw.flip()
        val encoder=MediaCodec.createByCodecName(codecName)
        val encoded=mutableListOf<Packet>()
        var outputFormat: MediaFormat?=null
        try {
            val config=MediaFormat.createAudioFormat("audio/mp4a-latm",rate,channels)
            config.setInteger(MediaFormat.KEY_AAC_PROFILE,2);config.setInteger(MediaFormat.KEY_BIT_RATE,bitrate)
            encoder.configure(config,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start()
            var inputDone=false;var outputDone=false;var submitted=0L
            val info=MediaCodec.BufferInfo();val deadline=SystemClock.elapsedRealtime()+10000
            while (!outputDone) {
                check(SystemClock.elapsedRealtime()<deadline) { "Encoder calibration timeout" }
                if (!inputDone) {
                    val index=encoder.dequeueInputBuffer(1000)
                    if (index>=0) {
                        val target=encoder.getInputBuffer(index)!!
                        val frameBytes = channels * 2
                        val count=minOf(target.remaining(),raw.remaining())/frameBytes*frameBytes
                        val oldLimit=raw.limit();raw.limit(raw.position()+count);target.put(raw);raw.limit(oldLimit)
                        inputDone=count==0
                        encoder.queueInputBuffer(index,0,count,submitted*1000000/rate,
                            if(inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        submitted+=count/(channels*2)
                    }
                }
                val index=encoder.dequeueOutputBuffer(info,1000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat=encoder.outputFormat
                else if(index>=0) {
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                        val b=encoder.getOutputBuffer(index)!!.duplicate();b.position(info.offset);b.limit(info.offset+info.size)
                        val bytes=ByteArray(info.size);b.get(bytes);encoded+=Packet(info.presentationTimeUs,bytes)
                    }
                    outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    encoder.releaseOutputBuffer(index,false)
                }
            }
        } finally { runCatching { encoder.stop() };encoder.release() }
        val format=checkNotNull(outputFormat)
        val csd=format.getByteBuffer("csd-0")!!.duplicate().let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        check((csd[0].toInt() and 255) shr 3==2 && csd[1].toInt() and 7==0) { "Unqualified AAC output profile/frame length" }
        format.setInteger("encoder-delay",0);format.setInteger("encoder-padding",0)
        val decoder=MediaCodec.createDecoderByType("audio/mp4a-latm")
        val pcm=ByteArrayOutputStream()
        try {
            decoder.configure(format,null,null,0);decoder.start()
            var next=0;var outputDone=false
            val info=MediaCodec.BufferInfo();val deadline=SystemClock.elapsedRealtime()+10000
            while(!outputDone) {
                check(SystemClock.elapsedRealtime()<deadline) { "Decoder calibration timeout" }
                if(next<=encoded.size) {
                    val index=decoder.dequeueInputBuffer(1000)
                    if(index>=0) {
                        val packet=encoded.getOrNull(next);packet?.let { decoder.getInputBuffer(index)!!.put(it.bytes) }
                        decoder.queueInputBuffer(index,0,packet?.bytes?.size ?: 0,packet?.pts ?: 0,
                            if(packet==null) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0);next++
                    }
                }
                val index=decoder.dequeueOutputBuffer(info,1000)
                if(index>=0) {
                    val b=decoder.getOutputBuffer(index)!!.duplicate();b.position(info.offset);b.limit(info.offset+info.size)
                    val bytes=ByteArray(info.size);b.get(bytes);pcm.write(bytes)
                    outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;decoder.releaseOutputBuffer(index,false)
                }
            }
        } finally { runCatching { decoder.stop() };decoder.release() }
        val b=ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val decoded=ShortArray(b.remaining()/2) { b.short }
        check(decoded.size == encoded.size*1024*channels) { "Encoded buffer/access-unit span outside LC qualification" }
        var best=-1.0;var second=-1.0;var bestDelay=-1
        for(delay in 0..8192) {
            if(delay+3072>decoded.size/channels)break
            var xy=0.0;var xx=0.0;var yy=0.0
            for(i in 1024 until 3072) for(channel in 0 until channels) {
                val x=samples[i*channels+channel].toDouble();val y=decoded[(i+delay)*channels+channel].toDouble()
                xy+=x*y;xx+=x*x;yy+=y*y
            }
            val correlation=if(yy==0.0)-1.0 else xy/sqrt(xx*yy)
            if(correlation>best) {second=best;best=correlation;bestDelay=delay} else if(correlation>second) second=correlation
        }
        check(best>.98 && best-second>.05) { "Ambiguous encoder calibration: delay=$bestDelay corr=$best alternative=$second" }
        return Evidence(codecName,rate,channels,bitrate,bestDelay,best,csd)
    }
}
