package com.videoeditor.core.media.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class AacSourceClockTest {
    private fun provenance(rate: Int) = AacSourceProvenance("a".repeat(64), 0, rate, 1, 1024, 0, -1024)

    @Test fun runtimeIdentityDoesNotInventContentHashOrDelay() {
        val p = AacSourceProvenance(null, 1, 48000, 1, null, null, 0, "content://source/track")
        assertNull(p.sourceSha256)
        assertNull(p.originalEncoderDelay)
        assertNull(p.originalEncoderPadding)
        assertEquals("content://source/track", p.sourceIdentity)
        assertThrows(IllegalArgumentException::class.java) {
            AacSourceProvenance(null, 1, 48000, 1, null, null, 0)
        }
    }

    @Test fun retainedNativeWindowsAreByteExactAcrossChunkBoundaries() {
        val path = System.getenv("AAC_CAUSAL_MATRIX")
        org.junit.Assume.assumeTrue("External retained PCM gate requires AAC_CAUSAL_MATRIX", path != null)
        val root = File(path!!)
        var count = 0
        for (rate in listOf(44100, 48000)) {
            for (requested in listOf(0, rate, rate + 47, 51199, 51200, 51201, 6 * rate - 3073)) {
                val prefix = "$rate-$requested-preroll2-delay0"
                val config = File(root, "$prefix-config.txt").readText().trim().split(Regex("\\s+")).associate {
                    val pair = it.split('=', limit = 2); pair[0] to pair[1]
                }
                val first = config.getValue("firstPacket").toInt()
                // Packet identity table is the original native extractor clock, not Media3 PTS.
                val ptsUs = Regex("pts=(-?\\d+)").find(File(root, "$rate-packets.txt").readLines()[first])!!.groupValues[1].toLong()
                // Native microseconds truncate rational sample PTS; nearest sample restores this fixture's grid.
                val origin = (ptsUs * rate + if (ptsUs >= 0) 500_000 else -500_000) / 1_000_000
                val startUs = config.getValue("startUs").toLong()
                val endUs = config.getValue("endUs").toLong()
                val clock = AacSourceClock.experimental(provenance(rate), startUs, endUs, origin, 0)
                val raw = File(root, "$prefix.pcm").readBytes()
                val actual = java.io.ByteArrayOutputStream()
                var position = 0
                while (position < raw.size) {
                    val length = minOf(514, raw.size - position)
                    val slice = clock.trim(ByteBuffer.wrap(raw, position, length))
                    val bytes = ByteArray(slice.pcm.remaining()); slice.pcm.get(bytes); actual.write(bytes)
                    position += length
                }
                clock.finish()
                val full = File(root, "$rate-$requested-full-delay1024.pcm").readBytes()
                assertArrayEquals(prefix, full.copyOfRange((clock.startSample * 2).toInt(), (clock.endSample * 2).toInt()), actual.toByteArray())
                assertEquals((clock.endSample-clock.startSample)*2, actual.size().toLong())
                count++
            }
        }
        assertEquals(14, count)
    }

    @Test fun stereoFramesAndIndependentGenerationsKeepExactBounds() {
        val p = provenance(48000).copy(channels = 2)
        val first = AacSourceClock.experimental(p, 21, 84, 0, 0)
        val second = AacSourceClock.experimental(p, 0, 42, 0, 0)
        val input = ByteBuffer.wrap(ByteArray(24) { it.toByte() })
        val slice = first.trim(input)
        assertEquals(2L, slice.firstSourceSample)
        assertEquals(12, slice.pcm.remaining())
        assertEquals(0, input.position())
        first.finish()
        assertEquals(12, second.trim(input).pcm.remaining())
        second.finish()
        assertThrows(IllegalStateException::class.java) { first.trim(input) }
    }

    @Test fun packetSelectionRejectsUnqualifiedCadence() {
        val policy = ExperimentalAacPreroll(2, 1024)
        assertEquals(1, policy.firstPacket(listOf(-1024, 0, 1024, 2048, 3072), 2048))
        assertThrows(IllegalArgumentException::class.java) {
            policy.firstPacket(listOf(0, 2048), 1000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.firstPacket(listOf(0, 1024), 2048)
        }
    }

    @Test fun staleGenerationAndMissingTailFailClosed() {
        val clock = AacSourceClock.experimental(provenance(48000), 0, 1000, -1024, 0)
        assertThrows(IllegalStateException::class.java) { clock.finish() }
        clock.close()
        assertThrows(IllegalStateException::class.java) { clock.trim(ByteBuffer.allocate(2)) }
    }
}
