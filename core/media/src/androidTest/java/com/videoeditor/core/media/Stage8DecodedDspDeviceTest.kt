package com.videoeditor.core.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.media.audio.*
import com.videoeditor.core.model.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Stage8DecodedDspDeviceTest {
    private fun decode(name: String): ShortArray {
        val context = InstrumentationRegistry.getInstrumentation().context
        val file = File(context.filesDir, "stage8-dsp-$name")
        context.assets.open("stage8/$name").use { input -> file.outputStream().use(input::copyTo) }
        val samples = ArrayList<Short>()
        visitAudioSamples(file) { _, channel, sample -> if (channel == 0) samples.add(sample.toInt().toShort()) }
        return samples.toShortArray()
    }
    private fun process(processor: AudioProcessor, input: ShortArray, rate: Int, chunk: Int = 1024): ShortArray {
        processor.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT)); processor.flush()
        // Media3 removes inactive processors from its pipeline. Reproduce that
        // bypass rather than calling queueInput on a disabled EQ.
        if (!processor.isActive) { processor.reset(); return input.copyOf() }
        val result = ArrayList<Short>()
        for (offset in input.indices step chunk) {
            val end = minOf(input.size, offset + chunk)
            val buffer = ByteBuffer.allocateDirect((end-offset)*2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in offset until end) buffer.putShort(input[i])
            buffer.flip(); processor.queueInput(buffer)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            while (output.hasRemaining()) result.add(output.short)
        }
        processor.queueEndOfStream()
        val tail = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        while (tail.hasRemaining()) result.add(tail.short)
        assertTrue(processor.isEnded); processor.reset()
        return result.toShortArray()
    }
    @Test fun nativeDecodedAutomationMatchesIndependentCurvesAtBothRates() {
        for ((name,rate) in listOf("tone440_44k_mono.m4a" to 44100, "tone440_48k_mono.m4a" to 48000)) {
            val input = decode(name)
            for (mode in VolumeInterpolation.entries) {
                val envelope = VolumeEnvelope(listOf(VolumePoint("a", 0, 0f, mode), VolumePoint("b", 2_000_000, -12f)))
                val actual = process(VolumeEnvelopeAudioProcessor(envelope,0,1f),input,rate,997)
                assertEquals(input.size,actual.size)
                for (i in input.indices) {
                    val t = (i*1_000_000L/rate).toDouble()/2_000_000
                    val x=t.coerceIn(0.0,1.0)
                    val u=when(mode) { VolumeInterpolation.Linear -> x; VolumeInterpolation.Hold -> if(t<1) 0.0 else 1.0; VolumeInterpolation.Smooth -> x*x*(3-2*x) }
                    val expected=(input[i]*10.0.pow(-12*u/20)).toInt()
                    assertEquals("rate=$rate mode=$mode frame=$i",expected.toDouble(),actual[i].toDouble(),2.0)
                }
            }
        }
    }
    @Test fun nativeDecodedAllFadeCurvesMatchIndependentOracle() {
        val rate=48000; val input=decode("tone440_48k_mono.m4a")
        for (curve in FadeCurve.entries) {
            val actual=process(FadeAudioProcessor(6_000_000,4_000_000,4_000_000,curve,curve),input,rate,997)
            assertEquals(input.size,actual.size)
            fun gain(t: Double): Double {
                val x=t.coerceIn(0.0,1.0)
                return when(curve) {
                    FadeCurve.Linear -> x; FadeCurve.EaseIn -> x*x; FadeCurve.EaseOut -> 1-(1-x)*(1-x)
                    FadeCurve.Smooth -> x*x*(3-2*x); FadeCurve.Exponential -> (3.0.pow(x)-1)/2
                }
            }
            for(i in input.indices) {
                val t=(i*1_000_000L/rate).toDouble()
                val expected=(input[i]*minOf(gain(t/4_000_000),gain((6_000_000-t)/4_000_000))).toInt()
                assertEquals("curve=$curve frame=$i",expected.toDouble(),actual[i].toDouble(),2.0)
            }
        }
    }
    @Test fun equalizerNativeDecodedPcmIsContinuousAcrossBufferBoundariesAndReset() {
        val context=InstrumentationRegistry.getInstrumentation().context
        val dir=File(context.filesDir,"stage8-eq-${android.os.SystemClock.elapsedRealtimeNanos()}").apply{mkdirs()}
        fun save(name:String, pcm:ShortArray) {
            val bytes=ByteBuffer.allocate(pcm.size*2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bytes.putShort(it) }; File(dir,name).writeBytes(bytes.array())
        }
        for ((name,rate) in listOf("tone440_44k_mono.m4a" to 44100, "eq_three_tone_48k.m4a" to 48000)) {
            val input=decode(name)
            save("$rate-input.s16le",input)
            val settings=EqualizerPreset.entries.map { it.name to it.settings() } + listOf(
                "Manual" to EqualizerSettings(true,-6f,3f,-4f),
                "MultipleBoost" to EqualizerSettings(true,6f,6f,6f))
            for ((preset, bands) in settings) {
                val processor=EqualizerAudioProcessor(bands)
                val a=process(processor,input,rate,997)
                val b=process(processor,input,rate,input.size)
                assertArrayEquals("$rate $preset chunk/reset continuity",a,b)
                if(preset=="Flat") assertArrayEquals(input,a)
                assertEquals(input.size,a.size)
                save("$rate-$preset.s16le",a)
            }
        }
        android.util.Log.i("Stage8Eq", "retained=${dir.absolutePath}")
    }
    @Test fun duckingAppliesTimelineCurveToNativeDecodedMusic() {
        val input=decode("music_tone220_48k.m4a")
        // Constant -12dB interior target; no detector claim is made by this processor test.
        val actual=process(DuckingGainAudioProcessor(DuckingCurve(0,1_000_000,FloatArray(9){.25f}),0),input,48000,997)
        assertEquals(input.size,actual.size)
        for(i in input.indices) assertEquals((input[i]*.25).toInt(),actual[i].toInt())
    }
}
