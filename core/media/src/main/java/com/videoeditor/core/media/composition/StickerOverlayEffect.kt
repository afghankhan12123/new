package com.videoeditor.core.media.composition

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import com.videoeditor.core.media.stickers.StickerCatalog
import com.videoeditor.core.model.StickerClip

/**
 * Renders the project's sticker layers on top of the video, shared by preview
 * (CompositionPlayer) and export (Transformer) through the composition's effect
 * list — one authoritative sticker→render mapping, not two.
 *
 * Follows the same contract as [TextOverlayEffect]: each model [StickerClip]
 * becomes a Media3 [BitmapOverlay] whose bitmap is decoded once from the
 * bundled catalog (never per frame), visibility is gated by
 * [OverlaySettings.setAlphaScale] against the sticker's timeline window, and
 * placement maps the model's normalized center coordinates (0..1, positive Y
 * down) onto Media3's anchor space ((-1,-1)=bottom-left, (1,1)=top-right).
 *
 * The bitmap is letterboxed to a square whose side is the layer's longer edge
 * so a sticker's aspect ratio survives uniform scale. [StickerClip.scale] is
 * expressed as a fraction of the output frame's short edge via the initial
 * bitmap size; Media3 scales overlays relative to the frame, so normalizing
 * the decoded asset to a fixed pixel budget keeps geometry identical between
 * preview canvas and export resolution.
 */
@UnstableApi
class StickerOverlayEffect(
    private val stickers: List<StickerClip>,
    private val clipStartMicros: Long,
    private val playbackSpeed: Float = 1f,
    /** Output short edge in pixels; scales the base sticker size. */
    private val canvasShortEdgePx: Int,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        // Later overlays draw on top (OverlayEffect blends FIFO), so ascending
        // z-index preserves the model's stacking contract.
        val sorted = stickers.sortedBy { it.zIndex }
        return OverlayEffect(sorted.map { RenderedStickerOverlay(context, it) })
            .toGlShaderProgram(context, useHdr)
    }

    /** One model sticker, resolved to content + transform per presentation time. */
    private inner class RenderedStickerOverlay(
        context: Context,
        private val model: StickerClip,
    ) : BitmapOverlay() {
        /** Longer-edge pixel size at scale 1, derived from the output short edge
         * so the same canvas fraction holds at 720p and 1080p. Declared before
         * [bitmap]: Kotlin initializes properties in declaration order, and the
         * decode below depends on this value. */
        private val basePixels: Int =
            (canvasShortEdgePx * StickerClip.BASE_CANVAS_FRACTION).toInt().coerceAtLeast(2)

        private val asset = StickerCatalog.asset(model.assetKey)
        private val bitmap: android.graphics.Bitmap? = asset?.let { a ->
            StickerCatalog.sharedBitmap(context, a)?.let { source -> normalize(source, a.aspectRatio) }
        }

        /**
         * Re-renders the asset onto a square canvas sized so the longer edge is
         * [basePixels]. The returned bitmap is owned by this overlay and
         * recycled on [release]; the shared decode is untouched.
         */
        private fun normalize(source: android.graphics.Bitmap, aspect: Float): android.graphics.Bitmap {
            val base = basePixels.coerceAtLeast(2)
            val targetW: Int
            val targetH: Int
            if (aspect >= 1f) { targetW = base; targetH = (base / aspect).toInt().coerceAtLeast(1) }
            else { targetH = base; targetW = (base * aspect).toInt().coerceAtLeast(1) }
            val scaled = android.graphics.Bitmap.createScaledBitmap(source, targetW, targetH, true)
            // createScaledBitmap returns the source instance when the dimensions
            // already match. That instance belongs to the shared catalog cache,
            // so copy it: release() recycles only overlay-owned bitmaps.
            return if (scaled === source) source.copy(source.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)!! else scaled
        }

        override fun getBitmap(presentationTimeUs: Long): android.graphics.Bitmap =
            bitmap ?: emptyBitmap

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val projectTimeMicros = clipStartMicros +
                ((presentationTimeUs - clipStartMicros) / playbackSpeed).toLong()
            val visible = bitmap != null &&
                projectTimeMicros >= model.timelineStartMicros &&
                projectTimeMicros < model.timelineEndMicros
            return OverlaySettings.Builder()
                .setAlphaScale(if (visible) model.opacity.coerceIn(0f, 1f) else 0f)
                // Model: normalized center (0..1), positive Y down.
                // Media3 anchor: (-1,-1)=bottom-left, (1,1)=top-right.
                .setBackgroundFrameAnchor(
                    (model.positionX * 2f - 1f).coerceIn(-1f, 1f),
                    (1f - model.positionY * 2f).coerceIn(-1f, 1f),
                )
                .setOverlayFrameAnchor(0f, 0f)
                .setScale(model.scale, model.scale)
                .setRotationDegrees(model.rotationDegrees)
                .build()
        }

        override fun release() {
            try {
                if (bitmap != null && bitmap != emptyBitmap && !bitmap.isRecycled) bitmap.recycle()
            } finally {
                super.release()
            }
        }
    }

    private companion object {
        /** 1×1 transparent placeholder so a missing asset degrades to
         * invisibility instead of failing the whole frame pipeline — the same
         * defensive posture as the text effect's never-empty layout. */
        val emptyBitmap: android.graphics.Bitmap by lazy {
            android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        }
    }
}
