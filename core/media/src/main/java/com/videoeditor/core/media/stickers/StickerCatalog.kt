package com.videoeditor.core.media.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import com.videoeditor.core.media.R

/**
 * The bundled sticker artwork available to every project.
 *
 * Assets are packaged in this module's resources so the editing UI, the GL
 * compositing path and tests resolve identical pixels from one durable source
 * with no network dependency. Entries are immutable and keyed by the stable
 * [StickerAsset.key] stored in the project document, so a saved project always
 * re-resolves to the same artwork across reinstalls.
 */
object StickerCatalog {

    /** One bundled sticker artwork. [aspectRatio] is width / height of the
     * decoded bitmap and drives correct scaling at any canvas size. An asset may
     * appear under more than one [categories] entry — the approved design lists
     * Flower in both Popular and Decorative. */
    data class StickerAsset(
        val key: String,
        val label: String,
        val categories: Set<String>,
        @DrawableRes val drawableRes: Int,
        val aspectRatio: Float,
    ) {
        constructor(key: String, label: String, category: String, drawableRes: Int, aspectRatio: Float) :
            this(key, label, setOf(category), drawableRes, aspectRatio)
    }

    val categories: List<String> = listOf("Popular", "Shapes", "Arrows", "Social", "Decorative")

    val assets: List<StickerAsset> = listOf(
        // Popular
        StickerAsset("star", "Star", "Popular", R.drawable.editor_kit_sticker_star, 1f),
        StickerAsset("heart", "Heart", "Popular", R.drawable.editor_kit_sticker_heart, 1f),
        StickerAsset("sun", "Sun", "Popular", R.drawable.editor_kit_sticker_sun, 1f),
        StickerAsset("sparkle", "Sparkle", "Popular", R.drawable.editor_kit_sticker_sparkle, 1f),
        StickerAsset("flag", "Flag", "Popular", R.drawable.editor_kit_sticker_flag, 1f),
        StickerAsset("right_arrow", "Right arrow", "Popular", R.drawable.editor_kit_sticker_right_arrow, 1f),
        StickerAsset("flower", "Flower", setOf("Popular", "Decorative"), R.drawable.editor_kit_sticker_flower, 1f),
        StickerAsset("smile", "Smile", "Popular", R.drawable.editor_kit_sticker_smile, 1f),
        StickerAsset("airplane", "Airplane", "Popular", R.drawable.editor_kit_sticker_airplane, 1f),
        StickerAsset("diamond", "Diamond", "Popular", R.drawable.editor_kit_sticker_diamond, 1f),
        StickerAsset("sparkles", "Sparkles", "Popular", R.drawable.editor_kit_sticker_sparkles, 1f),
        StickerAsset("music_note", "Music note", "Popular", R.drawable.editor_kit_sticker_music_note, 1f),
        StickerAsset("premium_star", "Premium star", "Popular", R.drawable.editor_sticker_premium_star, 256f / 234f),
        StickerAsset("premium_heart", "Premium heart", "Popular", R.drawable.editor_sticker_premium_heart, 256f / 203f),
        // Shapes
        StickerAsset("circle", "Circle", "Shapes", R.drawable.editor_kit_sticker_circle, 1f),
        StickerAsset("square", "Square", "Shapes", R.drawable.editor_kit_sticker_square, 1f),
        StickerAsset("triangle", "Triangle", "Shapes", R.drawable.editor_kit_sticker_triangle, 1f),
        StickerAsset("hexagon", "Hexagon", "Shapes", R.drawable.editor_kit_sticker_hexagon, 1f),
        StickerAsset("plus", "Plus", "Shapes", R.drawable.editor_kit_sticker_plus, 1f),
        // Arrows
        StickerAsset("left_arrow", "Left arrow", "Arrows", R.drawable.editor_kit_sticker_left_arrow, 1f),
        // Social
        StickerAsset("speech_bubble", "Speech bubble", "Social", R.drawable.editor_kit_sticker_speech_bubble, 1f),
        // Decorative
        StickerAsset("crown", "Crown", "Decorative", R.drawable.editor_kit_sticker_crown, 1f),
        StickerAsset("fire", "Fire", "Decorative", R.drawable.editor_kit_sticker_fire, 1f),
        StickerAsset("lightning", "Lightning", "Decorative", R.drawable.editor_kit_sticker_lightning, 1f),
        StickerAsset("checkmark", "Checkmark", "Decorative", R.drawable.editor_kit_sticker_checkmark, 1f),
        StickerAsset("premium_sparkle", "Premium sparkle", "Decorative", R.drawable.editor_sticker_premium_sparkle, 256f / 221f),
        StickerAsset("premium_sun", "Premium sun", "Decorative", R.drawable.editor_sticker_premium_sun, 256f / 254f),
    )

    fun asset(key: String): StickerAsset? = assets.firstOrNull { it.key == key }

    fun assetsIn(category: String): List<StickerAsset> = assets.filter { category in it.categories }

    /**
     * Decodes an asset's bitmap at full packaged resolution with alpha intact.
     * Callers own the returned bitmap and must recycle it; [bitmapCache] is the
     * shared bounded owner for rendering paths that decode repeatedly.
     */
    fun decode(context: Context, asset: StickerAsset): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return runCatching {
            BitmapFactory.decodeResource(context.resources, asset.drawableRes, options)
        }.getOrNull()?.takeIf { it.hasAlpha() || it.config == Bitmap.Config.ARGB_8888 }
            ?: runCatching {
                // Some packaged sources decode opaque; re-decode forcing alpha so
                // compositing math is uniform across every catalog entry.
                BitmapFactory.decodeResource(context.resources, asset.drawableRes,
                    BitmapFactory.Options().apply { inScaled = false })
            }.getOrNull()
    }

    /** Bounded process-wide cache so a sticker reused across frames and layers
     * decodes once. The cache owns its bitmaps and recycles them on eviction. */
    private val bitmapCache = object : android.util.LruCache<String, Bitmap>(8) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && !oldValue.isRecycled) oldValue.recycle()
        }
    }

    /** Returns the shared decoded bitmap for [asset], or null when the resource
     * cannot be decoded. Do not recycle the returned bitmap. */
    fun sharedBitmap(context: Context, asset: StickerAsset): Bitmap? {
        bitmapCache.get(asset.key)?.takeIf { !it.isRecycled }?.let { return it }
        val decoded = decode(context, asset) ?: return null
        bitmapCache.put(asset.key, decoded)
        return decoded
    }

    /** Test-only: drops cached bitmaps between verification runs. */
    @androidx.annotation.VisibleForTesting
    fun evictBitmapCache() = bitmapCache.evictAll()
}
