package com.videoeditor.core.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.media.export.ExportRequest
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CaptionAnimationDeviceTest {
    @Test fun fade_slide_and_scale_change_decoded_caption_pixels_at_the_correct_times() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(retainedMediaTestDirectory(context), "animation-source.mp4")
        context.assets.open("color_grade_motion.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val base = ProjectCreator().create("Decoded animations", listOf(ref))
        val project = base.copy(timeline = base.timeline.copy(videoClips = base.timeline.videoClips.map { it.copy(sourceEndMicros = 1_000_000) }))
        val files = mutableListOf<File>()
        suspend fun export(name: String, overlay: TextOverlay?): File {
            val output = File(retainedMediaTestDirectory(context), "animation-$name.mp4"); files += output
            val edited = project.copy(timeline = project.timeline.copy(textOverlays = listOfNotNull(overlay)))
            assertTrue(ExportEngine(context, CompositionBuilder()).export(edited,
                ExportRequest(project.id, output, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)) {}.isSuccess)
            return output
        }
        fun frame(file: File, time: Long): Bitmap {
            val reader = MediaMetadataRetriever()
            return try { reader.setDataSource(file.absolutePath); reader.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)!! }
                finally { reader.release() }
        }
        data class Mask(val energy: Double, val centerX: Double, val cells: Int)
        fun mask(file: File, plain: File, time: Long): Mask {
            val rendered = frame(file, time); val baseline = frame(plain, time)
            var energy = 0.0; var weightedX = 0.0; var cells = 0
            try {
                for (y in rendered.height / 3 until rendered.height * 2 / 3 step 2) for (x in 0 until rendered.width step 2) {
                    val a = rendered.getPixel(x,y); val b = baseline.getPixel(x,y)
                    val difference = listOf(0,8,16).sumOf { kotlin.math.abs((a shr it and 255) - (b shr it and 255)) }
                    if (difference > 30) { energy += difference; weightedX += x * difference; cells++ }
                }
                assertTrue("Caption absent at $time in ${file.name}", cells > 20)
                return Mask(energy, weightedX / energy, cells)
            } finally { rendered.recycle(); baseline.recycle() }
        }
        try {
            val plain = export("plain", null)
            val title = TextOverlay("title", "Adventure مرحبا 世界", fontSizeSp = 48f, durationMicros = 1_000_000)
            val fade = export("fade", title.copy(enterAnimation = TextAnimation.FadeIn, exitAnimation = TextAnimation.FadeOut))
            val fadeMiddle = mask(fade, plain, 500_000)
            assertTrue(mask(fade, plain, 100_000).energy < fadeMiddle.energy * .5)
            assertTrue(mask(fade, plain, 900_000).energy < fadeMiddle.energy * .5)
            val slide = export("slide", title.copy(enterAnimation = TextAnimation.SlideIn, exitAnimation = TextAnimation.SlideOut))
            val slideMiddle = mask(slide, plain, 500_000)
            assertTrue("Slide in did not move right", slideMiddle.centerX - mask(slide, plain, 100_000).centerX > 200)
            assertTrue("Slide out did not move right", mask(slide, plain, 900_000).centerX - slideMiddle.centerX > 200)
            val scale = export("scale", title.copy(enterAnimation = TextAnimation.ScaleIn, exitAnimation = TextAnimation.ScaleOut))
            val scaleMiddle = mask(scale, plain, 500_000)
            assertTrue(mask(scale, plain, 100_000).cells < scaleMiddle.cells * .3)
            assertTrue(mask(scale, plain, 900_000).cells < scaleMiddle.cells * .3)
        } finally { files.forEach(File::delete); source.delete() }
    }
}
