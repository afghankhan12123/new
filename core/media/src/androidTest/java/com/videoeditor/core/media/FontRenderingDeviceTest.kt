package com.videoeditor.core.media

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.*
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FontRenderingDeviceTest {
    @Test fun supported_system_font_choices_produce_distinct_decoded_glyphs() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(retainedMediaTestDirectory(context), "font-source.mp4")
        context.assets.open("color_grade_motion.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val base = ProjectCreator().create("Font pixels", listOf(ref))
        val project = base.copy(timeline = base.timeline.copy(videoClips = base.timeline.videoClips.map { it.copy(sourceEndMicros = 600_000) }))
        val files = mutableListOf<File>()
        suspend fun export(id: String): android.graphics.Bitmap {
            val output = File(retainedMediaTestDirectory(context), "font-$id.mp4"); files += output
            val edited = project.copy(timeline = project.timeline.copy(textOverlays = listOf(TextOverlay("font", "Adventure ABCD", fontId = id, fontSizeSp = 48f, durationMicros = 600_000))))
            assertTrue(ExportEngine(context, CompositionBuilder()).export(edited,
                ExportRequest(project.id, output, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)) {}.isSuccess)
            val reader = MediaMetadataRetriever()
            return try { reader.setDataSource(output.absolutePath); reader.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST)!! }
                finally { reader.release() }
        }
        val default = export("default")
        try {
            for (font in TextFonts.choices.filter { it.id != "default" && android.graphics.Typeface.create(it.family, 0) != android.graphics.Typeface.DEFAULT }) {
                val rendered = export(font.id)
                try {
                    var changed = 0
                    for (y in default.height / 3 until default.height * 2 / 3 step 3) for (x in 0 until default.width step 3) {
                        val a = default.getPixel(x,y); val b = rendered.getPixel(x,y)
                        if (listOf(0,8,16).sumOf { kotlin.math.abs((a shr it and 255) - (b shr it and 255)) } > 80) changed++
                    }
                    assertTrue("Font ${font.label} falls back to Default: $changed", changed > 200)
                    android.util.Log.i("FontPixels", "${font.id} changedCells=$changed")
                } finally { rendered.recycle() }
            }
        } finally { default.recycle(); files.forEach(File::delete); source.delete() }
    }
}
