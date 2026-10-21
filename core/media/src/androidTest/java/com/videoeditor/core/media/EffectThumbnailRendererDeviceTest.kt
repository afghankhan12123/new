package com.videoeditor.core.media

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.media.composition.EffectThumbnailRenderer
import com.videoeditor.core.model.VisualEffectType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EffectThumbnailRendererDeviceTest {
    @Test fun gallery_uses_production_shader_and_preserves_source_orientation() = runBlocking {
        val source = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        for (y in 0 until 144) for (x in 0 until 144) source.setPixel(x, y,
            if (y < 72) Color.rgb(200, 80, 40) else Color.rgb(30, 100, 220))
        val results = EffectThumbnailRenderer.render(InstrumentationRegistry.getInstrumentation().targetContext,
            source, listOf(VisualEffectType.InvertColor, VisualEffectType.BlackAndWhite, VisualEffectType.Sepia))
        try {
            val inverseTop = results.getValue(VisualEffectType.InvertColor).getPixel(72, 24)
            val inverseBottom = results.getValue(VisualEffectType.InvertColor).getPixel(72, 120)
            assertTrue("Top orientation", Color.red(inverseTop) < Color.blue(inverseTop))
            assertTrue("Bottom orientation", Color.red(inverseBottom) > Color.blue(inverseBottom))
            val gray = results.getValue(VisualEffectType.BlackAndWhite).getPixel(72, 24)
            assertTrue(kotlin.math.abs(Color.red(gray) - Color.blue(gray)) <= 2)
            val sepia = results.getValue(VisualEffectType.Sepia).getPixel(72, 120)
            assertTrue(Color.red(sepia) > Color.blue(sepia))
        } finally { results.values.forEach { it.recycle() }; source.recycle() }
    }
}
