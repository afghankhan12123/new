package com.videoeditor.core.model

/**
 * The single source of truth for export encoder settings and size estimates.
 *
 * The export engine derives its requested video bitrate from [videoBitrateBps],
 * and the export screen's estimate derives from [estimatedSizeBytes], so the
 * number a user sees and the value the encoder receives can never drift apart.
 */
object ExportProfile {

    /** AAC audio bitrate shared by every export preset. */
    const val AUDIO_BITRATE_BPS = 192_000

    /**
     * Target H.264 video bitrate for the selected resolution, quality preset
     * and frame rate. Standard/High at 30 fps are the accepted Stage 6 values;
     * Smaller is half of Standard, and 24/60 fps scale the base rate so the
     * presets hold the same per-frame quality at every supported cadence.
     */
    fun videoBitrateBps(
        resolution: ExportResolution,
        quality: ExportQuality,
        frameRate: FrameRate = FrameRate.FPS_30,
    ): Int {
        val base = when (resolution) {
            ExportResolution.RES_720P -> when (quality) {
                ExportQuality.Smaller -> 2_500_000
                ExportQuality.Standard -> 5_000_000
                ExportQuality.High -> 8_000_000
            }
            ExportResolution.RES_1080P -> when (quality) {
                ExportQuality.Smaller -> 5_000_000
                ExportQuality.Standard -> 10_000_000
                ExportQuality.High -> 16_000_000
            }
        }
        return (base.toLong() * frameRate.fps / 30).toInt()
    }

    /**
     * Approximate output size in bytes for a composition of [durationMicros]
     * rendered at the given settings. Includes ~1.5% MP4 container overhead.
     * This is an estimate, not a guarantee — encoders are free to distribute
     * the budget across frames as they see fit.
     */
    fun estimatedSizeBytes(
        durationMicros: Long,
        resolution: ExportResolution,
        quality: ExportQuality,
        frameRate: FrameRate,
    ): Long {
        val durationSeconds = durationMicros / 1_000_000.0
        val totalBits = (videoBitrateBps(resolution, quality, frameRate) + AUDIO_BITRATE_BPS) *
            durationSeconds * 1.015
        // Round, not truncate: binary floating point lands a hair under exact
        // values, and a systematic one-byte floor would only add noise.
        return kotlin.math.round(totalBits / 8).toLong()
    }

    /** Frame rates a project whose sources run at [sourceFps] can export at.
     * A source cadence can be preserved or dropped to a lower standard rate;
     * rates above the source are not offered because no real motion detail
     * would be added. When the source rate is unknown, the full set is offered
     * (30 fps is the accepted Stage 6 default and always remains available). */
    fun supportedFrameRates(sourceFps: Int?): List<FrameRate> =
        FrameRate.entries.filter { sourceFps == null || it.fps <= sourceFps || it.fps == FrameRate.FPS_30.fps }
}
