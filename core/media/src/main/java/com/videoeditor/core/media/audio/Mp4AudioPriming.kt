package com.videoeditor.core.media.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Candidate output-only correction. Requires measured component/configuration evidence. */
internal object Mp4AudioPriming {
    private data class Box(val type: String, val payload: ByteArray)
    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    private fun boxes(bytes: ByteArray): List<Box> {
        val input=buffer(bytes);val result=mutableListOf<Box>()
        while(input.hasRemaining()) {
            require(input.remaining()>=8)
            val count=input.int.toLong() and 0xffffffffL
            val type=ByteArray(4).also { input.get(it) }.toString(Charsets.US_ASCII)
            require(count in 8..(input.remaining()+8).toLong()) { "Unsupported nested MP4 box" }
            result+=Box(type,ByteArray(count.toInt()-8).also { input.get(it) })
        }
        return result
    }
    private fun pack(box: Box): ByteArray = ByteBuffer.allocate(box.payload.size+8)
        .putInt(box.payload.size+8).put(box.type.toByteArray(Charsets.US_ASCII)).put(box.payload).array()
    private fun pack(boxes: List<Box>) = java.io.ByteArrayOutputStream().also { output -> boxes.forEach { output.write(pack(it)) } }.toByteArray()
    private fun timing(payload: ByteArray): Pair<Long,Long> {
        val b=buffer(payload);val at=if(payload[0].toInt()==1)20 else 12
        val scale=b.getInt(at).toLong() and 0xffffffffL
        val duration=if(payload[0].toInt()==1)b.getLong(at+4) else b.getInt(at+4).toLong() and 0xffffffffL
        return scale to duration
    }
    private fun duration(payload: ByteArray, at: Int, value: Long): ByteArray = payload.copyOf().also {
        val b=buffer(it)
        if(it[0].toInt()==1)b.putLong(at,value) else {
            require(value in 0..0xffffffffL);b.putInt(at,value.toInt())
        }
    }
    private fun movieTiming(payload: ByteArray, scale: Long, value: Long): ByteArray {
        val at=if(payload[0].toInt()==1)20 else 12
        return duration(payload,at+4,value).also { buffer(it).putInt(at,scale.toInt()) }
    }
    private fun canonicalAudio(mdia: Box, expectedPackets: Long): Box {
        var counted=0L
        fun rewrite(parent: Box): Box = Box(parent.type,pack(boxes(parent.payload).map { child ->
            when(child.type) {
                "minf","stbl" -> rewrite(child)
                "ctts" -> error("Unexpected audio composition offsets")
                "stts" -> {
                    val b=buffer(child.payload);val entries=b.getInt(4)
                    var count=0L
                    repeat(entries) { count+=b.getInt(8+8*it).toLong() and 0xffffffffL }
                    check(count==expectedPackets)
                    counted=count
                    Box("stts",ByteBuffer.allocate(16).putInt(0).putInt(1).putInt(count.toInt()).putInt(1024).array())
                }
                else -> child
            }
        }))
        val rebuilt=rewrite(mdia)
        check(counted==expectedPackets)
        return Box("mdia",pack(boxes(rebuilt.payload).map { child ->
            if(child.type=="mdhd") {
                val (scale,_)=timing(child.payload)
                Box("mdhd",movieTiming(child.payload,scale,Math.multiplyExact(expectedPackets,1024)))
            } else child
        }))
    }
    private fun rescaleEdit(box: Box, factor: Long): Box = Box(box.type,pack(boxes(box.payload).map { child ->
        require(child.type=="elst")
        val b=buffer(child.payload.copyOf());val version=b.get(0).toInt();val entries=b.getInt(4)
        repeat(entries) { index ->
            val at=8+index*(if(version==1)20 else 12)
            if(version==1)b.putLong(at,Math.multiplyExact(b.getLong(at),factor))
            else {
                val value=Math.multiplyExact(b.getInt(at).toLong() and 0xffffffffL,factor)
                require(value<=0xffffffffL);b.putInt(at,value.toInt())
            }
        }
        Box(child.type,b.array())
    }))

