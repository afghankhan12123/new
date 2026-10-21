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
class PipWorkflowUiTest {
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
                (2..7).flatMap { y -> (2..7).map { x -> screenshot.getPixel(
                    (bounds.left + bounds.width * x / 10).toInt().coerceIn(0, screenshot.width - 1),
                    (bounds.top + bounds.height * y / 10).toInt().coerceIn(0, screenshot.height - 1)) } }
            } finally { screenshot.recycle() }
        }
        ui.waitUntil(15000) { pixels().count { android.graphics.Color.red(it) +
            android.graphics.Color.green(it) + android.graphics.Color.blue(it) > 100 } >= 10 }
        android.util.Log.i("PreviewReturnPerf", "label=$label visibleFrameMs=${(android.os.SystemClock.elapsedRealtimeNanos() - returnStarted) / 1e6}")
        // Move into the first motion clip, away from a paused still and title edges.
        repeat(8) { editorTestAction(ui, "Previous clip boundary") }
        repeat(current.timeline.videoClips.indexOfFirst { !it.isPhoto }.coerceAtLeast(0)) {
            editorTestAction(ui, "Next clip boundary")
        }
        ui.waitUntil(15000) { pixels().count { android.graphics.Color.red(it) +
            android.graphics.Color.green(it) + android.graphics.Color.blue(it) > 100 } >= 10 }
        val before = pixels()
        val startPosition = readTimelineClock(ui)[0].toLong()
        val playStarted = android.os.SystemClock.elapsedRealtimeNanos()
        ui.onNodeWithContentDescription("Play/Pause").performClick()
        ui.waitUntil(15000) { pixels().zip(before).count { (a, b) ->
            kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) > 25 } >= 5 }
        android.util.Log.i("PreviewReturnPerf", "label=$label motionFrameMs=${(android.os.SystemClock.elapsedRealtimeNanos() - playStarted) / 1e6}")
        ui.waitUntil(15_000) {
            val sample = readTimelineClock(ui)
            sample[0].toLong() >= startPosition + 1_000_000 && sample[6].toBoolean()
        }
        val sample = readTimelineClock(ui)
        assertTrue("$label ruler/playhead is stale", kotlin.math.abs(sample[1].toLong() - sample[2].toLong()) < 30)
        ui.onNodeWithTag("playback-time").assertTextEquals(
            com.videoeditor.editing.timeline.RulerScale.formatTimecode(sample[1].toLong()))
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
        // A directory the test writes one fixture into per run (e.g. VideoEditorPiP)
        // accumulates, so the exact target can be offscreen and never visible in the
        // first few rows. Sweep the directory to each terminal before giving up: swipe
        // until the visible row set stops changing (the terminal is reached), then
        // reverse. The target is identified by its row title or the "Preview the file …"
        // label that DocumentsUI exposes, so no fixed grid position is assumed.
        var forward = true
        var lastTitles: List<String> = listOf()
        var stablePasses = 0
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val activeWindow = automation.rootInActiveWindow?.windowId
            val target = nativeNodes().lastOrNull { it.windowId == activeWindow && it.refresh() && it.isVisibleToUser &&
                (it.text?.toString()?.equals(label, ignoreCase = true) == true ||
                    it.contentDescription?.toString()?.equals(label, ignoreCase = true) == true ||
                    it.contentDescription?.toString() == "Preview the file $label" ||
                    (label == "Add (1)" && it.text?.toString() in listOf("Add", "Done"))) }
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
            if (label.startsWith("PiP overlay")) {
                // DocumentsUI's directory grid can expose offscreen children and may omit
                // ACTION_SCROLL_FORWARD, so swipe the actual visible dir_list bounds.
                val scroll = nativeNodes().firstOrNull { it.refresh() && it.isVisibleToUser &&
                    it.packageName?.toString()?.contains("documentsui") == true &&
                    it.viewIdResourceName?.endsWith("/dir_list") == true }
                val titles = nativeNodes().mapNotNull {
                    if (it.viewIdResourceName == "android:id/title") it.text?.toString() else null
                }
                val bounds = android.graphics.Rect()
                scroll?.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    if (titles == lastTitles) stablePasses++
                    else { lastTitles = titles; stablePasses = 0 }
                    // Two consecutive identical visible-row sets mean a terminal was
                    // reached, so flip direction and sweep to the other end.
                    if (stablePasses >= 2) {
                        forward = !forward
                        lastTitles = listOf()
                        stablePasses = 0
                    }
                    val from = bounds.top + bounds.height() * if (forward) .85f else .15f
                    val to = bounds.top + bounds.height() * if (forward) .2f else .8f
                    shell("input swipe ${bounds.centerX()} ${from.toInt()} ${bounds.centerX()} ${to.toInt()} 350")
                    Thread.sleep(550)
                } else Thread.sleep(100)
            } else Thread.sleep(100)
        }
        nativeNodes().forEach { android.util.Log.i("WorkflowNative", "${it.viewIdResourceName}: ${it.text} / ${it.contentDescription}") }
        error("Native action missing: $label")
    }
    private fun publish(collection: Uri, name: String, mime: String, index: Int, write: (java.io.OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (collection == MediaStore.Downloads.EXTERNAL_CONTENT_URI) "Download/VideoEditorPiP" else "DCIM/$pickerAlbum")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (!mime.startsWith("audio")) put("datetaken", stamp + index * 1000)
        }
        val uri = context.contentResolver.insert(collection, values)!!; ownedUris += uri
        context.contentResolver.openOutputStream(uri)!!.use(write)
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
    @Test fun native_pip_import_continuous_seek_reopen_export_and_return() {
        val run = InstrumentationRegistry.getArguments().getString("evidenceExportRun") ?: "pip-${System.currentTimeMillis()}"
        require(run.matches(Regex("[a-zA-Z0-9_-]+")))
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val preferences = dagger.hilt.android.EntryPointAccessors.fromApplication(context, AcceptancePreferences::class.java).preferences()
        val previous = runBlocking { preferences.preferences.first() }
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        val repository = ProjectRepository(db.projectDao(), ProjectDocumentStorage(context))
        val storage = ProjectDocumentStorage(context)
        val existing = runBlocking { repository.observeAll().first().map { it.id }.toSet() }
        val background = publish(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "PiP background $stamp.mp4", "video/mp4", 3) { output ->
            instrumentation.context.assets.open("pip_main_moving_audio.mp4").use { it.copyTo(output) }
        }
        val overlayName = "PiP overlay $stamp.mp4"
        publish(MediaStore.Downloads.EXTERNAL_CONTENT_URI, overlayName, "video/mp4", 4) { output ->
            instrumentation.context.assets.open("pip_overlay_moving_audio.mp4").use { it.copyTo(output) }
        }
        runBlocking { preferences.setAutosaveEnabled(true); preferences.setSaveToPhotos(false); preferences.setThemeMode("Dark") }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.onNodeWithTag("new-project").performClick(); action("Choose Media"); ui.waitForIdle()
            nativeClick("Albums"); nativeClick(pickerAlbum)
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            var target: AccessibilityNodeInfo? = null
            while (target == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                target = nativeNodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.startsWith("Video taken on") == true }
                Thread.sleep(100)
            }
            val bounds = android.graphics.Rect().also { checkNotNull(target).getBoundsInScreen(it) }
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}"); Thread.sleep(400)
            nativeClick("Add (1)")
            ui.waitUntil(15000) { ui.onAllNodesWithText("1 item selected").fetchSemanticsNodes().isNotEmpty() }
            action("Next")
            ui.waitUntil(15000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
            val row = runBlocking { repository.observeAll().first().single { it.id !in existing } }
            fun document() = storage.load(row.id).getOrNull()!!
            fun await(predicate: (Project) -> Boolean) = ui.waitUntil(15000) { predicate(document()) }
            assertEquals("Native picker chose an unrelated source", background.lastPathSegment, Uri.parse(document().mediaRefs.single().uri).lastPathSegment)
            capture("pip-background")
            action("PiP"); action("Add video or photo overlay")
            nativeClick("Show roots"); nativeClick("Downloads"); nativeClick("VideoEditorPiP"); nativeClick(overlayName)
            await { it.timeline.overlayClips.size == 1 }
            action("PiP")
            fun slider(label: String, value: Float) {
                val node = when (label) {
                    "Trim end seconds" -> {
                        action("Crop")
                        ui.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).onLast()
                    }
                    "Start seconds" -> {
                        action("Crop")
                        ui.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).onFirst()
                    }
                    "Horizontal position" -> {
                        action("Position")
                        ui.onNodeWithContentDescription("Expand controls").performClick()
                        ui.onNodeWithTag("pip-X position")
                    }
                    else -> {
                        action(if (label == "Opacity") "Opacity" else "Position")
                        ui.onNodeWithTag("pip-$label")
                    }
                }
                runCatching { node.performScrollTo() }
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(value) }
            }
            slider("Trim end seconds", 3f)
            await { it.timeline.overlayClips.single().timelineDurationMicros == 3_000_000L }
            slider("Start seconds", 1f)
            slider("Scale", .3f); slider("Horizontal position", .5f)
            action("Done")
            await { it.timeline.overlayClips.single().timelineStartMicros == 1_000_000L }
            fun clock() = readTimelineClock(ui)
            fun scrub(targetUs: Long) {
                val before = clock()
                val delta = (before[1].toLong() - targetUs) * before[4].toFloat()
                ui.onNodeWithTag("timeline").performTouchInput {
                    val start = if (delta > 0) width * .15f else width * .85f
                    swipe(Offset(start, 10f), Offset(start + delta, 10f), 450)
                }
                ui.waitUntil(15000) { kotlin.math.abs(clock()[0].toLong() - targetUs) < 100_000 }
            }
            scrub(2_000_000)
            // A ruler tap can clear selection. Select the actual overlay lane
            // before its direct-manipulation gesture, as in the consumer flow.
            ui.onNodeWithTag("pip-lane-${document().timeline.overlayClips.single().id}").performClick()
            action("Done")
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("pip-canvas-handles").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("pip-canvas-handles").performTouchInput { swipe(centerRight - Offset(width * .25f, 0f), centerRight - Offset(width * .25f - 30f, -20f), 300) }
            ui.onNodeWithTag("pip-canvas-handles").performTouchInput {
                val center = centerRight - Offset(width * .25f, 0f)
                pinch(start0 = center - Offset(35f, 0f), end0 = center - Offset(45f, 0f), start1 = center + Offset(35f, 0f), end1 = center + Offset(45f, 0f), durationMillis = 300)
            }
            await { it.timeline.overlayClips.single().transform.scale > .35f }
            capture("pip-positioned")
            scrub(0)
            ui.onNodeWithContentDescription("Play/Pause").performClick()
            for (time in listOf(1_300_000L, 2_500_000L, 4_400_000L)) {
                ui.waitUntil(15000) { clock()[0].toLong() >= time }
                val sample = clock()
                assertTrue("Timeline does not follow its fixed playhead", kotlin.math.abs(sample[1].toLong() - sample[2].toLong()) < 30)
                capture("pip-continuous-$time")
            }
            ui.waitUntil(15000) { clock()[1].toLong() == 6_000_000L && !clock()[6].toBoolean() }
            scrub(2_000_000); capture("pip-seek-backward")
            scrub(3_000_000); capture("pip-seek-forward")
            ui.onNodeWithContentDescription("Play/Pause").performClick(); Thread.sleep(500)
            ui.onNodeWithContentDescription("Play/Pause").performClick(); capture("pip-pause-resume")
            action("PiP"); slider("Opacity", .6f); action("Done")
            await { it.timeline.overlayClips.single().opacity == .6f }
            val saved = document().timeline.overlayClips
            storage.documentFile(row.id).copyTo(File(context.getExternalFilesDir(null), "pip-project-$run.json"), overwrite = true)
            ui.onNodeWithContentDescription("Close").performClick(); ui.onNodeWithTag("project-card-${row.id}").performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(saved, document().timeline.overlayClips)
            capture("pip-reopened")
            scrub(2_000_000)
            ui.onNodeWithContentDescription("Play/Pause").performClick(); Thread.sleep(700)
            ui.onNodeWithContentDescription("Play/Pause").performClick()
            capture("pip-reopened-playing")
            action("Export")
            // Navigation completes before the asynchronously loaded project exposes
            // export settings. Await that state rather than treating it as a picker failure.
            ui.waitUntil(15000) { ui.onAllNodes(hasText("1080p") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            action("1080p"); action("Export Video")
            ui.waitUntil(120000) { ui.onAllNodesWithText("Export Complete").fetchSemanticsNodes().isNotEmpty() }
            val output = File(context.filesDir, "exports").listFiles()!!.filter { it.name.startsWith("export_${row.id}_") && it.extension == "mp4" }.maxBy { it.lastModified() }
            val retriever = android.media.MediaMetadataRetriever().apply { setDataSource(output.path) }
            try {
                assertEquals("1080", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                for (position in listOf(500_000L, 2_000_000L, 3_000_000L, 4_500_000L)) {
                    val frame = retriever.getFrameAtTime(position, android.media.MediaMetadataRetriever.OPTION_CLOSEST)!!
                    val file = File(context.getExternalFilesDir(null), "pip-export-$position.png")
                    file.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    frame.recycle()
                }
            } finally { retriever.release() }
            val tracks = android.media.MediaExtractor().apply { setDataSource(output.path) }
            try { assertTrue((0 until tracks.trackCount).any { tracks.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }) }
            finally { tracks.release() }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "workflow-export-1080p.mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VideoEditorEvidence/$run/")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            resolver.openOutputStream(uri)!!.use { destination -> output.inputStream().use { it.copyTo(destination) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            ui.onNodeWithContentDescription("Play").performClick(); Thread.sleep(500)
            if (nativeNodes().any { it.text?.toString() == "Photos" }) {
                nativeClick("Photos"); if (nativeNodes().any { it.text?.toString()?.equals("Just once", true) == true }) nativeClick("Just once")
            }
            Thread.sleep(2500); capture("pip-exported-mp4-playing")
            shell("input keyevent KEYCODE_BACK")
            ui.waitUntil(15000) { ui.onAllNodesWithText("Export Complete").fetchSemanticsNodes().isNotEmpty() }
            action("Done"); scrub(2_000_000)
            ui.onNodeWithContentDescription("Play/Pause").performClick()
            ui.waitUntil(15000) { clock()[0].toLong() >= 2_500_000 && clock()[6].toBoolean() }
            capture("pip-export-return-playing")
            ui.onAllNodes(hasText("error from player", substring = true)).assertCountEquals(0)
            shell("mkdir -p /sdcard/Download/VideoEditorEvidence/$run")
            shell("echo ${row.id} > /sdcard/Download/VideoEditorEvidence/$run/project-id.txt")
        } catch (failure: Throwable) {
            runCatching { capture("pip-failure") }; throw failure
        } finally {
            scenario.close()
            runBlocking { preferences.setAutosaveEnabled(previous.autosaveEnabled); preferences.setSaveToPhotos(previous.saveToPhotos); preferences.setThemeMode(previous.themeMode) }
            db.close()
            // Retain the owned project/source grants for production replay and evidence.
        }
    }
}
