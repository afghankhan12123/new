package com.videoeditor.core.model

import kotlinx.serialization.Serializable

/**
 * A decorative still-image layer placed on the canvas above the video track and
 * any PiP overlays, in the same visual family as [TextOverlay]. Stickers are
 * non-destructive: the project records only the asset reference and the
 * placement needed to re-render the layer, and preview and export share one
 * interpretation through the composition builder.
 *
 * Timing: [timelineStartMicros]/[timelineDurationMicros] place the sticker on
 * the project timeline independently of the main track; stickers never shift
 * other items and are simply not rendered outside their window.
 *
 * Placement: [positionX]/[positionY] are the sticker center in normalized
 * canvas coordinates (0..1, positive Y down), identical to [TextOverlay]'s
 * convention, so a sticker survives preview-size and export-resolution changes
 * untouched. [scale] multiplies the sticker's base size (a canvas fraction).
 * [rotationDegrees] is clockwise. [opacity] multiplies the asset's own alpha.
 *
 * Stacking: [zIndex] orders stickers among themselves; higher draws on top.
 * Every sticker renders above the video track and every PiP overlay, and text
 * renders above every sticker, preserving the established layer contract.
 */
@Serializable
data class StickerClip(
    val id: String,
    /** Durable asset key from the bundled sticker catalog, e.g. "star". */
    val assetKey: String,
    val timelineStartMicros: Long = 0L,
    val timelineDurationMicros: Long = 3_000_000L,
    /** Normalized center position on the canvas, 0..1. */
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    /** Multiplier on the base size: the sticker's longer edge covers
     * [scale] × [BASE_CANVAS_FRACTION] of the canvas short edge. */
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
    val zIndex: Int = 0,
) {
    val timelineEndMicros: Long get() = timelineStartMicros + timelineDurationMicros

    companion object {
        /** The sticker's longer edge covers this fraction of the canvas short
         * edge at scale 1 — the same visual weight a sticker carries in the
         * library preview. */
        const val BASE_CANVAS_FRACTION = 0.25f
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 4f
        const val MIN_DURATION_MICROS = 100_000L
        const val DEFAULT_DURATION_MICROS = 3_000_000L
    }
}
