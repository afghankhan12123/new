package com.videoeditor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProfileTest {

    @Test
    fun stage6DefaultBitratesArePreserved() {
        // The accepted Stage 6 ladder: Standard 5M/10M, High 8M/16M at 30 fps.
        assertEquals(5_000_000, ExportProfile.videoBitrateBps(ExportResolution.RES_720P, ExportQuality.Standard, FrameRate.FPS_30))
        assertEquals(10_000_000, ExportProfile.videoBitrateBps(ExportResolution.RES_1080P, ExportQuality.Standard, FrameRate.FPS_30))
        assertEquals(8_000_000, ExportProfile.videoBitrateBps(ExportResolution.RES_720P, ExportQuality.High, FrameRate.FPS_30))
        assertEquals(16_000_000, ExportProfile.videoBitrateBps(ExportResolution.RES_1080P, ExportQuality.High, FrameRate.FPS_30))
    }

    @Test
    fun smallerIsHalfOfStandard() {
        assertEquals(2_500_000, ExportProfile.videoBitrateBps(ExportResolution.RES_720P, ExportQuality.Smaller, FrameRate.FPS_30))
        assertEquals(5_000_000, ExportProfile.videoBitrateBps(ExportResolution.RES_1080P, ExportQuality.Smaller, FrameRate.FPS_30))
    }

    @Test
    fun frameRateScalesBitrateProportionally() {
        assertEquals(4_000_000, ExportProfile.videoBitrateBps(ExportResolution.RES_720P, ExportQuality.Standard, FrameRate.FPS_24))
        assertEquals(10_000_000, ExportProfile.videoBitrateBps(ExportResolution.RES_720P, ExportQuality.Standard, FrameRate.FPS_60))
    }

    @Test
    fun presetsOrderStrictly() {
        for (resolution in ExportResolution.entries) {
            val smaller = ExportProfile.videoBitrateBps(resolution, ExportQuality.Smaller)
            val standard = ExportProfile.videoBitrateBps(resolution, ExportQuality.Standard)
            val high = ExportProfile.videoBitrateBps(resolution, ExportQuality.High)
            assertTrue(smaller < standard)
            assertTrue(standard < high)
        }
    }

    @Test
    fun estimateGrowsWithQualityAndDuration() {
        val base = ExportProfile.estimatedSizeBytes(4_000_000, ExportResolution.RES_1080P, ExportQuality.Standard, FrameRate.FPS_30)
        val bigger = ExportProfile.estimatedSizeBytes(4_000_000, ExportResolution.RES_1080P, ExportQuality.High, FrameRate.FPS_30)
        val longer = ExportProfile.estimatedSizeBytes(8_000_000, ExportResolution.RES_1080P, ExportQuality.Standard, FrameRate.FPS_30)
        assertTrue(bigger > base)
        assertTrue(longer > base)
        // 4s of 1080p Standard: (10M + 192k) * 4 * 1.015 / 8 ≈ 5.17 MB.
        assertEquals(5_172_440L, base)
    }

    @Test
    fun supportedFrameRatesNeverExceedSource() {
        assertEquals(listOf(FrameRate.FPS_24, FrameRate.FPS_30), ExportProfile.supportedFrameRates(24))
        assertEquals(listOf(FrameRate.FPS_24, FrameRate.FPS_30), ExportProfile.supportedFrameRates(30))
        assertEquals(listOf(FrameRate.FPS_24, FrameRate.FPS_30, FrameRate.FPS_60), ExportProfile.supportedFrameRates(60))
        // Unknown sources keep the full set; 30 fps is always available.
        assertEquals(FrameRate.entries.toList(), ExportProfile.supportedFrameRates(null))
    }

    @Test
    fun frameRateThirtyIsAlwaysSupported() {
        // Even a 24 fps source retains the Stage 6 default option.
        assertTrue(ExportProfile.supportedFrameRates(24).contains(FrameRate.FPS_30))
    }
}
