package com.videoeditor.app

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.database.VideoEditorDatabase
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.database.storage.ProjectDocumentStorage
import com.videoeditor.core.media.MediaMetadataExtractor
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.TextOverlay
import com.videoeditor.editing.ProjectCreator
import com.videoeditor.editing.timeline.RulerScale
import com.videoeditor.feature.editor.timeline.TimelineDiagnostics
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual activity, player clock, drawn timeline and SurfaceView pixels. No transport mocks. */
class TimelinePlaybackSyncUiTest {
    @get:Rule val frameTraceName = org.junit.rules.TestName()
    @org.junit.Before fun enableFrameTrace() {
        com.videoeditor.core.media.preview.PreviewPhaseTrace.clearFrameDeliveryTrace()
        com.videoeditor.core.media.preview.PreviewPhaseTrace.frameDeliveryDiagnostic = true
    }
    @org.junit.After fun disableFrameTrace() {
        com.videoeditor.core.media.preview.PreviewPhaseTrace.writeFrameDeliveryTrace(File(context.getExternalFilesDir(null),
            "preview-first-frame-traces/${frameTraceName.methodName}-${android.os.SystemClock.elapsedRealtimeNanos()}.trace"))
        com.videoeditor.core.media.preview.PreviewPhaseTrace.frameDeliveryDiagnostic = false
    }
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val directory = File(context.filesDir, "timeline-sync-${System.nanoTime()}")
    private val screenshotDirectory = "/sdcard/Download/VideoEditorEvidence/ne2211-${System.nanoTime()}"
    private val storage by lazy { ProjectDocumentStorage(context) }
    private var liveClock: (() -> List<String>)? = null
    private fun resetClockProbe() { liveClock = null }
    private data class Clock(val actual: Long, val displayed: Long, val anchor: Long,
        val scroll: Float, val zoom: Float, val duration: Long, val playing: Boolean)
    private fun clock(): Clock {
        // Acquire while paused, then sample without waiting for Espresso idle.
        val read = liveClock ?: timelineClockReader(ui).also { liveClock = it }
        val s = read()
        return Clock(s[0].toLong(), s[1].toLong(), s[2].toLong(), s[3].toFloat(),
            s[4].toFloat(), s[5].toLong(), s[6].toBoolean())
    }
    private fun aligned() {
        val c = clock()
        android.util.Log.i("TimelineSync", "aligned $c")
        assertTrue("ruler under fixed line disagrees with timer: $c", kotlin.math.abs(c.anchor - c.displayed) < 30)
        assertTrue("player disagrees with timer: $c", kotlin.math.abs(c.actual - c.displayed) < 150_000)
        assertEquals(c.displayed * c.zoom, c.scroll, .05f)
        // Timer is visible text, separately observed from the Canvas's state.
        val text = ui.onAllNodesWithTag("playback-time").fetchSemanticsNodes().single()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        val current = clock().displayed
        val parts = Regex("""(\d+):(\d{2})\.(\d)""").matchEntire(text)
            ?: error("Unexpected project timecode: $text")
        val observedTenths = (parts.groupValues[1].toLong() * 60 + parts.groupValues[2].toLong()) * 10 + parts.groupValues[3].toLong()
        assertTrue("visible timer stale: $text, $current", observedTenths in
            (current - 150_000).coerceAtLeast(0) / 100_000..current / 100_000)
    }
    private fun pixels(tag: String): List<Int> {
        val bounds = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val bitmap = automation.takeScreenshot() ?: error("No screenshot")
        return try { (1..18).filter { tag != "editor-preview" || it !in 7..12 }.flatMap { y -> (1..18).map { x -> bitmap.getPixel(
            (bounds.left + bounds.width * x / 20).toInt().coerceIn(0, bitmap.width - 1),
            (bounds.top + bounds.height * y / 20).toInt().coerceIn(0, bitmap.height - 1)) } } }
        finally { bitmap.recycle() }
    }
    private fun rulerPixels(): IntArray {
        val bounds = ui.onNodeWithTag("timeline").fetchSemanticsNode().boundsInWindow
        val bitmap = automation.takeScreenshot() ?: error("No screenshot")
        return try {
            // The first 55 physical pixels contain ruler labels/ticks, excluding
            // filmstrips: asynchronous asset appearance must not pass follow.
            val width = bounds.width.toInt()
            IntArray(width * 55).also {
                bitmap.getPixels(it, 0, width, bounds.left.toInt(), bounds.top.toInt(), width, 55)
            }
        } finally { bitmap.recycle() }
    }
    private fun captionHandlePixels(): Int {
        val bounds = ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInWindow
        val bitmap = automation.takeScreenshot() ?: error("No screenshot")
        return try {
            var count = 0
            for (y in bounds.top.toInt() until bounds.bottom.toInt() step 2)
                for (x in bounds.left.toInt() until bounds.right.toInt() step 2) {
                    val color = bitmap.getPixel(x, y)
                    // Current workspace handle color is #79E2D3.
                    if (android.graphics.Color.red(color) in 115..127 &&
                        android.graphics.Color.green(color) in 220..232 &&
                        android.graphics.Color.blue(color) in 205..217) count++
                }
            count
        } finally { bitmap.recycle() }
    }
    private fun capture(name: String) {
        automation.executeShellCommand("mkdir -p $screenshotDirectory").let { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        automation.executeShellCommand("screencap -p $screenshotDirectory/timeline-$name.png").let { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    private fun scrubTo(target: Long, expectedFrame: (() -> Boolean)? = null) {
        val before = clock()
        val delta = (before.displayed - target) * before.zoom
        ui.onNodeWithTag("timeline").performTouchInput {
            val start = if (delta > 0) width * .15f else width * .85f
            down(Offset(start, 15f))
            for (step in 1..40) moveTo(Offset(start + delta * step / 40, 15f), 16)
            // This checks a precise scrub landing. Hold before releasing so
            // velocity tracking does not turn the gesture into an inertial fling.
            advanceEventTime(300)
            moveTo(Offset(start + delta, 15f))
            up()
        }
        val releasedNanos = android.os.SystemClock.elapsedRealtimeNanos()
        ui.waitUntil(15_000) {
            kotlin.math.abs(clock().actual - target) < 250_000 && (expectedFrame?.invoke() ?: true)
        }
        android.util.Log.i("TimelineSeekPerf", "targetUs=$target sourcePixelsVerified=${expectedFrame != null} " +
            "inputReleaseToVerifiedMs=${(android.os.SystemClock.elapsedRealtimeNanos() - releasedNanos) / 1e6}")
        assertFalse("scrub should leave playback paused", clock().playing)
        aligned()
        val settled = clock()
        Thread.sleep(400)
        assertTrue("stale clock reset latest scrub", kotlin.math.abs(clock().displayed - settled.displayed) < 50_000)
    }
    @Test fun large_timeline_accessibility_reaches_last_clip_without_platform_action_overflow() {
        directory.mkdirs()
        val footage = File(directory, "waterfall.mp4").also { file ->
            instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use(input::copyTo) }
        }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(footage)).getOrNull()!!
        val project = ProjectCreator().create("Accessible 50 clips ${System.nanoTime()}", List(50) { ref })
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        val repository = ProjectRepository(db.projectDao(), storage)
        try {
            runBlocking { assertTrue(repository.save(project).isSuccess) }
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                ui.waitUntil(15_000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText(project.name).performClick()
                ui.waitUntil(15_000) { runCatching { clock().duration == project.timeline.renderedDurationMicros }.getOrDefault(false) }
                fun action(label: String) {
                    val actions = ui.onNodeWithTag("timeline").fetchSemanticsNode().config[
                        androidx.compose.ui.semantics.SemanticsActions.CustomActions]
                    ui.runOnIdle {
                        assertTrue(actions.size <= 32)
                        assertTrue(actions.single { it.label == label }.action())
                    }
                }
                repeat(50) { action("Select next visual clip") }
                // Ask Android for the node too: merely inspecting Compose semantics
                // did not catch the platform's custom-action overflow.
                assertNotNull(automation.rootInActiveWindow)
                assertEquals(0L, clock().displayed)
                assertEquals(project.timeline, runBlocking { repository.loadDocument(project.id).getOrNull()!! }.timeline)
                action("Trim start later by 0.1 seconds")
                ui.waitUntil(15_000) {
                    runBlocking { repository.loadDocument(project.id).getOrNull()!!.timeline.videoClips.last().sourceStartMicros == 100_000L }
                }
                val trimmed = runBlocking { repository.loadDocument(project.id).getOrNull()!! }
                assertEquals(project.timeline.videoClips.dropLast(1), trimmed.timeline.videoClips.dropLast(1))
                ui.onNodeWithContentDescription("Undo").performClick()
                ui.waitUntil(15_000) { runBlocking { repository.loadDocument(project.id).getOrNull()!!.timeline == project.timeline } }
            } finally { scenario.close() }
        } finally {
            runBlocking { repository.deletePermanently(project.id) }
            db.close(); directory.deleteRecursively()
        }
    }

    @Test fun continuous_playback_pause_scrub_zoom_end_and_replay_are_visible() {
        directory.mkdirs()
        fun source(name: String): File = File(directory, name).also { file ->
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
        }
        val footage = source("waterfall.mp4")
        val music = source("music_20s.m4a")
        val extractor = MediaMetadataExtractor()
        val refs = (1..3).map { extractor.extract(context, Uri.fromFile(footage)).getOrNull()!! }
        val audio = extractor.extract(context, Uri.fromFile(music)).getOrNull()!!
        val base = ProjectCreator().create("Timeline sync real footage ${System.nanoTime()}", refs)
        var cursor = 0L
        val clips = base.timeline.videoClips.map { clip ->
            clip.copy(playbackSpeed = .5f, timelineStartMicros = cursor).also { cursor += it.timelineDurationMicros }
        }
        val project = base.copy(mediaRefs = refs + audio, timeline = base.timeline.copy(videoClips = clips,
            textOverlays = listOf(TextOverlay("sync-title", "Synchronized timeline", startMicros = 3_000_000, durationMicros = cursor - 3_000_000)),
            audioClips = listOf(AudioClip("sync-audio", audio.id, sourceEndMicros = minOf(cursor, audio.durationMicros!!)))))
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        try {
            runBlocking { assertTrue(ProjectRepository(db.projectDao(), storage).save(project).isSuccess) }
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                ui.waitUntil(15_000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText(project.name).performClick()
                ui.waitUntil(15_000) { runCatching { clock().duration == cursor }.getOrDefault(false) }
                ui.waitUntil(15_000) { pixels("editor-preview").count { android.graphics.Color.green(it) > 40 } > 20 }
                editorTestAction(ui, "Fit timeline")
                assertEquals(0L, clock().displayed); aligned(); capture("start")
                val initialPreview = pixels("editor-preview")
                val initialTimeline = pixels("timeline")
                val initialRuler = rulerPixels()
                ui.onNodeWithContentDescription("Play/Pause").performClick()
                ui.waitUntil(15_000) { clock().actual > 1_500_000 }
                assertTrue(clock().scroll > 0)
                assertTrue("continuous observation waited until the end", clock().actual < 3_000_000)
                aligned()
                ui.waitUntil(15_000) { clock().actual >= 8_000_000 }
                assertTrue("must sample during playback", clock().actual in 8_000_000..10_000_000)
                aligned()
                capture("playing-eight-seconds")
                assertTrue("decoded preview is stationary", pixels("editor-preview").zip(initialPreview).count { it.first != it.second } > 20)
                assertTrue("drawn timeline is stationary", pixels("timeline").zip(initialTimeline).count { it.first != it.second } > 20)
                assertTrue("rendered ruler stayed stationary while the player advanced",
                    rulerPixels().zip(initialRuler.toTypedArray()).count { it.first != it.second } > 100)
                ui.onNodeWithContentDescription("Play/Pause").performClick(); Thread.sleep(400)
                val paused = clock(); capture("paused")
                Thread.sleep(700); assertEquals(paused, clock()); aligned()
                // Canvas tap now opens the text editor. Select through the
                // timeline's production accessibility action to inspect handles.
                val titleAction = ui.onNodeWithTag("timeline").fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsActions.CustomActions]
                    .single { it.label == "Select title 1: Synchronized timeline" }
                ui.runOnIdle { assertTrue(titleAction.action()) }
                ui.onNodeWithTag("text-apply").performClick()
                ui.waitUntil(10_000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
                ui.waitUntil(10_000) { captionHandlePixels() > 100 }
                capture("text-selected-paused")
                ui.onNodeWithContentDescription("Play/Pause").performClick()
                ui.waitUntil(10_000) { clock().playing && captionHandlePixels() < 50 }
                capture("text-selected-playing")
                ui.onNodeWithContentDescription("Play/Pause").performClick()
                ui.waitUntil(10_000) { !clock().playing && captionHandlePixels() > 100 }
                capture("text-selection-restored")
                scrubTo(2_000_000)
                ui.waitUntil(10_000) { captionHandlePixels() < 50 }
                capture("scrub-back")
                scrubTo(clips[1].timelineStartMicros + 700_000); capture("scrub-forward-boundary")
                val zoomTime = clock().displayed
                ui.onNodeWithTag("timeline").performTouchInput {
                    down(0, Offset(width * .4f, 70f)); down(1, Offset(width * .6f, 70f))
                    moveTo(0, Offset(width * .2f, 70f), 350); moveTo(1, Offset(width * .8f, 70f), 350)
                    up(0); up(1)
                }
                assertTrue(clock().zoom > paused.zoom)
                assertTrue(kotlin.math.abs(clock().displayed - zoomTime) < 50_000); aligned(); capture("pinch-zoom")
                editorTestAction(ui, "Zoom out timeline"); aligned()
                ui.onNodeWithContentDescription("Play/Pause").performClick()
                ui.waitUntil(15_000) {
                    val c = clock()
                    c.displayed > zoomTime + 1_000_000
                }; aligned(); capture("resumed")
                ui.waitUntil(30_000) { clock().displayed == cursor && !clock().playing }
                aligned(); capture("end")
                // Acquire once while paused. Reacquiring Compose semantics on
                // every playback sample can wait for idle beyond the early window.
                val replayClock = timelineClockReader(ui)
                ui.onNodeWithContentDescription("Play/Pause").performClick()
                ui.waitUntil(15_000) {
                    val sample = replayClock()
                    sample[1].toLong() in 100_000..2_000_000 && sample[6].toBoolean()
                }
                aligned(); capture("replay")
                ui.onAllNodes(hasText("error from player", substring = true) or hasText("Preview playback", substring = true)).assertCountEquals(0)
            } catch (error: Throwable) {
                runCatching { android.util.Log.e("TimelineSync", "failure ${clock()}"); capture("failure") }
                throw error
            } finally { scenario.close() }
        } finally {
            runBlocking { ProjectRepository(db.projectDao(), storage).deletePermanently(project.id) }
            db.close(); directory.deleteRecursively()
        }
    }
    @Test fun home_open_and_reopen_present_source_pixels_for_video_photo_and_layers() {
        directory.mkdirs()
        val video = File(directory, "marked.mp4")
        instrumentation.context.assets.open("transition_identity_0.mp4").use { input -> video.outputStream().use(input::copyTo) }
        // Independently decoded frame-index references. NE2211's time-based
        // retriever returned marker 90 for time zero in this fixture.
        val first = instrumentation.context.assets.open("preview_first_frame_0.png").use(android.graphics.BitmapFactory::decodeStream)
        val oneSecond = instrumentation.context.assets.open("preview_first_frame_30.png").use(android.graphics.BitmapFactory::decodeStream)
        val photo = File(directory, "photo.png").also { file -> file.outputStream().use { first.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        val v = MediaMetadataExtractor().extract(context,Uri.fromFile(video)).getOrNull()!!
        val p = MediaMetadataExtractor().extract(context,Uri.fromFile(photo)).getOrNull()!!
        val db = Room.databaseBuilder(context,VideoEditorDatabase::class.java,VideoEditorDatabase.NAME).build()
        val repo = ProjectRepository(db.projectDao(),storage)
        val owned = mutableListOf<String>()
        fun matches(expected: android.graphics.Bitmap): Boolean {
            val bounds = ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInWindow
            val shot = automation.takeScreenshot() ?: return false
            return try {
                val scale = minOf(bounds.width/expected.width,bounds.height/expected.height)
                val left = bounds.left+(bounds.width-expected.width*scale)/2
                val top = bounds.top+(bounds.height-expected.height*scale)/2
                var sum=0L
                // Upper source area remains clear of the deliberately lower overlays.
                for (y in 1..4) for (x in 1..8) {
                    val sx=expected.width*x/10; val sy=expected.height*y/10
                    val a=shot.getPixel((left+sx*scale).toInt(),(top+sy*scale).toInt())
                    val b=expected.getPixel(sx,sy)
                    for (shift in listOf(0,8,16)) sum+=kotlin.math.abs((a shr shift and 255)-(b shr shift and 255))
                }
                sum/96.0<20
            } finally { shot.recycle() }
        }
        try {
            for (kind in 0..4) {
                val base=ProjectCreator().create("First frame home $kind ${System.nanoTime()}",if(kind==1) listOf(p,v) else if(kind==2 || kind==4) listOf(v,v) else listOf(v))
                val project=when(kind) {
                    3 -> base.copy(timeline=base.timeline.copy(
                        textOverlays=listOf(com.videoeditor.core.model.TextOverlay("caption","FIRST FRAME",startMicros=0,durationMicros=3_000_000,positionY=.85f)),
                        stickers=listOf(com.videoeditor.core.model.StickerClip("sticker","premium_heart",timelineStartMicros=0,timelineDurationMicros=3_000_000,positionY=.85f)),
                        overlayClips=listOf(com.videoeditor.core.model.OverlayClip("pip",v.id,sourceEndMicros=3_000_000,timelineDurationMicros=3_000_000,muted=true,
                            transform=com.videoeditor.core.model.VisualTransform(scale=.2f,offsetX=.65f,offsetY=.65f)))))
                    4 -> { val a=base.timeline.videoClips[0].copy(sourceStartMicros=1_000_000,sourceEndMicros=1_500_000)
                        val b=base.timeline.videoClips[1].copy(sourceStartMicros=1_000_000,sourceEndMicros=3_000_000,timelineStartMicros=500_000)
                        base.copy(timeline=base.timeline.copy(videoClips=listOf(a,b),transitions=listOf(com.videoeditor.core.model.Transition("initial",a.id,b.id,1_000_000)))) }
                    else -> base
                }
                owned+=project.id
                runBlocking { assertTrue(repo.save(project).isSuccess) }
                val scenario=ActivityScenario.launch(MainActivity::class.java)
                try {
                    repeat(2) { opening ->
                        resetClockProbe()
                        ui.waitUntil(15_000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
                        val start=android.os.SystemClock.elapsedRealtimeNanos()
                        ui.onNodeWithText(project.name).performClick()
                        ui.waitUntil(15_000) { runCatching { clock().duration==project.timeline.renderedDurationMicros && matches(if(kind==4) oneSecond else first) }.getOrDefault(false) }
                        val pixels=android.os.SystemClock.elapsedRealtimeNanos()
                        ui.waitUntil(15_000) { ui.onAllNodesWithText("Loading preview…").fetchSemanticsNodes().isEmpty() }
                        android.util.Log.i("HomeFirstFramePerf","kind=$kind opening=$opening requestBootNs=$start pixelsBootNs=$pixels visibleMs=${(pixels-start)/1e6} spinnerDismissedBootNs=${android.os.SystemClock.elapsedRealtimeNanos()}")
                        if (kind in listOf(0,2,3)) {
                            editorTestAction(ui,"Fit timeline")
                            scrubTo(1_000_000) { matches(oneSecond) }
                            scrubTo(0) { matches(first) }
                            ui.onNodeWithContentDescription("Play/Pause").performClick()
                            ui.waitUntil(15_000) { clock().playing && clock().displayed>250_000 }
                            ui.onNodeWithContentDescription("Play/Pause").performClick()
                            ui.waitUntil(15_000) { !clock().playing }
                        }
                        ui.onNodeWithContentDescription("Close").performClick()
                    }
                } finally { scenario.close() }
                runBlocking { repo.deletePermanently(project.id) }; owned.remove(project.id)
            }
        } finally {
            runBlocking { owned.forEach { repo.deletePermanently(it) } }
            first.recycle(); oneSecond.recycle(); db.close(); directory.deleteRecursively()
        }
    }

    @Test fun touch_scrub_reconciles_trimmed_speed_video_photo_and_video_frames() {
        directory.mkdirs()
        fun source(name: String) = File(directory, name).also { file ->
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
        }
        val video = source("waterfall.mp4")
        val bars = source("clip2_480p_5s.mp4")
        val photo = File(directory, "photo.png")
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(bars.absolutePath)
        val expected = retriever.getFrameAtTime(0)!!
        photo.outputStream().use { expected.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        retriever.release()
        val refs = listOf(video, photo, video).map { MediaMetadataExtractor().extract(context, Uri.fromFile(it)).getOrNull()!! }
        val base = ProjectCreator().create("Photo timeline sync ${System.nanoTime()}", refs)
        var cursor = 0L
        val clips = base.timeline.videoClips.mapIndexed { index, clip ->
            val trimmed = if (index == 0) clip.copy(sourceStartMicros = 500_000, sourceEndMicros = 2_500_000, playbackSpeed = 1.5f) else clip
            trimmed.copy(timelineStartMicros = cursor).also { cursor += it.timelineDurationMicros }
        }
        val project = base.copy(timeline = base.timeline.copy(videoClips = clips))
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        fun matchesPhoto(): Boolean {
            val bounds = ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInWindow
            val screenshot = automation.takeScreenshot() ?: return false
            return try {
                val canvasScale = minOf(bounds.width / project.canvas.width, bounds.height / project.canvas.height)
                val scale = minOf(project.canvas.width * canvasScale / expected.width,
                    project.canvas.height * canvasScale / expected.height)
                val left = bounds.left + (bounds.width - expected.width * scale) / 2
                val top = bounds.top + (bounds.height - expected.height * scale) / 2
                var error = 0L
                for (y in 1..8) for (x in 1..8) {
                    val px = expected.width * x / 10; val py = expected.height * y / 10
                    val actual = screenshot.getPixel((left + px * scale).toInt(), (top + py * scale).toInt())
                    val target = expected.getPixel(px, py)
                    for (shift in listOf(0, 8, 16)) error += kotlin.math.abs((actual shr shift and 255) - (target shr shift and 255))
                }
                error / 192.0 < 20
            } finally { screenshot.recycle() }
        }
        try {
            runBlocking { assertTrue(ProjectRepository(db.projectDao(), storage).save(project).isSuccess) }
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                ui.waitUntil(15_000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText(project.name).performClick()
                ui.waitUntil(15_000) { runCatching { clock().duration == cursor }.getOrDefault(false) }
                editorTestAction(ui, "Fit timeline")
                val photoTime = clips[1].timelineStartMicros + 700_000
                scrubTo(photoTime, ::matchesPhoto)
                ui.waitUntil(15_000) { matchesPhoto() }; capture("photo-forward")
                scrubTo(clips[2].timelineStartMicros + 700_000) { !matchesPhoto() }
                ui.waitUntil(15_000) { !matchesPhoto() }; capture("video-after-photo")
                scrubTo(photoTime, ::matchesPhoto)
                ui.waitUntil(15_000) { matchesPhoto() }; capture("photo-backward")
                val timeBefore = clock().displayed
                resetClockProbe()
                scenario.recreate()
                ui.waitUntil(15_000) { runCatching { kotlin.math.abs(clock().displayed - timeBefore) < 50_000 && matchesPhoto() }.getOrDefault(false) }
                aligned()
                for (orientation in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                    scenario.onActivity { it.requestedOrientation = orientation }
                    ui.waitUntil(15_000) { context.resources.configuration.orientation == if (orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT }
                    resetClockProbe()
                    ui.waitUntil(15_000) { matchesPhoto() }; aligned()
                }
                ui.onNodeWithContentDescription("Fullscreen preview").performClick()
                ui.onNodeWithText("Tap to exit fullscreen").performClick()
                resetClockProbe()
                ui.waitUntil(15_000) { matchesPhoto() }; aligned()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                resetClockProbe()
                ui.waitUntil(15_000) { matchesPhoto() }; aligned()
                scrubTo(600_000) { !matchesPhoto() }
                ui.waitUntil(15_000) { !matchesPhoto() }; capture("trimmed-speed-video-back")
                editorTestAction(ui, "Zoom in timeline"); aligned()
                ui.onNodeWithContentDescription("Play/Pause").performClick()
                ui.waitUntil(15_000) { clock().displayed >= photoTime && clock().playing }
                ui.waitUntil(15_000) { matchesPhoto() }; aligned()
            } finally { scenario.close() }
        } finally {
            runBlocking { ProjectRepository(db.projectDao(), storage).deletePermanently(project.id) }
            expected.recycle(); db.close(); directory.deleteRecursively()
        }
    }

}
