package com.videoeditor.core.media.composition

import android.content.Context
import android.graphics.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import com.videoeditor.core.media.stickers.StickerCatalog
import com.videoeditor.core.model.*
import com.videoeditor.core.model.TextOverlay

/** One permanent canvas pass accepts added/removed captions and stickers without replacing decoders. */
@UnstableApi
internal class ProjectCanvasOverlayEffect(
    private val state: RenderPlanState,
    private val width: Int,
    private val height: Int,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        OverlayEffect(listOf(CanvasOverlay(context))).toGlShaderProgram(context, useHdr)

    private data class TextFrame(val model: TextOverlay, val alpha: Float, val scale: Float, val slide: Float)
    private data class Scene(val text: List<TextFrame>, val stickers: List<StickerClip>)
    private inner class CanvasOverlay(private val context: Context) : BitmapOverlay() {
        private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(bitmap)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // GL-thread-owned, byte bounded; immutable source bitmaps never escape this renderer.
        private val captions = object : android.util.LruCache<TextOverlay, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: TextOverlay, value: Bitmap) = value.allocationByteCount
            override fun entryRemoved(evicted: Boolean, key: TextOverlay, old: Bitmap, replacement: Bitmap?) {
                if (old !== replacement) old.recycle()
            }
        }
        private var lastScene: Scene? = null
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val plan = state.plan
            val scene = Scene(plan.project.timeline.textOverlays.filter {
                presentationTimeUs >= it.startMicros && presentationTimeUs < it.startMicros + it.durationMicros
            }.map { model ->
                val animationUs = minOf(500_000L, model.durationMicros / 2).coerceAtLeast(1L)
                val entering = ((presentationTimeUs - model.startMicros).toFloat() / animationUs).coerceIn(0f, 1f)
                val exiting = ((model.startMicros + model.durationMicros - presentationTimeUs).toFloat() / animationUs).coerceIn(0f, 1f)
                TextFrame(model,
                    model.opacity * (if (model.enterAnimation == TextAnimation.FadeIn) entering else 1f) *
                        (if (model.exitAnimation == TextAnimation.FadeOut) exiting else 1f),
                    model.scale * (if (model.enterAnimation == TextAnimation.ScaleIn) entering else 1f) *
                        (if (model.exitAnimation == TextAnimation.ScaleOut) exiting else 1f),
                    (if (model.enterAnimation == TextAnimation.SlideIn) -.3f * (1f - entering) else 0f) +
                        (if (model.exitAnimation == TextAnimation.SlideOut) .3f * (1f - exiting) else 0f))
            }, plan.project.timeline.stickers.filter {
                presentationTimeUs >= it.timelineStartMicros && presentationTimeUs < it.timelineEndMicros
            }.sortedWith(compareBy<StickerClip> { it.zIndex }.thenBy { it.id }))
            if (scene == lastScene) return bitmap
            bitmap.eraseColor(Color.TRANSPARENT)
            for (sticker in scene.stickers) {
                val asset = StickerCatalog.asset(sticker.assetKey) ?: continue
                val source = StickerCatalog.sharedBitmap(context, asset) ?: continue
                val edge = minOf(width, height) * StickerClip.BASE_CANVAS_FRACTION * sticker.scale
                val factor = edge / maxOf(source.width, source.height)
                draw(source, sticker.positionX, sticker.positionY, sticker.rotationDegrees,
                    factor, sticker.opacity)
            }
            for (frame in scene.text) {
                val model = frame.model
                // Position/timing/animation never invalidate a shaped-text raster.
                val rasterKey = model.copy(id = "", positionX = .5f, positionY = .5f, rotationDegrees = 0f,
                    scale = 1f, opacity = 1f, startMicros = 0L, durationMicros = 0L,
                    enterAnimation = TextAnimation.None, exitAnimation = TextAnimation.None)
                val source = captions.get(rasterKey) ?: renderTextBitmap(model, minOf(width, height) / 1080f, width)
                    .also { if (it.allocationByteCount <= captions.maxSize()) captions.put(rasterKey, it) }
                val transient = source.allocationByteCount > captions.maxSize()
                draw(source, (model.positionX + frame.slide).coerceIn(0f, 1f), model.positionY,
                    model.rotationDegrees, frame.scale, frame.alpha)
                if (transient) source.recycle()
            }
            lastScene = scene
            return bitmap // BitmapOverlay tracks generationId and uploads only changed pixels.
        }

        private fun draw(source: Bitmap, x: Float, y: Float, degrees: Float, scale: Float, alpha: Float) {
            val save = canvas.save()
            canvas.translate(x * width, y * height)
            canvas.rotate(degrees)
            canvas.scale(scale, scale)
            paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawBitmap(source, -source.width / 2f, -source.height / 2f, paint)
            canvas.restoreToCount(save)
        }

        override fun release() {
            try { super.release() } finally { captions.evictAll(); bitmap.recycle() }
        }
    }
}