    fun apply(file: File, encoder: CanonicalAacEncoder) {
        val evidence=encoder.evidence
        val frames=encoder.inputFrames
        require(frames>0 && evidence.delaySamples>=0)
        check(encoder.encodedFrames>=frames+evidence.delaySamples)
        RandomAccessFile(file,"rw").use { raf ->
            var moovAt=-1L;var payload: ByteArray?=null
            while(raf.filePointer<raf.length()) {
                val at=raf.filePointer
                var size=raf.readInt().toLong() and 0xffffffffL
                val type=ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
                var header=8
                if(size==1L) { size=raf.readLong();header=16 }
                require(size>=header && size<=raf.length()-at)
                if(type=="moov") {
                    require(header==8 && payload==null && size<=16*1024*1024)
                    moovAt=at;payload=ByteArray(size.toInt()-8).also { raf.readFully(it) }
                } else raf.seek(at+size)
            }
            val root=boxes(checkNotNull(payload))
            val (oldScale,_)=timing(root.single { it.type=="mvhd" }.payload)
            val scale=1_000_000L
            require(oldScale>0 && scale%oldScale==0L)
            val factor=scale/oldScale
            val audioDuration=(Math.multiplyExact(frames,scale)+evidence.sampleRate-1)/evidence.sampleRate
            var audioTracks=0
            var maxDuration=0L
            val tracks=root.filter { it.type=="trak" }.map { track ->
                val children=boxes(track.payload)
                val originalMdia=children.single { it.type=="mdia" }
                val media=boxes(originalMdia.payload)
                val handler=media.single { it.type=="hdlr" }.payload.copyOfRange(8,12).toString(Charsets.US_ASCII)
                val audio=handler=="soun"
                if(audio) {
                    audioTracks++
                    require(children.none { it.type=="edts" }) { "Existing AAC edit list requires review" }
                    require(timing(media.single { it.type=="mdhd" }.payload).first==evidence.sampleRate.toLong())
                }
                val mdia=if(audio)canonicalAudio(originalMdia,encoder.encodedFrames/1024) else originalMdia
                val tkhd=children.single { it.type=="tkhd" }
                val at=if(tkhd.payload[0].toInt()==1)28 else 20
                val old=if(tkhd.payload[0].toInt()==1)buffer(tkhd.payload).getLong(at)
                    else buffer(tkhd.payload).getInt(at).toLong() and 0xffffffffL
                val duration=if(audio)audioDuration else Math.multiplyExact(old,factor)
                maxDuration=maxOf(maxDuration,duration)
                val updated=children.map { child -> when(child.type) {
                    "tkhd" -> Box("tkhd",duration(child.payload,at,duration))
                    "mdia" -> mdia
                    "edts" -> rescaleEdit(child,factor)
                    else -> child
                }}.toMutableList()
                if(audio) {
                    val entry=ByteBuffer.allocate(28).putInt(0x01000000).putInt(1)
                        .putLong(audioDuration).putLong(evidence.delaySamples.toLong()).putShort(1).putShort(0).array()
                    updated+=Box("edts",pack(Box("elst",entry)))
                }
                Box("trak",pack(updated))
            }.iterator()
            require(audioTracks==1)
            val rewritten=pack(Box("moov",pack(root.map { child -> when(child.type) {
                "mvhd" -> Box("mvhd",movieTiming(child.payload,scale,maxDuration))
                "trak" -> tracks.next()
                else -> child
            }})))
            // Media bytes/chunk offsets do not move. Publish replacement only after it is durable.
            raf.seek(raf.length());raf.write(rewritten);raf.fd.sync()
            raf.seek(moovAt+4);raf.write("free".toByteArray(Charsets.US_ASCII));raf.fd.sync()
        }
    }
}
