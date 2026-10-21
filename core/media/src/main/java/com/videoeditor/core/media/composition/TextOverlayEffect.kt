package com.videoeditor.core.media.composition

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import com.videoeditor.core.model.TextOverlay as ModelTextOverlay

/**
 * Renders the project's text overlays on top of a single video clip, shared by
 * preview (CompositionPlayer) and export (Transformer) because it is attached to
 * the clip's effect list in [CompositionBuilder] — there is one authoritative
 * text→render mapping, not two.
 *
 * Each model [ModelTextOverlay] becomes a Media3 [TextOverlay]. Media3 feeds
 * video effects sequence-offset timestamps before retiming. Subtract the clip
 * offset, scale local source progress by playback speed, then restore the offset.
 * This effect must precede TimestampAdjustment in the clip effect list.
 *
 * Visibility is gated by [OverlaySettings.setAlphaScale] rather than by swapping
 * the text content: the styled bitmap is rendered every frame (constant size, so
 * the GL texture never churns) and is simply made fully transparent outside the
 * overlay's [startMicros, startMicros + durationMicros) window. Returning an
 * empty string instead would make Media3 build a zero-width layout and fail the
 * whole frame pipeline ("Video frame processing error"), so that path is avoided.
 *
 * Android StaticLayout rasterization supplies shaped text, family selection,
 * shadow and outline. Animation and normalized transform values are shared
 * between preview and export; decoded parity remains a separate verification gate.
 */
@UnstableApi
class TextOverlayEffect(
    private val overlays: List<ModelTextOverlay>,
    private val clipStartMicros: Long,
    private val playbackSpeed: Float = 1f,
    private val textPixelScale: Float = 1f,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val textureOverlays = overlays.map { overlay -> RenderedTextOverlay(overlay) }
        return OverlayEffect(textureOverlays).toGlShaderProgram(context, useHdr)
    }

    /** One model overlay, resolved to content + transform per presentation time. */
    private inner class RenderedTextOverlay(
        private val model: ModelTextOverlay,
    ) : BitmapOverlay() {
        private val bitmap = renderTextBitmap(model, textPixelScale)
        override fun getBitmap(presentationTimeUs: Long): android.graphics.Bitmap = bitmap
        override fun release() {
            try { super.release() } finally { bitmap.recycle() }
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val projectTimeMicros = clipStartMicros +
                ((presentationTimeUs - clipStartMicros) / playbackSpeed).toLong()
            val endMicros = model.startMicros + model.durationMicros
            val visible = projectTimeMicros >= model.startMicros && projectTimeMicros < endMicros
            val elapsed = projectTimeMicros - model.startMicros
            val remaining = endMicros - projectTimeMicros
            val animationUs = minOf(500_000L, model.durationMicros / 2).coerceAtLeast(1)
            val entering = (elapsed.toFloat() / animationUs).coerceIn(0f, 1f)
            val exiting = (remaining.toFloat() / animationUs).coerceIn(0f, 1f)
            val enterFade = if (model.enterAnimation == com.videoeditor.core.model.TextAnimation.FadeIn) entering else 1f
            val exitFade = if (model.exitAnimation == com.videoeditor.core.model.TextAnimation.FadeOut) exiting else 1f
            val alpha = if (visible) model.opacity.coerceIn(0f, 1f) * enterFade * exitFade else 0f
            val slideX = (if (model.enterAnimation == com.videoeditor.core.model.TextAnimation.SlideIn) (1f - entering) * -0.3f else 0f) +
                (if (model.exitAnimation == com.videoeditor.core.model.TextAnimation.SlideOut) (1f - exiting) * 0.3f else 0f)
            val animationScale = (if (model.enterAnimation == com.videoeditor.core.model.TextAnimation.ScaleIn) entering else 1f) *
                (if (model.exitAnimation == com.videoeditor.core.model.TextAnimation.ScaleOut) exiting else 1f)
            return OverlaySettings.Builder()
                .setAlphaScale(alpha)
                // Model position is normalized with (0,0)=top-left, (1,1)=bottom-right
                // and refers to the text's center. Media3's background anchor uses
                // (-1,-1)=bottom-left, (1,1)=top-right, so map x directly and flip y.
                .setBackgroundFrameAnchor(
                    ((model.positionX + slideX) * 2f - 1f).coerceIn(-1f, 1f),
                    (1f - model.positionY * 2f).coerceIn(-1f, 1f),
                )
                .setOverlayFrameAnchor(0f, 0f)
                .setScale(model.scale * animationScale, model.scale * animationScale)
                .setRotationDegrees(model.rotationDegrees)
                .build()
        }
    }
}

/**
 * Maps a model overlay to the styled text Media3 renders. Font size is scaled so
 * the default 32 reads as a prominent caption on a 1080p canvas; the ratio keeps
 * proportions across resolutions.
 */
