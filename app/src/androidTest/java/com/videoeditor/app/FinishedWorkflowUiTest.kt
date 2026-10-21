package com.videoeditor.app

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
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
import com.videoeditor.core.model.Project
import com.videoeditor.editing.timeline.TimelineGeometry
import com.videoeditor.editing.timeline.TimelineLayout
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real picker grants, UI edits, recorder, persistence and destination actions. Suitable for screen recording. */
class FinishedWorkflowUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val ownedUris = mutableListOf<Uri>()
    private val stamp = System.currentTimeMillis()
    // This emulator's picker exposes only the predefined Camera/Videos/Downloads
    // collections. Put owned visuals in Camera and retain exact returned-URI checks.
    private val pickerAlbum = "Camera"
    private fun shell(command: String) = automation.executeShellCommand(command).let {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
    }
    private fun capture(label: String) {
        ui.waitForIdle(); Thread.sleep(600)
        shell("mkdir -p /sdcard/Download/VideoEditorEvidence")
        shell("touch /sdcard/Download/VideoEditorEvidence/.nomedia")
        shell("screencap -p /sdcard/Download/VideoEditorEvidence/release-workflow-$label.png")
    }
    /** Samples the actual SurfaceView pixels, not transport state or thumbnails. */
    private fun assertPreviewPlayback(label: String, current: Project) {
        val returnStarted = android.os.SystemClock.elapsedRealtimeNanos()
        ui.waitUntil(15000) {
            runCatching {
                ui.onNodeWithTag("editor-preview").assertIsDisplayed()
                ui.onNodeWithContentDescription("Play/Pause").assertIsEnabled()
                ui.onAllNodes(hasText("Preview playback", substring = true) or
                    hasText("error from player", substring = true)).fetchSemanticsNodes().isEmpty()
            }.getOrDefault(false)
        }
        fun pixels(): List<Int> {
            val bounds = ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInWindow
            val screenshot = automation.takeScreenshot() ?: error("Cannot capture preview")
            return try {
                // A rotated/scaled source can occupy a small valid canvas region.
                // Sample its interior densely so black letterboxing cannot hide it.
                (1..23).flatMap { y -> (1..23).map { x -> screenshot.getPixel(
                    (bounds.left + bounds.width * x / 24).toInt().coerceIn(0, screenshot.width - 1),
                    (bounds.top + bounds.height * y / 24).toInt().coerceIn(0, screenshot.height - 1)) } }
            } finally { screenshot.recycle() }
        }
        try {
            ui.waitUntil(15000) { pixels().count { android.graphics.Color.red(it) +
                android.graphics.Color.green(it) + android.graphics.Color.blue(it) > 100 } >= 10 }
        } catch (failure: Throwable) {
            capture("$label-no-visible-frame")
            android.util.Log.e("PreviewReturnPerf", "label=$label clock=${readTimelineClock(ui)}")
            throw failure
        }
        android.util.Log.i("PreviewReturnPerf", "label=$label visibleFrameMs=${(android.os.SystemClock.elapsedRealtimeNanos() - returnStarted) / 1e6}")
        // Move into the first motion clip, away from a paused still and title edges.
        val clock = timelineClockReader(ui)
        repeat(8) { action("Previous clip boundary") }
        repeat(current.timeline.videoClips.indexOfFirst { !it.isPhoto }.coerceAtLeast(0)) {
            action("Next clip boundary")
        }
        // Boundary actions can queue a seek while a returning graph prepares.
        // A visible old frame does not establish that the seek has completed.
        val motionStart = current.timeline.videoClips.firstOrNull { !it.isPhoto }?.timelineStartMicros ?: 0L
        ui.waitUntil(15_000) {
            kotlin.math.abs(clock()[0].toLong() - motionStart) < 1_000
        }
        ui.waitUntil(15000) { pixels().count { android.graphics.Color.red(it) +
            android.graphics.Color.green(it) + android.graphics.Color.blue(it) > 100 } >= 10 }
        val before = pixels()
        val startPosition = clock()[0].toLong()
        val playStarted = android.os.SystemClock.elapsedRealtimeNanos()
        ui.onNodeWithContentDescription("Play/Pause").performClick()
        ui.waitUntil(15000) { pixels().zip(before).count { (a, b) ->
            kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) > 25 } >= 5 }
        android.util.Log.i("PreviewReturnPerf", "label=$label motionFrameMs=${(android.os.SystemClock.elapsedRealtimeNanos() - playStarted) / 1e6}")
        ui.waitUntil(15_000) {
            val sample = clock()
            sample[0].toLong() >= startPosition + 1_000_000 && sample[6].toBoolean()
        }
        val sample = clock()
        assertTrue("$label ruler/playhead is stale", kotlin.math.abs(sample[1].toLong() - sample[2].toLong()) < 30)
        val beforeText = clock()[1].toLong()
        val displayedText = ui.onNodeWithTag("playback-time").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        val afterText = clock()[1].toLong()
        val parts = Regex("""(\d+):(\d{2})\.(\d)""").matchEntire(displayedText)
        assertNotNull("Invalid project timecode: $displayedText", parts)
        val (minutes, seconds, tenths) = parts!!.destructured
        val displayedMicros = (minutes.toLong() * 60 + seconds.toLong()) * 1_000_000 + tenths.toLong() * 100_000
        assertTrue("$label timer $displayedMicros outside live-clock interval [$beforeText, $afterText]",
            displayedMicros in (beforeText - 150_000)..afterText)
        capture("$label-playing")
        ui.onNodeWithContentDescription("Play/Pause").performClick()
        ui.onAllNodes(hasText("error from player", substring = true)).assertCountEquals(0)
    }
    private fun action(label: String) {
        editorTestAction(ui, label)
    }

    private fun nativeNodes(): List<AccessibilityNodeInfo> {
        fun collect(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
            listOf(node) + (0 until node.childCount).flatMap { collect(node.getChild(it)) }
        val roots = (listOf(automation.rootInActiveWindow) + automation.windows.map { it.root }).filterNotNull().distinctBy { it.windowId }
        return roots.flatMap { collect(it) }
    }
    private fun nativeClick(label: String) {
        runCatching { automation.waitForIdle(500, 10000) }
        Thread.sleep(500)
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        var scrollForward = true
        var scrollAttempts = 0
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val activeWindow = automation.rootInActiveWindow?.windowId
            val target = nativeNodes().lastOrNull { it.windowId == activeWindow && it.refresh() && it.isVisibleToUser && (it.text?.toString()?.equals(label, ignoreCase = true) == true || it.contentDescription?.toString()?.equals(label, ignoreCase = true) == true ||
                (label == "Add (5)" && it.text?.toString() in listOf("Add", "Done"))) }
            if (target != null) {
                val bounds = android.graphics.Rect().also { target.getBoundsInScreen(it) }
                if (!bounds.isEmpty) {
                    shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                    Thread.sleep(400)
                    return
                }
            }
            var node = target
            while (node != null) {
                if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                node = node.parent
            }
            if (label.startsWith("Workflow music")) {
                val scroll = nativeNodes().firstOrNull { it.refresh() && it.isVisibleToUser &&
                    it.packageName?.toString()?.contains("documentsui") == true && it.viewIdResourceName?.endsWith("/dir_list") == true }
                val bounds = android.graphics.Rect()
                scroll?.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    // DocumentsUI's grid can expose offscreen children and omit
                    // ACTION_SCROLL_FORWARD. Swipe its actual visible bounds.
                    val from = bounds.top + bounds.height() * if (scrollForward) .8f else .25f
                    val to = bounds.top + bounds.height() * if (scrollForward) .25f else .8f
                    shell("input swipe ${bounds.centerX()} ${from.toInt()} ${bounds.centerX()} ${to.toInt()} 350")
                    Thread.sleep(400)
                    if (++scrollAttempts % 6 == 0) scrollForward = !scrollForward
                } else Thread.sleep(100)
            } else Thread.sleep(100)
        }
        nativeNodes().forEach { android.util.Log.i("WorkflowNative", "${it.viewIdResourceName}: ${it.text} / ${it.contentDescription}") }
        error("Native action missing: $label")
    }
    private fun publish(collection: Uri, name: String, mime: String, index: Int, write: (java.io.OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (mime.startsWith("audio")) "Download/VideoEditorWorkflow" else "DCIM/$pickerAlbum")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (!mime.startsWith("audio")) put("datetaken", stamp + index * 1000)
        }
        val uri = context.contentResolver.insert(collection, values)!!; ownedUris += uri
        context.contentResolver.openOutputStream(uri)!!.use(write)
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
    @Test fun native_import_edit_record_reopen_and_two_exports() {
        val evidenceRun = InstrumentationRegistry.getArguments().getString("evidenceExportRun") ?: "suite-${System.currentTimeMillis()}"
        require(evidenceRun.matches(Regex("[a-zA-Z0-9_-]+")))
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
        val preferences = dagger.hilt.android.EntryPointAccessors.fromApplication(context, AcceptancePreferences::class.java).preferences()
        val previous = runBlocking { preferences.preferences.first() }
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        val storage = ProjectDocumentStorage(context)
        val repository = ProjectRepository(db.projectDao(), storage)
        val existing = runBlocking { repository.observeAll().first().map { it.id }.toSet() }
        var projectId: String? = null
        val temp = File(context.filesDir, "workflow-$stamp.mp4")
        val scenario: ActivityScenario<MainActivity>
        try {
            instrumentation.context.assets.open("waterfall.mp4").use { source -> temp.outputStream().use(source::copyTo) }
            val retriever = android.media.MediaMetadataRetriever().apply { setDataSource(temp.absolutePath) }
            try {
                for (index in 0..1) {
                    val frame = retriever.getFrameAtTime((index + 1) * 500000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST)!!
                    publish(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Workflow photo $index $stamp.png", "image/png", index) { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    frame.recycle()
                }
            } finally { retriever.release() }
            listOf("waterfall.mp4", "waterfall.mp4", "waterfall.mp4").forEachIndexed { index, asset ->
                publish(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Workflow video $index $stamp.mp4", "video/mp4", index + 2) { output -> instrumentation.context.assets.open(asset).use { it.copyTo(output) } }
            }
            val musicNames = (1..2).map { "Workflow music $it $stamp.m4a" }
            musicNames.forEachIndexed { index, name -> publish(MediaStore.Downloads.EXTERNAL_CONTENT_URI, name, "audio/mp4", index) { output -> instrumentation.context.assets.open(if (index == 0) "music_20s.m4a" else "music_alt_20s.m4a").use { it.copyTo(output) } } }
            runBlocking { preferences.setAutosaveEnabled(true); preferences.setSaveToPhotos(false); preferences.setThemeMode("Dark") }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                capture("home"); ui.onNodeWithTag("new-project").performClick(); action("Choose Media"); ui.waitForIdle()
                runCatching { automation.waitForIdle(500, 10000) }
                nativeClick("Albums")
                nativeClick(pickerAlbum)
                capture("native-picker")
                val deadline = android.os.SystemClock.elapsedRealtime() + 15000
                var thumbnails: List<AccessibilityNodeInfo> = emptyList()
                while (thumbnails.size < 5 && android.os.SystemClock.elapsedRealtime() < deadline) {
                    thumbnails = nativeNodes().filter { it.contentDescription?.toString()?.let { description ->
                        description.startsWith("Photo taken on") || description.startsWith("Video taken on") } == true }
                    Thread.sleep(100)
                }
                if (thumbnails.size < 5) { nativeNodes().forEach { android.util.Log.i("WorkflowNative", "${it.viewIdResourceName}: ${it.text} / ${it.contentDescription}") }; error("Picker did not expose five thumbnails") }
                repeat(5) { index ->
                    val fresh = nativeNodes().filter { it.contentDescription?.toString()?.let { description ->
                        description.startsWith("Photo taken on") || description.startsWith("Video taken on") } == true }
                        .take(5).sortedBy { if (it.contentDescription?.toString()?.startsWith("Photo taken on") == true) 0 else 1 }
                    val bounds = android.graphics.Rect().also { fresh[index].getBoundsInScreen(it) }
                    android.util.Log.i("WorkflowPicker", "item=$index bounds=$bounds visible=${fresh[index].isVisibleToUser} description=${fresh[index].contentDescription}")
                    val now = android.os.SystemClock.uptimeMillis()
                    for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to now, android.view.MotionEvent.ACTION_UP to now + 30)) {
                        val event = android.view.MotionEvent.obtain(now, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        assertTrue(automation.injectInputEvent(event, true)); event.recycle()
                    }
                    // The system picker animates its checkbox and footer in a
                    // separate window. Its transient counter is not reliably
                    // exposed; verify all five returned source IDs below.
                    Thread.sleep(400)
                }
                nativeClick("Add (5)")
                ui.waitUntil(15000) { ui.onAllNodesWithText("5 items selected").fetchSemanticsNodes().isNotEmpty() }
                capture("import-five"); action("Next")
                ui.waitUntil(15000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
                val row = runBlocking { repository.observeAll().first().single { it.id !in existing } }; projectId = row.id
                fun document(): Project = storage.load(row.id).getOrNull()!!
                fun await(predicate: (Project) -> Boolean) = ui.waitUntil(15000) { predicate(document()) }
                android.util.Log.i("WorkflowPhoto", "refs=${document().mediaRefs} canvas=${document().canvas} clips=${document().timeline.videoClips}")
                ui.waitUntil(15000) { runCatching { ui.onNodeWithContentDescription("Play/Pause").assertIsEnabled() }.isSuccess }
                ui.waitUntil(15000) {
                    val bounds = ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInWindow
                    val screenshot = automation.takeScreenshot()
                    if (screenshot == null) false else try {
                        var visiblePixels = 0
                        for (y in 2..7) for (x in 2..7) {
                            val pixel = screenshot.getPixel((bounds.left + bounds.width * x / 10).toInt().coerceIn(0, screenshot.width - 1),
                                (bounds.top + bounds.height * y / 10).toInt().coerceIn(0, screenshot.height - 1))
                            if (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel) > 100) visiblePixels++
                        }
                        visiblePixels >= 10
                    } finally { screenshot.recycle() }
                }
                capture("imported-editor")
                assertEquals(5, document().timeline.videoClips.size)
                assertTrue("Workflow must verify a photo-first paused preview", document().timeline.videoClips.first().isPhoto)
                val pickedIds = document().mediaRefs.map { Uri.parse(it.uri).lastPathSegment }.toSet()
                val ownedVisualIds = ownedUris.filter { !it.toString().contains("downloads") }.map { it.lastPathSegment }.toSet()
                android.util.Log.i("WorkflowSources", "picked=${document().mediaRefs.map { it.uri to it.displayName }} owned=$ownedVisualIds")
                assertEquals("Picker selected unrelated files", ownedVisualIds, pickedIds)
                assertEquals(3, document().mediaRefs.count { it.type == com.videoeditor.core.model.MediaType.Video })
                assertEquals(2, document().mediaRefs.count { it.type == com.videoeditor.core.model.MediaType.Photo })
                var clockReader = timelineClockReader(ui)
                fun timelineClock() = clockReader()
                assertEquals(0L, timelineClock()[0].toLong())
                ui.onNodeWithContentDescription("Play/Pause").performClick()
                var following = timelineClock()
                ui.waitUntil(15_000) {
                    following = timelineClock()
                    following[0].toLong() >= 8_000_000 && following[6].toBoolean()
                }
                assertTrue(following[6].toBoolean())
                assertTrue(kotlin.math.abs(following[1].toLong() - following[2].toLong()) < 30)
                capture("following-from-zero")
                ui.onNodeWithContentDescription("Play/Pause").performClick(); capture("paused")
                fun scrub(target: Long, label: String) {
                    val before = timelineClock()
                    val delta = (before[1].toLong() - target) * before[4].toFloat()
                    ui.onNodeWithTag("timeline").performTouchInput {
                        val start = if (delta > 0) width * .1f else width * .9f
                        swipe(Offset(start, 15f), Offset(start + delta, 15f), 650)
                    }
                    ui.waitUntil(15_000) { kotlin.math.abs(timelineClock()[0].toLong() - target) < 250_000 }
                    assertFalse(timelineClock()[6].toBoolean()); capture(label)
                }
                scrub(1_000_000, "manual-backward"); scrub(8_000_000, "manual-forward")
                // Stay inside the zoom limits so the inverse is reversible.
                val initialZoom = timelineClock()[4].toFloat()
                ui.onNodeWithTag("timeline").performTouchInput {
                    down(0, Offset(width * .4f, 70f)); down(1, Offset(width * .6f, 70f))
                    moveTo(0, Offset(width * .35f, 70f), 350); moveTo(1, Offset(width * .65f, 70f), 350)
                    up(0); up(1)
                }
                assertTrue(timelineClock()[4].toFloat() > initialZoom); capture("pinch-zoom")
                ui.onNodeWithTag("timeline").performTouchInput {
                    down(0, Offset(width * .35f, 70f)); down(1, Offset(width * .65f, 70f))
                    moveTo(0, Offset(width * .4f, 70f), 350); moveTo(1, Offset(width * .6f, 70f), 350)
                    up(0); up(1)
                }
                assertEquals(initialZoom, timelineClock()[4].toFloat(), initialZoom * .01f)
                repeat(8) { action("Previous clip boundary") }
                ui.onNodeWithTag("timeline").performTouchInput { click(Offset(width * .6f, height * (TimelineLayout.rowTop(1) + TimelineLayout.VIDEO_HEIGHT / 2) / TimelineGeometry.from(document().timeline).totalHeight)) }
                action("Crop"); action("Flip H"); action("Crop")
                ui.onNodeWithTag("crop-left").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.1f) }
                action("Apply"); action("Rotate")
                await { it.timeline.videoClips.any { clip -> clip.transform.flipHorizontal && clip.transform.rotationDegrees == 90 && clip.transform.crop?.left == .1f } }
                if (document().timeline.videoClips.first().isPhoto) {
                    action("Duration")
                    ui.onNodeWithTag("photo-duration-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1.5f) }
                    action("Apply"); await { it.timeline.videoClips.first().photoDurationMicros == 1500000L }
                }
                val firstVideo = document().timeline.videoClips.indexOfFirst { !it.isPhoto }
                repeat(firstVideo) { action("Next clip boundary") }
                ui.onNodeWithTag("timeline").performTouchInput { click(Offset(width * .55f, height * (TimelineLayout.rowTop(1) + TimelineLayout.VIDEO_HEIGHT / 2) / TimelineGeometry.from(document().timeline).totalHeight)) }
                val beforeSpeedDraft = document().timeline
                action("Speed"); action("2x"); action("Cancel")
                assertEquals("Cancel must not commit the speed draft", beforeSpeedDraft, document().timeline)
                action("Speed"); action("1.5x"); capture("speed-controls"); action("Apply")
                await { it.timeline.videoClips.any { clip -> clip.playbackSpeed == 1.5f } }
                ui.onNodeWithContentDescription("Undo").performClick()
                await { it.timeline == beforeSpeedDraft }
                ui.onNodeWithContentDescription("Redo").performClick()
                await { it.timeline.videoClips.any { clip -> clip.playbackSpeed == 1.5f } }
                action("Trim"); action("Cancel")
                val beforeAccessibleTrim = document().timeline.videoClips[firstVideo]
                val trimActions = ui.onNodeWithTag("timeline").fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsActions.CustomActions]
                ui.runOnIdle { assertTrue(trimActions.single { it.label == "Trim start later by 0.1 seconds" }.action()) }
                await { it.timeline.videoClips[firstVideo].sourceStartMicros > beforeAccessibleTrim.sourceStartMicros }
                ui.onNodeWithContentDescription("Undo").performClick()
                await { it.timeline.videoClips[firstVideo] == beforeAccessibleTrim }
                val trimmedId = document().timeline.videoClips.first { it.playbackSpeed == 1.5f }.id
                val oldEnd = document().timeline.videoClips.first { it.id == trimmedId }.sourceEndMicros
                repeat(document().timeline.videoClips.size + 1) { action("Previous clip boundary") }
                action("Fit timeline")
                val trimClip = document().timeline.videoClips.first { it.id == trimmedId }
                val fitDuration = document().timeline.renderedDurationMicros
                ui.onNodeWithTag("timeline").performTouchInput {
                    val y = height * (TimelineLayout.rowTop(1) + TimelineLayout.VIDEO_HEIGHT / 2) / TimelineGeometry.from(document().timeline).totalHeight
                    val end = width * (.5f + .45f * (trimClip.timelineStartMicros + trimClip.timelineDurationMicros).toFloat() / fitDuration)
                    val delta = width * .45f * trimClip.timelineDurationMicros.toFloat() / fitDuration * .25f
                    down(Offset(end, y)); moveTo(Offset(end - delta, y)); up()
                }
                await { it.timeline.videoClips.first { clip -> clip.id == trimmedId }.sourceEndMicros < oldEnd }
                capture("trimmed-video")
                action("Filters"); action("Warm"); action("Apply")
                action("Adjust"); ui.onNodeWithTag("grade-brightness").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.15f) }; action("Apply")
                repeat(document().timeline.videoClips.size + 1) { action("Previous clip boundary") }
                action("Fit timeline")
                val splitClip = document().timeline.videoClips.first { it.id == trimmedId }
                val splitFraction = .45f * (splitClip.timelineStartMicros + splitClip.timelineDurationMicros / 2).toFloat() / document().timeline.renderedDurationMicros
                ui.onNodeWithTag("timeline").performTouchInput {
                    val y = height * (TimelineLayout.rowTop(1) + TimelineLayout.VIDEO_HEIGHT / 2) / TimelineGeometry.from(document().timeline).totalHeight
                    click(Offset(width * (.5f + splitFraction), y))
                }
                ui.onNodeWithTag("timeline").performTouchInput { swipe(Offset(width * .85f, 8f), Offset(width * (.85f - splitFraction), 8f), 300) }
                action("Split"); action("Apply"); await { it.timeline.videoClips.size == 6 }
                ui.onNodeWithContentDescription("Undo").performClick(); await { it.timeline.videoClips.size == 5 }
                ui.onNodeWithContentDescription("Redo").performClick(); await { it.timeline.videoClips.size == 6 }
                repeat(document().timeline.videoClips.size + 1) { action("Previous clip boundary") }
                action("Fit timeline")
                val beforeOrder = document().timeline.videoClips.map { it.id }
                val dragged = document().timeline.videoClips.drop(1).maxBy { it.timelineDurationMicros }
                val total = document().timeline.renderedDurationMicros
                ui.onNodeWithTag("timeline").performTouchInput {
                    val y = height * (TimelineLayout.rowTop(1) + TimelineLayout.VIDEO_HEIGHT / 2) / TimelineGeometry.from(document().timeline).totalHeight
                    val x = width * (.5f + .45f * (dragged.timelineStartMicros + dragged.timelineDurationMicros / 2).toFloat() / total)
                    down(Offset(x, y)); advanceEventTime(800); moveTo(Offset(width * .49f, y)); up()
                }
                await { it.timeline.videoClips.first().id == dragged.id }
                capture("reordered-video")
                ui.onNodeWithContentDescription("Undo").performClick(); await { it.timeline.videoClips.map { clip -> clip.id } == beforeOrder }
                ui.onNodeWithContentDescription("Redo").performClick(); await { it.timeline.videoClips.first().id == dragged.id }
                repeat(2) { index ->
                    action("Text")
                    ui.onNode(hasSetTextAction()).performClick()
                    ui.waitUntil(5000) { ui.onAllNodesWithText("Font").fetchSemanticsNodes().isNotEmpty() }
                    ui.onNode(hasSetTextAction()).performTextReplacement(if (index == 0) "Adventure\nمرحبا 世界" else "Waterfall memories")
                    action("Font")
                    ui.waitUntil(5000) { ui.onAllNodesWithText("Serif").fetchSemanticsNodes().isNotEmpty() }
                    action("Serif"); action("Apply")
                }
                ui.onNodeWithTag("editor-preview").performTouchInput {
                    swipe(center, center + Offset(0f, height * .23f), 300)
                }
                await { it.timeline.textOverlays.last().positionY > .6f }
                capture("positioned-titles")
                for ((index, name) in musicNames.withIndex()) {
                    action("Audio"); action("Import music or sound"); ui.waitForIdle()
                    nativeClick("Show roots"); nativeClick("Downloads"); nativeClick("VideoEditorWorkflow"); nativeClick(name)
                    await { it.timeline.audioClips.size == index + 1 }
                    assertPreviewPlayback("audio-${index + 1}-return", document())
                }
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
                val beforeVoiceoverCancel = document().timeline
                action("Audio"); action("Voiceover"); action("Record")
                capture("voiceover-recording"); Thread.sleep(1500); action("Stop")
                capture("voiceover-stopped"); action("Cancel")
                assertEquals("Cancel must discard the recorded take", beforeVoiceoverCancel, document().timeline)
                assertPreviewPlayback("voiceover-cancel-return", document())
                action("Audio"); action("Voiceover"); action("Record"); Thread.sleep(1500)
                action("Stop"); action("Play take"); capture("voiceover-review"); action("Save Voiceover")
                await { it.timeline.audioClips.size == 3 }
                assertPreviewPlayback("voiceover-save-return", document())
                capture("edited-project")
                ui.onNodeWithContentDescription("Close").performClick(); ui.onNodeWithTag("project-card-${row.id}").performClick()
                ui.waitUntil(15000) { runCatching { ui.onNodeWithContentDescription("Play/Pause").assertIsEnabled() }.isSuccess }
                clockReader = timelineClockReader(ui)
                assertEquals(2, document().timeline.textOverlays.size); assertEquals(3, document().timeline.audioClips.size)
                assertPreviewPlayback("reopened", document())
                ui.onNodeWithContentDescription("Play/Pause").performClick()
                ui.waitUntil(45_000) {
                    val clock = timelineClock()
                    val endMicros = document().timeline.renderedDurationMicros
                    // Media3 exposes milliseconds; the published project clock keeps
                    // the exact microsecond endpoint even for fractional-speed clips.
                    clock[1].toLong() == endMicros && clock[5].toLong() == endMicros &&
                        kotlin.math.abs(clock[0].toLong() - endMicros) < 1_000 && !clock[6].toBoolean()
                }
                capture("playback-end")
                ui.onNodeWithContentDescription("Play/Pause").performClick()
                ui.waitUntil(15_000) { timelineClock()[0].toLong() in 100_000..2_000_000 && timelineClock()[6].toBoolean() }
                capture("replay"); ui.onNodeWithContentDescription("Play/Pause").performClick()
                for (resolution in listOf("720p", "1080p")) {
                    action("Export"); ui.waitUntil(15000) { ui.onAllNodesWithText(resolution).fetchSemanticsNodes().isNotEmpty() }; action(resolution); action("Export Video")
                    ui.waitUntil(120000) { ui.onAllNodesWithText("Export Complete").fetchSemanticsNodes().isNotEmpty() }
                    val output = File(context.filesDir, "exports").listFiles()!!.filter { it.name.startsWith("export_${row.id}_") && it.extension == "mp4" }.maxBy { it.lastModified() }
                    val reader = android.media.MediaMetadataRetriever().apply { setDataSource(output.absolutePath) }
                    try {
                        assertEquals(document().timeline.videoClips.sumOf { it.timelineDurationMicros } / 1000.0, reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble(), 200.0)
                        for (clip in document().timeline.videoClips) {
                            val frame = reader.getFrameAtTime(clip.timelineStartMicros + clip.timelineDurationMicros / 2, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                            assertNotNull(frame)
                            var brightness = 0L
                            for (y in 0..7) for (x in 0..7) {
                                val pixel = frame!!.getPixel(frame.width * x / 8, frame.height * y / 8)
                                brightness += android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
                            }
                            assertTrue("Unexpected black clip midpoint", brightness > 64 * 3 * 10)
                            frame!!.recycle()
                        }
                    } finally { reader.release() }
                    val tracks = android.media.MediaExtractor().apply { setDataSource(output.absolutePath) }
                    try {
                        val mimes = (0 until tracks.trackCount).map { tracks.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) }
                        assertTrue(mimes.contains("video/avc")); assertTrue(mimes.contains("audio/mp4a-latm"))
                    } finally { tracks.release() }
                    assertTrue(output.name.matches(Regex("export_[a-zA-Z0-9_-]+\\.mp4")))
                    val resolver = context.contentResolver
                    val evidenceName = "workflow-export-$resolution.mp4"
                    val evidencePath = "Download/VideoEditorEvidence/$evidenceRun/"
                    val downloads = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    val evidenceUri = resolver.insert(downloads, android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, evidenceName)
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, evidencePath)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    }) ?: error("Cannot create export evidence")
                    resolver.openOutputStream(evidenceUri)!!.use { destination -> output.inputStream().use { it.copyTo(destination) } }
                    assertEquals(1, resolver.update(evidenceUri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
                    val copiedBytes = resolver.openInputStream(evidenceUri)!!.use { it.readBytes() }
                    assertArrayEquals("Evidence copy differs from decoded export", output.readBytes(), copiedBytes)
                    capture("export-$resolution")
                    ui.onNodeWithContentDescription("Play").performClick()
                    ui.waitUntil(15000) { nativeNodes().any { it.packageName?.toString() != context.packageName && it.isVisibleToUser } }
                    if (nativeNodes().any { it.text?.toString() == "Photos" }) {
                        nativeClick("Photos")
                        if (nativeNodes().any { it.text?.toString()?.equals("Just once", ignoreCase = true) == true }) nativeClick("Just once")
                    }
                    Thread.sleep(3000)
                    // Android may present its first-use immersive-mode education over Photos.
                    if (nativeNodes().any { it.text?.toString() == "Got it" }) nativeClick("Got it")
                    capture("output-$resolution-playing")
                    shell("input keyevent KEYCODE_BACK")
                    // Native Back is asynchronous; await the returning Compose root.
                    ui.waitUntil(15000) { runCatching {
                        ui.onAllNodesWithText("Export Complete").fetchSemanticsNodes().isNotEmpty()
                    }.getOrDefault(false) }
                    if (resolution == "720p") {
                        action("Save to Photos"); ui.waitUntil(15000) { ui.onAllNodesWithText("Saved to Photos").fetchSemanticsNodes().isNotEmpty() }
                        action("Share")
                        ui.waitUntil(15000) { nativeNodes().any { it.text?.toString()?.startsWith("Sharing ") == true || it.text?.toString() == "Share video" } }
                        shell("input keyevent KEYCODE_BACK")
                        ui.waitUntil(15000) { runCatching { ui.onAllNodes(hasText("Save to Files") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
                        action("Save to Files"); ui.waitForIdle(); nativeClick("Save")
                        ui.waitUntil(15000) { runCatching { ui.onAllNodes(hasText("Saved to Files", substring = true)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
                    }
                    action("Done")
                    assertPreviewPlayback("export-$resolution-native-return", document())
                }
            } catch (failure: Throwable) {
                runCatching { capture("failure") }
                runCatching { ui.onRoot().printToLog("WorkflowCompose") }
                if (projectId != null) android.util.Log.i("WorkflowDocument", storage.load(projectId!!).toString())
                throw failure
            } finally { scenario.close() }
        } finally {
            runBlocking { if (projectId != null) repository.deletePermanently(projectId!!); preferences.setThemeMode(previous.themeMode); preferences.setAutosaveEnabled(previous.autosaveEnabled); preferences.setSaveToPhotos(previous.saveToPhotos) }
            ownedUris.forEach { context.contentResolver.delete(it, null, null) }; temp.delete(); db.close()
        }
    }
}
