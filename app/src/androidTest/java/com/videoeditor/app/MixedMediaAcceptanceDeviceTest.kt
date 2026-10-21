package com.videoeditor.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.database.VideoEditorDatabase
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.database.storage.ProjectDocumentStorage
import com.videoeditor.core.media.MediaMetadataExtractor
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.*
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import com.videoeditor.editing.commands.TimelineCommands
import com.videoeditor.editing.history.EditHistory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real mixed-source composition, commands, Room/document reopen and both MP4 sizes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MixedMediaAcceptanceDeviceTest {
    @Test fun three_videos_two_photos_two_audio_tracks_voiceover_and_multilingual_titles_export() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "mixed-acceptance").apply { mkdirs() }
        val extractor = MediaMetadataExtractor()
        fun asset(name: String): MediaRef {
            val file = File(directory, name)
            instrumentation.context.assets.open(name).use { source -> file.outputStream().use(source::copyTo) }
            return extractor.extract(context, Uri.fromFile(file)).getOrNull()!!
        }
        val videos = listOf("clip1_720p_6s.mp4", "clip2_480p_5s.mp4", "waterfall.mp4").map(::asset)
        val music = asset("music_20s.m4a")
        // Capture genuine microphone input through Android's recorder. The
        // emulator may provide silence; this verifies recording and mixing,
        // without claiming a human vocal performance on unavailable hardware.
        val microphone = android.Manifest.permission.RECORD_AUDIO
        if (context.checkSelfPermission(microphone) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, microphone)
        val voiceFile = File(directory, "recorded-voiceover.m4a")
        @Suppress("DEPRECATION") val recorder = android.media.MediaRecorder()
        try {
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(48000); recorder.setAudioEncodingBitRate(192000)
            recorder.setOutputFile(voiceFile.absolutePath); recorder.prepare(); recorder.start()
            Thread.sleep(1100); recorder.stop()
        } finally { recorder.release() }
        val voiceover = extractor.extract(context, Uri.fromFile(voiceFile)).getOrNull()!!
        assertTrue("Recorded voiceover has no duration", (voiceover.durationMicros ?: 0) > 500_000)
        // Independently generated stills are identifiable through their two
        // contrasting halves even after cropping, rotation and reordering.
        val photos = listOf(Color.CYAN to Color.BLUE, Color.YELLOW to Color.RED).mapIndexed { index, colors ->
            val file = File(directory, "photo-$index.png")
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(colors.first)
            canvas.drawRect(320f, 0f, 640f, 480f, android.graphics.Paint().apply { color = colors.second })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            extractor.extract(context, Uri.fromFile(file)).getOrNull()!!
        }
        val initial = ProjectCreator().create("Mixed pipeline", videos + photos, canvas = CanvasSettings(aspectRatio = AspectRatioPreset.Square1x1, width = 720, height = 720))
        var cursor = 0L
        val clips = initial.timeline.videoClips.mapIndexed { index, clip ->
            clip.copy(sourceEndMicros = if (clip.isPhoto) clip.sourceEndMicros else 1_500_000,
                photoDurationMicros = if (clip.isPhoto) 800_000 else null,
                playbackSpeed = when(index) { 0 -> 1.5f; 1 -> 2f; else -> 1f },
                timelineStartMicros = cursor, muted = true,
                filter = if (index == 2) FilterSettings("warm", .7f) else FilterSettings(),
                colorAdjustments = if (index == 2) ColorAdjustments(brightness = .15f, saturation = .2f) else ColorAdjustments(),
                transform = if (index == 3) VisualTransform(rotationDegrees = 90, flipVertical = true, crop = CropRect(.1f,.1f,.9f,.9f)) else VisualTransform())
                .also { cursor += it.timelineDurationMicros }
        }
        var project = initial.copy(mediaRefs = initial.mediaRefs + music + voiceover, timeline = Timeline(videoClips = clips,
            audioClips = listOf(AudioClip("music", music.id, sourceEndMicros = 2_000_000, timelineStartMicros = 250_000, fadeInMicros = 300_000, fadeOutMicros = 300_000),
                AudioClip("extracted", videos[0].id, sourceEndMicros = 1_000_000, timelineStartMicros = 1_000_000, volume = .4f),
                AudioClip("voiceover", voiceover.id, sourceEndMicros = voiceover.durationMicros!!,
                    timelineStartMicros = 1_800_000, fadeInMicros = 100_000, fadeOutMicros = 100_000)),
            textOverlays = listOf(TextOverlay("rtl", "پښتو اردو العربية\nEnglish 世界 😀", fontSizeSp = 24f, durationMicros = 2_000_000, hasShadow = true, enterAnimation = TextAnimation.FadeIn),
                TextOverlay("second", "Photos + motion", fontId = "serif", startMicros = 2_000_000, durationMicros = 3_000_000, positionY = .7f, rotationDegrees = 10f, hasOutline = true, enterAnimation = TextAnimation.SlideIn))))
        val history = EditHistory()
        val split = TimelineCommands.SplitClip(clips.first().id, 500_000)
        project = history.apply(project, split)
        assertEquals(6, project.timeline.videoClips.size)
        project = history.undo(project); assertEquals(5, project.timeline.videoClips.size)
        project = history.redo(project); assertEquals(6, project.timeline.videoClips.size)
        project = history.apply(project, TimelineCommands.MoveClip(project.timeline.videoClips.last().id, 2))
        val db = Room.inMemoryDatabaseBuilder(context, VideoEditorDatabase::class.java).build()
        val repository = ProjectRepository(db.projectDao(), ProjectDocumentStorage(context))
        val outputs = mutableListOf<File>()
        try {
            assertTrue(repository.save(project).isSuccess)
            val reopened = ProjectDocumentStorage(context).load(project.id).getOrNull()!!
            assertEquals(project, reopened)
            val engine = ExportEngine(context, CompositionBuilder())
            for (resolution in ExportResolution.entries) {
                val output = File(directory, "mixed-${resolution.name}.mp4"); outputs += output
                val result = engine.export(reopened, ExportRequest(project.id, output, resolution, FrameRate.FPS_30, ExportQuality.Standard)) {}
                assertTrue("Mixed export failed: $result", result.isSuccess)
                val tracks = MediaExtractor()
                try {
                    tracks.setDataSource(output.absolutePath)
                    val formats = (0 until tracks.trackCount).map(tracks::getTrackFormat)
                    assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "video/avc" })
                    assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
                } finally { tracks.release() }
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(output.absolutePath)
                    assertEquals(resolution.height, reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt())
                    assertEquals(resolution.height, reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt())
                    assertEquals(reopened.timeline.renderedDurationMicros / 1000.0, reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble(), 150.0)
                    for (clip in reopened.timeline.videoClips) {
                        val frame = reader.getFrameAtTime(clip.timelineStartMicros + clip.timelineDurationMicros / 2, MediaMetadataRetriever.OPTION_CLOSEST)!!
                        var bright = 0
                        for (y in 0 until frame.height step 20) for (x in 0 until frame.width step 20) {
                            val pixel = frame.getPixel(x,y)
                            if (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > 120) bright++
                        }
                        assertTrue("Black clip ${clip.id} at ${clip.timelineStartMicros}", bright > 100)
                        frame.recycle()
                    }
                } finally { reader.release() }
            }
        } finally {
            repository.deletePermanently(project.id); db.close(); outputs.forEach(File::delete); directory.deleteRecursively()
        }
    }
}
