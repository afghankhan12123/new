package com.videoeditor.core.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
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

class ExifPhotoDeviceTest {
    @Test fun all_exif_orientations_preserve_canvas_thumbnail_and_export_corners() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val expected = listOf(listOf(0,1,2,3), listOf(1,0,3,2), listOf(3,2,1,0), listOf(2,3,0,1),
            listOf(0,2,1,3), listOf(2,0,3,1), listOf(3,1,2,0), listOf(1,3,0,2))
        val source = File(retainedMediaTestDirectory(context), "exif-orientation.jpg")
        val output = File(retainedMediaTestDirectory(context), "exif-orientation.mp4")
        fun verify(bitmap: Bitmap, orientation: Int, path: String) {
            val points = listOf(.25f to .25f, .75f to .25f, .25f to .75f, .75f to .75f)
            points.forEachIndexed { index, (x,y) ->
                val actual = bitmap.getPixel((x*bitmap.width).toInt(), (y*bitmap.height).toInt())
                val wanted = colors[expected[orientation-1][index]]
                val distance = listOf(0,8,16).sumOf { kotlin.math.abs((actual shr it and 255) - (wanted shr it and 255)) }
                assertTrue("$path orientation=$orientation corner=$index distance=$distance", distance < 90)
            }
        }
        try {
            for (orientation in 1..8) {
                val bitmap = Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap); val paint = Paint()
                colors.forEachIndexed { i,c -> paint.color=c; val x=(i%2)*60f; val y=(i/2)*40f; canvas.drawRect(x,y,x+60,y+40,paint) }
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,100,it) }; bitmap.recycle()
                ExifInterface(source.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString()); saveAttributes() }
                val ref = MediaMetadataExtractor().extract(context,Uri.fromFile(source)).getOrNull()!!
                assertEquals(orientation,ref.photoOrientation)
                assertEquals(if(orientation>=5)80 else 120,ref.width)
                assertEquals(if(orientation>=5)120 else 80,ref.height)
                val thumbnail = decodeImageThumbnail(context,Uri.fromFile(source),120)!!
                verify(thumbnail,orientation,"thumbnail"); thumbnail.recycle()
                val project = ProjectCreator().create("EXIF",listOf(ref),canvas=CanvasSettings(width=ref.width!!,height=ref.height!!))
                val short = project.copy(timeline=project.timeline.copy(videoClips=project.timeline.videoClips.map { it.copy(photoDurationMicros=500000) }))
                val legacy = MediaMetadataExtractor().resolveSpeedAudio(context,short.copy(mediaRefs=listOf(ref.copy(photoOrientation=null,width=120,height=80))))
                assertEquals(ref,legacy.mediaRefs.single())
                val result = ExportEngine(context,CompositionBuilder()).export(short,ExportRequest(short.id,output,ExportResolution.RES_720P,FrameRate.FPS_30,ExportQuality.High)) {}
                assertTrue("orientation=$orientation $result",result.isSuccess)
                val retriever = MediaMetadataRetriever()
                try { retriever.setDataSource(output.absolutePath); val frame=retriever.getFrameAtTime(200000,MediaMetadataRetriever.OPTION_CLOSEST)!!
                    verify(frame,orientation,"export"); frame.recycle()
                } finally { retriever.release() }
                output.delete()
            }
        } finally { source.delete(); output.delete() }
    }
}