internal fun styledText(model: ModelTextOverlay, pixelScale: Float = 1f): SpannableString {
    val text = SpannableString(model.text)
    text.setSpan(android.text.style.TypefaceSpan(com.videoeditor.core.model.TextFonts.family(model.fontId)),
        0, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    text.setSpan(android.text.style.StyleSpan(
        (if (model.bold) android.graphics.Typeface.BOLD else 0) or
            (if (model.italic) android.graphics.Typeface.ITALIC else 0)),
        0, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    text.setSpan(
        android.text.style.AlignmentSpan.Standard(when (model.alignment) {
            com.videoeditor.core.model.TextAlignment.Start -> android.text.Layout.Alignment.ALIGN_NORMAL
            com.videoeditor.core.model.TextAlignment.Center -> android.text.Layout.Alignment.ALIGN_CENTER
            com.videoeditor.core.model.TextAlignment.End -> android.text.Layout.Alignment.ALIGN_OPPOSITE
        }), 0, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
    )
    val pixelSize = (model.fontSizeSp * 3f * pixelScale).toInt().coerceAtLeast(1)
    text.setSpan(
        AbsoluteSizeSpan(pixelSize),
        0,
        text.length,
        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
    )
    text.setSpan(
        ForegroundColorSpan(model.colorArgb),
        0,
        text.length,
        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
    )
    if (model.hasBackground) {
        text.setSpan(
            BackgroundColorSpan(model.backgroundColorArgb),
            0,
            text.length,
            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
        )
    }
    return text
}

/** StaticLayout preserves Android shaping, bidi and emoji. Rasterize once per edit, never per frame. */
@UnstableApi
internal fun renderTextBitmap(model: ModelTextOverlay, pixelScale: Float, canvasWidth: Int? = null): android.graphics.Bitmap {
    val raster = captionLayout(model, pixelScale, canvasWidth)
    val text = raster.text; val paint = raster.paint; val layout = raster.layout; val padding = raster.padding
    val width = layout.width
    val bitmap = android.graphics.Bitmap.createBitmap(width + 2 * padding, layout.height + 2 * padding, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.translate(padding.toFloat(), padding.toFloat())
    if (model.hasOutline) {
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach { text.removeSpan(it) }
        paint.color = model.outlineColorArgb
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = model.outlineWidthPx * pixelScale * 2f
        layout.draw(canvas)
        text.setSpan(ForegroundColorSpan(model.colorArgb), 0, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    }
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = model.colorArgb
    if (model.hasShadow) paint.setShadowLayer(4f * pixelScale, 2f * pixelScale, 2f * pixelScale, model.shadowColorArgb)
    layout.draw(canvas)
    return bitmap
}

private data class CaptionRaster(val text: SpannableString, val paint: android.text.TextPaint,
    val layout: android.text.StaticLayout, val padding: Int)
private fun captionLayout(model: ModelTextOverlay, pixelScale: Float, canvasWidth: Int? = null): CaptionRaster {
    val text = styledText(model, pixelScale)
    val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = model.fontSizeSp * 3f * pixelScale
        color = model.colorArgb
    }
    val padding = (12f * pixelScale + model.outlineWidthPx * pixelScale).toInt().coerceAtLeast(2)
    val maxWidth = model.maxWidthFraction?.takeIf { it.isFinite() }?.let {
        ((canvasWidth ?: (1080 * pixelScale).toInt()) * it.coerceIn(.1f, 1f)).toInt() - 2 * padding
    }?.coerceIn(1, 4096) ?: 4096
    val width = kotlin.math.ceil(android.text.Layout.getDesiredWidth(text, paint).toDouble()).toInt().coerceIn(1, maxWidth)
    val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(when(model.alignment) {
            com.videoeditor.core.model.TextAlignment.Start -> android.text.Layout.Alignment.ALIGN_NORMAL
            com.videoeditor.core.model.TextAlignment.Center -> android.text.Layout.Alignment.ALIGN_CENTER
            com.videoeditor.core.model.TextAlignment.End -> android.text.Layout.Alignment.ALIGN_OPPOSITE
        }).setTextDirection(android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
        .setIncludePad(true).build()
    return CaptionRaster(text, paint, layout, padding)
}
private data class CaptionMeasureKey(val text: String, val family: String, val size: Float, val bold: Boolean, val italic: Boolean,
    val alignment: com.videoeditor.core.model.TextAlignment, val outline: Float, val pixelScale: Float,
    val maxWidthFraction: Float?, val canvasWidth: Int?)
private val captionMeasures = android.util.LruCache<CaptionMeasureKey, android.util.Size>(64)

/** Same shaped layout and padding as the export bitmap, cached for canvas handles. */
fun measureCaptionDimensions(model: ModelTextOverlay, pixelScale: Float, canvasWidth: Int? = null): android.util.Size {
    val key = CaptionMeasureKey(model.text, com.videoeditor.core.model.TextFonts.family(model.fontId), model.fontSizeSp, model.bold, model.italic,
        model.alignment, model.outlineWidthPx, pixelScale, model.maxWidthFraction, canvasWidth)
    captionMeasures.get(key)?.let { return it }
    val raster = captionLayout(model, pixelScale, canvasWidth)
    return android.util.Size(raster.layout.width + 2 * raster.padding, raster.layout.height + 2 * raster.padding)
        .also { captionMeasures.put(key, it) }
}
