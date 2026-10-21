package com.videoeditor.app

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.database.VideoEditorDatabase
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.database.storage.ProjectDocumentStorage
import com.videoeditor.core.media.MediaMetadataExtractor
import com.videoeditor.core.common.result.Result
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.TextOverlay
import com.videoeditor.editing.ProjectCreator
import com.videoeditor.editing.timeline.TimelineLayout
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

/** Ships the real activity, Hilt graph, media engine and persistence; no callback mocks. */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class EditorAcceptanceUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage by lazy { ProjectDocumentStorage(context) }
    private lateinit var projectId: String
    private val ownedProjectIds = mutableSetOf<String>()
    private lateinit var previousPreferences: com.videoeditor.core.datastore.UserPreferences
    @Before fun preservePreferences() {
        previousPreferences = runBlocking {
            dagger.hilt.android.EntryPointAccessors.fromApplication(context, AcceptancePreferences::class.java).preferences().preferences.first()
        }
    }
    @After fun removeOnlyOwnedFixturesAndRestorePreferences() {
        if (::projectId.isInitialized) ownedProjectIds += projectId
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        try {
            runBlocking {
                val repository = ProjectRepository(db.projectDao(), storage)
                ownedProjectIds.forEach { repository.deletePermanently(it) }
                val preferences = dagger.hilt.android.EntryPointAccessors.fromApplication(context, AcceptancePreferences::class.java).preferences()
                preferences.setSaveToPhotos(previousPreferences.saveToPhotos)
                preferences.setAutosaveEnabled(previousPreferences.autosaveEnabled)
            }
        } finally { db.close() }
    }
    private val acceptanceName = "UI acceptance ${System.currentTimeMillis()}"
    private fun document(): Project = (storage.load(projectId) as Result.Success).value
    private fun awaitDocument(predicate: (Project) -> Boolean) {
        ui.waitUntil(15_000) { predicate(document()) }
    }
    private fun action(text: String) {
        try { editorTestAction(ui, text) } catch (error: Throwable) {
            capture("unreachable-action-${text.replace(' ', '-')}")
            throw error
        }
    }

    private fun videoY(height: Int) = height *
        (TimelineLayout.rowTop(TimelineLayout.ROW_VIDEO) + TimelineLayout.rowHeight(TimelineLayout.ROW_VIDEO) / 2) /
        com.videoeditor.editing.timeline.TimelineGeometry.from(document().timeline).totalHeight

    private fun capture(label: String) {
        ui.waitForIdle()
        // Compose idle does not mean SurfaceFlinger's orientation transition has finished.
        Thread.sleep(800)
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/release-audit-$label.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test fun premium_workspace_panels_capture_real_frames_and_commit_only_on_apply() {
        val file = File(context.filesDir, "redesign-${System.nanoTime()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("waterfall.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(file)).getOrNull()!!
        val base = ProjectCreator().create("Mountain workspace ${System.nanoTime()}", listOf(ref, ref))
        val musicFile = File(context.filesDir, "redesign-music-${System.nanoTime()}.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("music_20s.m4a").use { input -> musicFile.outputStream().use(input::copyTo) }
        val music = MediaMetadataExtractor().extract(context, Uri.fromFile(musicFile)).getOrNull()!!
        val project = base.copy(mediaRefs = base.mediaRefs + music, canvas = base.canvas.copy(aspectRatio = com.videoeditor.core.model.AspectRatioPreset.Landscape16x9, width = 1920, height = 1080), timeline = base.timeline.copy(
            audioClips = listOf(AudioClip("redesign-audio", music.id, sourceEndMicros = 3_000_000, volume = .4f)),
            overlayClips = listOf(com.videoeditor.core.model.OverlayClip("redesign-pip", ref.id,
                timelineDurationMicros = 2_000_000, sourceEndMicros = 2_000_000, muted = true,
                transform = com.videoeditor.core.model.VisualTransform(scale = .25f, offsetX = .65f, offsetY = .6f))),
            textOverlays = listOf(
            TextOverlay("redesign-caption", "Adventure awaits", durationMicros = 3_000_000))))
        projectId = project.id
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        try { runBlocking { assertTrue(ProjectRepository(db.projectDao(), storage).save(project).isSuccess) } } finally { db.close() }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.waitUntil(15_000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
            action(project.name)
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
            fun readyCapture(label: String) {
                ui.mainClock.advanceTimeBy(300)
                ui.waitForIdle()
                // Draft preview rebuilds are asynchronous; let the replacement graph
                // publish a frame before checking its loading state and capturing.
                Thread.sleep(1500)
                ui.waitUntil(20_000) { ui.onAllNodesWithText("Loading preview…").fetchSemanticsNodes().isEmpty() }
                capture(label)
            }
            Thread.sleep(1800)
            readyCapture("redesign-main")
            action("Expand timeline"); readyCapture("redesign-expanded-timeline"); action("Compact timeline")
            fun selectVideo() {
                ui.onNodeWithTag("timeline").performTouchInput { click(Offset(width * .58f, videoY(height))) }
            }
            selectVideo()
            action("Trim"); readyCapture("redesign-trim")
            ui.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("0.5")
            action("Cancel")
            assertEquals(project.timeline.videoClips.first().sourceStartMicros, document().timeline.videoClips.first().sourceStartMicros)
            selectVideo(); action("Crop")
            ui.onNode(hasSetTextAction()).performTextReplacement("-")
            ui.onNodeWithText("Apply").assertIsNotEnabled()
            action("Reset")
            ui.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("0")))
            ui.onNode(hasSetTextAction()).performTextReplacement("12")
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").let {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
            }
            Thread.sleep(500)
            readyCapture("redesign-transform")
            val previewBounds = ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInRoot
            val outputHeight = minOf(previewBounds.height, previewBounds.width * 9f / 16f)
            val sourceAspect = ref.width!!.toFloat() / ref.height!!
            val sampleX = (previewBounds.center.x - outputHeight * sourceAspect * .45f).toInt()
            val sampleY = (previewBounds.center.y - outputHeight * .38f).toInt()
            val rotatedFrame = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            try {
                val pixel = rotatedFrame.getPixel(sampleX, sampleY)
                assertTrue("Rotation outline moved but actual source corner did not: pixel=$pixel at $sampleX,$sampleY",
                    android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel) < 70)
            } finally { rotatedFrame.recycle() }
            action("Reset")
            repeat(3) { action("+90°") }
            ui.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("-90")))
            action("Reset")
            ui.onNodeWithTag("transform-handles").performTouchInput { swipe(center, center + Offset(30f, -20f), 300) }
            assertEquals(0f, document().timeline.videoClips.first().transform.offsetX)
            action("Apply")
            awaitDocument { it.timeline.videoClips.any { clip -> clip.transform.offsetX != 0f } }
            ui.onNodeWithContentDescription("Undo").performClick()
            awaitDocument { it.timeline.videoClips.all { clip -> clip.transform.offsetX == 0f } }
            selectVideo(); action("Crop"); action("Crop")
            ui.onNodeWithTag("source-crop-handles").performScrollTo()
            ui.onNodeWithTag("source-crop-handles").performTouchInput {
                val aspect = requireNotNull(ref.width).toFloat() / requireNotNull(ref.height)
                val imageWidth = minOf(width.toFloat(), height * aspect)
                val left = (width - imageWidth) / 2
                down(Offset(left + 1f, 1f))
                moveTo(Offset(left + imageWidth * .15f, height * .1f), 300)
                up()
            }
            assertNull(document().timeline.videoClips.first().transform.crop)
            readyCapture("redesign-source-crop-handles")
            action("Apply")
            awaitDocument { it.timeline.videoClips.any { clip -> (clip.transform.crop?.left ?: 0f) > .05f } }
            ui.onNodeWithContentDescription("Undo").performClick()
            awaitDocument { it.timeline.videoClips.all { clip -> clip.transform.crop == null } }
            selectVideo(); action("Speed"); action("1.5x"); readyCapture("redesign-speed"); action("Cancel")
            assertEquals(1f, document().timeline.videoClips.first().playbackSpeed)
            selectVideo(); action("Volume"); readyCapture("redesign-volume"); action("Cancel")
            selectVideo(); action("Volume"); action("Mute clip"); action("Apply")
            awaitDocument { it.timeline.videoClips.any { clip -> clip.muted } }
            ui.onNodeWithContentDescription("Undo").performClick()
            awaitDocument { it.timeline.videoClips.none { clip -> clip.muted } }
            selectVideo(); action("Split"); readyCapture("redesign-split")
            ui.onNodeWithText("Apply").assertIsEnabled().performClick()
            awaitDocument { it.timeline.videoClips.size == 3 }
            ui.onNodeWithContentDescription("Undo").performClick()
            awaitDocument { it.timeline.videoClips.size == 2 }
            selectVideo(); action("Filters"); action("Warm"); readyCapture("redesign-filters"); action("Cancel")
            assertNull(document().timeline.videoClips.first().filter.filterId)
            selectVideo(); action("Adjust"); readyCapture("redesign-adjust"); action("Cancel")
            ui.onNodeWithContentDescription("Aspect Ratio").performClick()
            action("9:16"); Thread.sleep(1500); readyCapture("redesign-canvas-portrait-draft")
            action("Apply")
            awaitDocument { it.canvas.aspectRatio == com.videoeditor.core.model.AspectRatioPreset.Portrait9x16 }
            readyCapture("redesign-canvas-portrait-applied")
            ui.onNodeWithContentDescription("Undo").performClick()
            awaitDocument { it.canvas == project.canvas }
            readyCapture("redesign-canvas-undo")
            ui.onAllNodesWithText("Text").onLast().performScrollTo().performClick()
            readyCapture("redesign-text"); action("Cancel")
            action("PiP"); ui.onNodeWithTag("select-pip-redesign-pip").performClick(); readyCapture("redesign-overlay"); action("Done")
            selectVideo(); action("Transition"); readyCapture("redesign-transition"); action("Cancel")
            fun shell(command: String) {
                val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                Thread.sleep(1000)
            }
            try {
                for ((label, dimensions) in listOf("360" to "720x1280", "393" to "786x1704", "412" to "824x1784", "tablet" to "1600x2560", "landscape" to "1280x720")) {
                    shell("wm size $dimensions"); shell("wm density 320")
                    if (label == "landscape") readyCapture("redesign-landscape-main")
                    action("Speed")
                    ui.onNodeWithText("Apply").assertIsDisplayed()
                    ui.onNodeWithText("Cancel").assertIsDisplayed()
                    ui.onNodeWithContentDescription("Play/Pause").assertIsDisplayed()
                    assertTrue("Preview must retain space with a tool open", ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInRoot.height > 40f)
                    readyCapture("redesign-responsive-$label-speed")
                    if (label == "360") {
                        ui.onNodeWithContentDescription("Expand controls").performClick()
                        readyCapture("redesign-compact-preview-expanded-panel")
                        ui.onNodeWithText("Apply").assertIsDisplayed()
                        assertTrue(ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInRoot.height > 40f)
                    }
                    action("Cancel")
                }
                val previousFontScale = android.provider.Settings.System.getFloat(context.contentResolver, android.provider.Settings.System.FONT_SCALE, 1f)
                try {
                    shell("wm size 786x1704")
                    shell("settings put system font_scale 1.3")
                    action("Speed")
                    ui.onNodeWithText("Apply").assertIsDisplayed()
                    ui.onNodeWithText("Cancel").assertIsDisplayed()
                    ui.onNodeWithContentDescription("Play/Pause").assertIsDisplayed()
                    readyCapture("redesign-responsive-font-130-speed")
                    action("Cancel")
                } finally { shell("settings put system font_scale $previousFontScale") }
            } finally { shell("wm size reset"); shell("wm density reset") }
            ui.onNodeWithTag("editor-tools").performTouchInput { swipeLeft() }; readyCapture("redesign-toolbar-scrolled")
        } finally { scenario.close() }
    }

    @Test fun text_draft_recreation_retains_editor_and_cancel_removes_only_the_draft() {
        val source = File(context.filesDir, "text-recreation-${System.nanoTime()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("clip1_720p_6s.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val initial = ProjectCreator().create("Text recreation ${System.nanoTime()}", listOf(ref))
        val originalCaption = TextOverlay("original-caption", "Original caption", durationMicros = 3_000_000,
            opacity = .7f, rotationDegrees = 12f, positionX = .35f)
        val project = initial.copy(timeline = initial.timeline.copy(textOverlays = listOf(originalCaption)))
        projectId = project.id
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        try { runBlocking { assertTrue(ProjectRepository(db.projectDao(), storage).save(project).isSuccess) } }
        finally { db.close() }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.waitUntil(15_000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
            action(project.name)
            ui.waitUntil(15_000) { previewHasDecodedColor() }
            action("Text")
            ui.onNode(hasSetTextAction()).performTextReplacement("Unconfirmed draft")
            scenario.recreate()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("Text Editor").fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(hasSetTextAction()).assertTextEquals("Unconfirmed draft")
            action("Cancel")
            ui.onNodeWithTag("timeline").performTouchInput {
                val geometry = com.videoeditor.editing.timeline.TimelineGeometry.from(project.timeline)
                click(Offset(centerX + 40f, height * (geometry.rowTop(TimelineLayout.ROW_TEXT) +
                    geometry.rowHeight(TimelineLayout.ROW_TEXT) / 2) / geometry.totalHeight))
            }
            ui.onNodeWithTag("editor-tool-Edit").performScrollTo().performClick()
            ui.onNodeWithTag("clip-action-Edit").performScrollTo().performClick()
            ui.onNode(hasSetTextAction()).performTextReplacement("Edited caption")
            scenario.recreate()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("Text Editor").fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(hasSetTextAction()).assertTextEquals("Edited caption")
            action("Cancel")
            ui.onNodeWithContentDescription("Close").performClick()
            awaitDocument { it.timeline.textOverlays == listOf(originalCaption) }
            assertEquals(project.timeline, document().timeline)
        } finally { scenario.close(); source.delete() }
    }

    @Test fun a_microphone_denial_opens_settings_and_restores_the_preview() {
        // Establish denial from the host before instrumentation starts. Revoking a
        // granted runtime permission here can kill this instrumented process.
        assertEquals("Revoke RECORD_AUDIO and clear user-set/user-fixed flags before starting instrumentation",
            android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
        val source = File(context.filesDir, "permission-source-${System.nanoTime()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("clip1_720p_6s.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val project = ProjectCreator().create("Microphone permissions ${System.nanoTime()}", listOf(ref)); projectId = project.id
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        try { runBlocking { assertTrue(ProjectRepository(db.projectDao(), storage).save(project).isSuccess) } } finally { db.close() }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.waitUntil(15000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }; action(project.name)
            ui.waitUntil(15000) { previewHasDecodedColor() }
            action("Audio"); action("Voiceover"); ui.waitForIdle(); capture("microphone-request")
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            fun collect(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> =
                if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { collect(node.getChild(it)) }
            val until = android.os.SystemClock.elapsedRealtime() + 15000
            var denied = false
            while (!denied && android.os.SystemClock.elapsedRealtime() < until) {
                val node = collect(automation.rootInActiveWindow).firstOrNull {
                    it.isVisibleToUser && (it.viewIdResourceName?.endsWith("/permission_deny_button") == true ||
                        it.text?.toString()?.replace('’', '\'')?.equals("Don't allow", true) == true)
                }
                if (node != null) {
                    val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").let {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
                    }; denied = true
                } else Thread.sleep(100)
            }
            assertTrue("Native microphone denial button missing", denied)
            ui.waitUntil(10000) { ui.onAllNodesWithText("Microphone permission").fetchSemanticsNodes().isNotEmpty() }
            capture("microphone-denied"); action("Open Settings"); ui.waitForIdle(); Thread.sleep(700)
            ui.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" }
            assertEquals("com.android.settings", automation.rootInActiveWindow?.packageName?.toString())
            capture("microphone-settings")
            automation.executeShellCommand("input keyevent KEYCODE_BACK").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
            try {
                ui.waitUntil(15000) { previewHasDecodedColor() }
            } catch (failure: Throwable) {
                capture("microphone-settings-return-failure")
                throw failure
            }
            assertEquals(project.timeline, document().timeline)
        } finally {
            scenario.close()
            source.delete()
        }
    }

    @Test fun missing_media_prompts_relink_and_picker_cancellation_preserves_document() {
        val ref = MediaRef("missing-ref", "content://unavailable.audit.provider/original", com.videoeditor.core.model.MediaType.Video,
            "Original.mp4", durationMicros = 6_000_000, width = 1280, height = 720)
        val project = ProjectCreator().create("Missing media audit ${System.nanoTime()}", listOf(ref))
        ownedProjectIds += project.id
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        runBlocking { assertTrue(ProjectRepository(db.projectDao(), storage).save(project).isSuccess) }
        db.close()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.waitUntil(15_000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(project.name).performClick()
            ui.waitUntil(10_000) { runCatching { ui.onNodeWithText("Missing Media").assertIsDisplayed() }.isSuccess }
            capture("missing-media")
            ui.onNodeWithText("Relink File").performClick()
            capture("relink-picker")
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            ui.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            val cancellationDeadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (automation.rootInActiveWindow?.packageName?.toString() != context.packageName && android.os.SystemClock.elapsedRealtime() < cancellationDeadline) {
                if (automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) {
                    automation.executeShellCommand("input keyevent KEYCODE_BACK").let {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
                    }
                    Thread.sleep(500)
                } else Thread.sleep(100)
            }
            assertEquals("Native picker did not cancel", context.packageName, automation.rootInActiveWindow?.packageName?.toString())
            ui.waitUntil(10_000) { runCatching { ui.onNodeWithText("Missing Media").assertIsDisplayed() }.isSuccess }
            val reopened = (storage.load(project.id) as Result.Success).value
            assertEquals(project, reopened)
        } finally { scenario.close() }
    }

    @Test fun missing_media_relinks_through_native_provider_and_decodes_preview() {
        val resolver = context.contentResolver
        val name = "Relink source ${System.nanoTime()}.mp4"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/VideoEditorRelink")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val source = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun nativeTap(label: String) {
            runCatching { automation.waitForIdle(500, 10000) }; Thread.sleep(500)
            fun collect(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> =
                if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { collect(node.getChild(it)) }
            val until = android.os.SystemClock.elapsedRealtime() + 15000
            while (android.os.SystemClock.elapsedRealtime() < until) {
                val roots = (listOf(automation.rootInActiveWindow) + automation.windows.map { it.root }).filterNotNull().distinctBy { it.windowId }
                val node = roots.flatMap { collect(it) }.lastOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
                if (node != null) {
                    val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").let {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
                    }
                    Thread.sleep(400); return
                }
                Thread.sleep(100)
            }
            error("Native relink action missing: $label")
        }
        try {
            resolver.openOutputStream(source)!!.use { output ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("clip1_720p_6s.mp4").use { it.copyTo(output) }
            }
            resolver.update(source, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val ref = MediaRef("native-missing", "content://unavailable.audit.provider/original", com.videoeditor.core.model.MediaType.Video,
                "Original.mp4", durationMicros = 6_000_000, width = 1280, height = 720)
            val project = ProjectCreator().create("Native relink ${System.nanoTime()}", listOf(ref))
            projectId = project.id
            val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
            try { runBlocking { assertTrue(ProjectRepository(db.projectDao(), storage).save(project).isSuccess) } } finally { db.close() }
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                ui.waitUntil(15000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
                action(project.name)
                ui.waitUntil(10000) { ui.onAllNodesWithText("Relink File").fetchSemanticsNodes().isNotEmpty() }
                action("Relink File")
                nativeTap("Show roots"); nativeTap("Downloads"); nativeTap("VideoEditorRelink"); nativeTap(name)
                awaitDocument { it.mediaRefs.single().uri != ref.uri }
                val updated = document()
                assertEquals(project.timeline, updated.timeline)
                assertEquals(ref.id, updated.mediaRefs.single().id)
                assertEquals(ref.uri, updated.mediaRefs.single().originalUri)
                ui.waitUntil(15000) { previewHasDecodedColor() }
                capture("relink-success")
                ui.onNodeWithContentDescription("Close").performClick(); action(project.name)
                ui.waitUntil(15000) { previewHasDecodedColor() }
                assertEquals(updated.timeline, document().timeline)
                ui.onNodeWithText("Missing Media").assertDoesNotExist()
            } finally { scenario.close() }
        } finally { resolver.delete(source, null, null) }
    }

    @Test fun empty_editor_respects_insets_and_landscape_tools() {
        val empty = Project("empty-layout", "Empty layout check",0,System.currentTimeMillis())
        ownedProjectIds += empty.id
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        runBlocking { assertTrue(ProjectRepository(db.projectDao(),storage).save(empty).isSuccess) }
        db.close()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.waitUntil(15_000) { ui.onAllNodesWithText(empty.name).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(empty.name).performClick()
            ui.onNodeWithTag("timeline").assertIsDisplayed()
            ui.onNodeWithText("ⓘ Audio preview limited").assertDoesNotExist()
            ui.onNodeWithText("Filters").assertIsNotEnabled()
            ui.onNodeWithText("Speed").assertIsNotEnabled()
            ui.onNodeWithText("Add media to begin editing").assertIsDisplayed()
            ui.onAllNodes(hasText("Could not build the preview", substring = true)).assertCountEquals(0)
            capture("empty")
            for (orientation in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                scenario.onActivity { it.requestedOrientation = orientation }
                ui.waitUntil(10_000) { context.resources.configuration.orientation == if (orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT }
                ui.onNodeWithTag("timeline").assertIsDisplayed()
                ui.onNodeWithTag("editor-tools").assertIsDisplayed()
                val tools = ui.onNodeWithTag("editor-tools").fetchSemanticsNode().boundsInWindow
                val preview = ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInWindow
                scenario.onActivity { activity ->
                    val insets = activity.window.decorView.rootWindowInsets
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    assertTrue(preview.top >= bars.top)
                    assertTrue(tools.bottom <= activity.window.decorView.height - bars.bottom + 1)
                    val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView)
                    assertFalse(controller.isAppearanceLightStatusBars)
                    assertFalse(controller.isAppearanceLightNavigationBars)
                }
            }
        } finally { scenario.close() }
    }

    /** Read compositor pixels, including the real SurfaceView (not View.draw). */
    private fun previewHasDecodedColor(): Boolean {
        val bounds = ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInWindow
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return false
        try {
            var colored = 0
            for (y in 2..7) for (x in 2..7) {
                val px = (bounds.left + bounds.width * x / 10).toInt().coerceIn(0, screenshot.width - 1)
                val py = (bounds.top + bounds.height * y / 10).toInt().coerceIn(0, screenshot.height - 1)
                val pixel = screenshot.getPixel(px, py)
                val red = android.graphics.Color.red(pixel)
                if (red < 100 && (android.graphics.Color.blue(pixel) > 120 || android.graphics.Color.green(pixel) > 120)) colored++
            }
            // White transport/play icons and the orange warning cannot satisfy this.
            return colored >= 5
        } finally { screenshot.recycle() }
    }

    /** Coarse source identity at every edited clip midpoint, not pixel-level parity. */
    private fun assertExportSequence(retriever: MediaMetadataRetriever, edited: Project) {
        for (clip in edited.timeline.videoClips) {
            val outputTime = clip.timelineStartMicros + clip.timelineDurationMicros / 2
            val sourceTime = clip.sourceStartMicros + clip.sourceDurationMicros / 2
            val actual = retriever.getFrameAtTime(outputTime, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val scores = edited.mediaRefs.filter { it.type == com.videoeditor.core.model.MediaType.Video }.associate { ref ->
                val source = MediaMetadataRetriever()
                try {
                    source.setDataSource(context, Uri.parse(ref.uri))
                    val frame = source.getFrameAtTime(sourceTime.coerceAtMost((ref.durationMicros ?: sourceTime) - 100_000),
                        MediaMetadataRetriever.OPTION_CLOSEST)!!
                    val expected = android.graphics.Bitmap.createBitmap(actual.width, actual.height, android.graphics.Bitmap.Config.ARGB_8888)
                    val scale = minOf(actual.width.toFloat() / frame.width, actual.height.toFloat() / frame.height)
                    val left = (actual.width - frame.width * scale) / 2
                    val top = (actual.height - frame.height * scale) / 2
                    android.graphics.Canvas(expected).drawBitmap(frame, null,
                        android.graphics.RectF(left, top, actual.width - left, actual.height - top), null)
                    var error = 0L
                    for (y in 0 until 9) for (x in 0 until 16) {
                        val px = (actual.width * (x + .5f) / 16).toInt()
                        val py = (actual.height * (y + .5f) / 9).toInt()
                        val a = actual.getPixel(px, py)
                        val b = expected.getPixel(px, py)
                        error += kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
                    }
                    frame.recycle()
                    expected.recycle()
                    ref.id to error
                } finally { source.release() }
            }
            actual.recycle()
            assertEquals("exported clip midpoint must match committed source: $scores", clip.mediaRefId,
                scores.minBy { it.value }.key)
        }
    }

    @Test fun home_editor_touch_edits_reopen_and_export() {
        val preferences = dagger.hilt.android.EntryPointAccessors.fromApplication(context, AcceptancePreferences::class.java).preferences()
        // Exercise explicit Save here; the natural-footage workflow covers the
        // default automatic destination behavior independently.
        runBlocking { preferences.setSaveToPhotos(false) }
        val fixtures = File(context.filesDir, "acceptance-media").apply { mkdirs() }
        val refs = listOf("clip1_720p_6s.mp4", "clip2_480p_5s.mp4", "clip3_1080p_4s.mp4").map { name ->
            val file = File(fixtures, name)
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            (MediaMetadataExtractor().extract(context, Uri.fromFile(file)) as Result.Success<MediaRef>).value
        }
        val musicFile = File(fixtures, "music_20s.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open(musicFile.name).use { input ->
            musicFile.outputStream().use { input.copyTo(it) }
        }
        val music = (MediaMetadataExtractor().extract(context, Uri.fromFile(musicFile)) as Result.Success<MediaRef>).value
        val base = ProjectCreator().create(acceptanceName, refs)
        val project = base.copy(mediaRefs = base.mediaRefs + music, timeline = base.timeline.copy(
            audioClips = listOf(AudioClip("acceptance-audio", music.id, sourceEndMicros = 3_000_000)),
            textOverlays = listOf(TextOverlay("acceptance-text", "Timeline title")),
        ))
        projectId = project.id
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        runBlocking { assertTrue(ProjectRepository(db.projectDao(), storage).save(project).isSuccess) }
        db.close()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.waitUntil(15_000) { ui.onAllNodesWithText(acceptanceName).fetchSemanticsNodes().isNotEmpty() }
            capture("home")
            ui.onAllNodesWithText("New Project")[0].performClick()
            capture("media-import")
            ui.onNodeWithContentDescription("Back").performClick()
            ui.onNodeWithText(acceptanceName).performClick()
            ui.onNodeWithTag("timeline").assertIsDisplayed()
            capture("editor")
            ui.onAllNodesWithText("Text").onLast().performScrollTo().performClick()
            ui.onNode(hasSetTextAction()).performClick()
            ui.waitUntil(5_000) {
                var visible = false
                scenario.onActivity { activity ->
                    visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
                visible
            }
            ui.onNode(hasSetTextAction()).performTextReplacement("")
            ui.onNodeWithText("Apply").assertIsNotEnabled()
            ui.onNode(hasSetTextAction()).performTextReplacement("  مرحبا 世界 👩🏽‍💻\n ")
            ui.onNode(hasSetTextAction()).assertTextEquals("  مرحبا 世界 👩🏽‍💻\n ")
            ui.onNode(hasSetTextAction()).performTextReplacement("مرحبا 世界 👩🏽‍💻\n".repeat(20))
            ui.onNode(hasSetTextAction()).assertIsDisplayed()
            ui.onNodeWithText("Cancel").assertIsDisplayed()
            ui.onNodeWithText("Apply").assertIsDisplayed()
            var availableBottom = 0
            scenario.onActivity { activity ->
                val inset = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                availableBottom = activity.window.decorView.height - inset
            }
            listOf(ui.onNode(hasSetTextAction()), ui.onNodeWithText("Cancel"), ui.onNodeWithText("Apply"))
                .forEach { node ->
                    assertTrue("Text input and confirmation must remain above the keyboard",
                        node.fetchSemanticsNode().boundsInWindow.bottom <= availableBottom)
                }
            ui.onNode(hasSetTextAction()).performTextReplacement("Adventure\nمرحبا 世界")
            capture("text-keyboard-open")
            action("Font")
            // Hiding the native IME is asynchronous even after Compose is idle.
            ui.waitUntil(15000) { ui.onAllNodesWithText("Default").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Default").performScrollTo().assertIsDisplayed().performClick()
            capture("text-editing")
            ui.onNodeWithText("Apply").performClick()
            awaitDocument { it.timeline.textOverlays.any { text -> text.text == "Adventure\nمرحبا 世界" } }
            ui.onNodeWithContentDescription("Close").performClick()
            ui.onNodeWithText(acceptanceName).performClick()
            assertTrue(document().timeline.textOverlays.any { it.text == "Adventure\nمرحبا 世界" })
            // Fit the whole project so the coordinate math below (calibrated to
            // the fit-all span) is deterministic regardless of the initial zoom.
            action("Fit timeline")
            val contentHeight = ui.onNodeWithTag("timeline").fetchSemanticsNode().boundsInRoot.height
            val geometry = com.videoeditor.editing.timeline.TimelineGeometry.from(document().timeline)
            ui.onNodeWithTag("timeline-viewport").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, contentHeight) }
            ui.onNodeWithTag("timeline-viewport").performTouchInput {
                val audioY = height - contentHeight * TimelineLayout.AUDIO_HEIGHT / geometry.totalHeight / 2
                click(Offset(width * (.5f + 1_500_000 / (document().timeline.renderedDurationMicros / 0.45f)), audioY))
            }
            ui.onNodeWithTag("timeline-viewport").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -contentHeight) }
            val beforeAudioDraft = document()
            action("Volume")
            ui.onNodeWithTag("clip-volume-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.2f) }
            action("Cancel")
            assertEquals("Cancelled volume draft changed the project", beforeAudioDraft, document())
            action("Volume")
            ui.onNodeWithTag("clip-volume-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.6f) }
            capture("audio-editing")
            ui.onNodeWithText("Apply").performClick()
            awaitDocument { kotlin.math.abs(it.timeline.audioClips.single().volume - 0.6f) < 0.01f }
            action("Mute"); awaitDocument { it.timeline.audioClips.single().muted }
            action("Unmute"); awaitDocument { !it.timeline.audioClips.single().muted }
            repeat(document().timeline.videoClips.size + 1) {
                action("Previous clip boundary")
            }
            val playbackClock = timelineClockReader(ui)
            ui.waitUntil(10_000) { playbackClock()[0].toLong() < 500_000 }
            val playbackStart = playbackClock()[0].toLong()
            ui.onAllNodesWithContentDescription("Play/Pause")[0].performClick()
            try {
                // Observe the live clock without waiting for Compose to become idle
                // during playback; the dedicated sync suite verifies the timer text.
                ui.waitUntil(20_000) { playbackClock()[0].toLong() >= playbackStart + 500_000 }
            } catch (failure: Throwable) {
                capture("playback-timeout")
                throw AssertionError("Playback clock ${readTimelineClock(ui)}; transport " +
                    ui.onNodeWithContentDescription("Play/Pause").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription], failure)
            }
            ui.onAllNodesWithContentDescription("Play/Pause")[0].performClick()
            ui.onNodeWithTag("timeline").performTouchInput {
                swipe(Offset(width * .4f, height * .06f), Offset(width * .8f, height * .06f))
            }
            ui.waitUntil(5_000) { ui.onAllNodes(hasText("00:00.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("timeline").performTouchInput {
                pinch(Offset(width * .4f, videoY(height)), Offset(width * .2f, videoY(height)),
                    Offset(width * .6f, videoY(height)), Offset(width * .8f, videoY(height)))
            }
            ui.onNodeWithContentDescription("Close").performClick()
            ui.onNodeWithText(acceptanceName).performClick()
            action("Fit timeline")
            ui.onNodeWithTag("timeline").performTouchInput {
                click(Offset(width * .567f, videoY(height)))
            }
            action("Trim")
            action("Cancel")
            ui.onNodeWithTag("timeline").performTouchInput {
                down(Offset(width * .2f, videoY(height)))
                moveTo(Offset(width * .3f, videoY(height)))
                up()
            }
            awaitDocument { it.timeline.videoClips.first().sourceStartMicros > 0 }
            val afterTrim = document()
            assertTrue(afterTrim.timeline.videoClips.first().sourceDurationMicros > 0)
            action("Trim"); action("Cancel")
            ui.onNodeWithTag("timeline").performTouchInput {
                down(Offset(width * .8f, videoY(height)))
                moveTo(Offset(width * .7f, videoY(height)))
                up()
            }
            awaitDocument { it.timeline.videoClips.first().sourceEndMicros < 6_000_000 }
            val validTrim = document().timeline.videoClips.first()
            action("Trim"); action("Cancel")
            ui.onNodeWithTag("timeline").performTouchInput {
                down(Offset(width * .2f, videoY(height)))
                moveTo(Offset(width * .98f, videoY(height)))
                up()
            }
            awaitDocument { it.timeline.videoClips.first().sourceDurationMicros == com.videoeditor.editing.timeline.TrimMath.MIN_SOURCE_SPAN_MICROS }
            assertTrue(document().timeline.videoClips.first().sourceStartMicros >= 0)
            ui.onNodeWithContentDescription("Undo").performClick()
            awaitDocument { it.timeline.videoClips.first() == validTrim }
            ui.onNodeWithContentDescription("Close").performClick()
            ui.onNodeWithText(acceptanceName).performClick()
            action("Fit timeline")
            val firstDuration = document().timeline.videoClips.first().timelineDurationMicros
            // Select clip two, then scrub its playhead to an interior split point.
            ui.onNodeWithTag("timeline").performTouchInput {
                click(Offset(width * (.5f + (firstDuration + 2_000_000) / (document().timeline.renderedDurationMicros / 0.45f)), videoY(height)))
                swipe(Offset(width * .8f, height * .06f),
                    Offset(width * (.8f - (firstDuration + 2_000_000) / (document().timeline.renderedDurationMicros / 0.45f)), height * .06f))
            }
            capture("selected")
            action("Split"); action("Apply")
            awaitDocument { it.timeline.videoClips.size == 4 }
            ui.onNodeWithContentDescription("Close").performClick()
            ui.onNodeWithText(acceptanceName).performClick()
            action("Fit timeline")
            val originalFirst = document().timeline.videoClips.first().id
            ui.onNodeWithTag("timeline").performTouchInput {
                down(Offset(width * (.5f + firstDuration / 2 / (document().timeline.renderedDurationMicros / 0.45f)), videoY(height)))
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
                moveTo(Offset(width * .98f, videoY(height)))
                up()
            }
            awaitDocument { it.timeline.videoClips.last().id == originalFirst }
            ui.onNodeWithTag("timeline").performTouchInput {
                val last = document().timeline.videoClips.last()
                click(Offset(width * (.5f + (last.timelineStartMicros + last.timelineDurationMicros / 2) / (document().timeline.renderedDurationMicros / 0.45f)), videoY(height)))
            }
            action("Duplicate")
            awaitDocument { it.timeline.videoClips.size == 5 }
            action("Delete")
            awaitDocument { it.timeline.videoClips.size == 4 }
            ui.onNodeWithContentDescription("Undo").performClick()
            awaitDocument { it.timeline.videoClips.size == 5 }
            ui.onNodeWithContentDescription("Redo").performClick()
            awaitDocument { it.timeline.videoClips.size == 4 }
            val committed = document().timeline
            assertEquals(1, committed.audioClips.size)
            assertEquals(2, committed.textOverlays.size)
            ui.onNodeWithContentDescription("Close").performClick()
            ui.onNodeWithText(acceptanceName).performClick()
            ui.onNodeWithTag("timeline").assertIsDisplayed()
            ui.onNodeWithText("ⓘ Audio preview limited").assertDoesNotExist()
            assertEquals(committed, document().timeline)
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            ui.onNodeWithTag("editor-preview").assertIsDisplayed()
            assertTrue("landscape must preserve preview height", ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInRoot.height > 100)
            ui.onNodeWithTag("timeline").assertIsDisplayed()
            ui.onNodeWithText("Edit").assertIsDisplayed()
            capture("landscape")
            ui.onNodeWithText("ⓘ Audio preview limited").assertDoesNotExist()
            ui.onNodeWithContentDescription("Fullscreen preview").performClick()
            ui.onNodeWithTag("timeline").assertDoesNotExist()
            capture("fullscreen")
            ui.onNodeWithText("Tap to exit fullscreen").assertIsDisplayed().performClick()
            ui.onNodeWithTag("timeline").assertIsDisplayed()
            ui.waitUntil(10_000) { previewHasDecodedColor() }
            capture("landscape-restored")
            ui.onNodeWithText("ⓘ Audio preview limited").assertDoesNotExist()
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.waitUntil(10_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            ui.waitUntil(10_000) { previewHasDecodedColor() }
            capture("portrait")
            ui.onNodeWithContentDescription("Fullscreen preview").performClick()
            ui.onNodeWithText("Tap to exit fullscreen").performClick()
            ui.waitUntil(10_000) { previewHasDecodedColor() }
            ui.onNodeWithText("Export").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("720p").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("720p").performClick()
            capture("export-settings")
            action("Export Video")
            capture("export-progress")
            ui.waitUntil(120_000) { ui.onAllNodesWithText("Export Complete").fetchSemanticsNodes().isNotEmpty() }
            capture("export-completed")
            val output = File(context.filesDir, "exports").walkTopDown().filter { it.extension == "mp4" && it.name.startsWith("export_${projectId}_") }
                .maxByOrNull { it.lastModified() }!!
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertEquals(committed.totalDurationMicros / 1000.0, duration.toDouble(), 800.0)
                val frame = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                assertNotNull("UI-exported MP4 must decode", frame)
                frame?.recycle()
                assertExportSequence(retriever, document())
            } finally { retriever.release() }
            action("Save to Photos")
            ui.waitUntil(15_000) { ui.onAllNodesWithText("Saved to Photos").fetchSemanticsNodes().isNotEmpty() }
            capture("gallery-saved")
            action("Done")
            ui.onNodeWithTag("timeline").assertIsDisplayed()
            ui.waitUntil(10_000) { previewHasDecodedColor() }
            capture("returned-editor")
        } finally { scenario.close() }
        // Fixture ownership and preference restoration are handled by @After.
    }
    @Test fun photo_preview_survives_rotation_and_fullscreen_surface_replacement() {
        val source = File(context.filesDir, "photo-surface-source-${System.nanoTime()}.mp4")
        val photo = File(context.filesDir, "photo-surface-${System.nanoTime()}.png")
        InstrumentationRegistry.getInstrumentation().context.assets.open("waterfall.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val frame = retriever.getFrameAtTime(0)!!
            try { photo.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { frame.recycle() }
        } finally { retriever.release() }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(photo)).getOrNull()!!
        val project = ProjectCreator().create("Photo surfaces ${System.nanoTime()}", listOf(ref))
        projectId = project.id
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        try { runBlocking { assertTrue(ProjectRepository(db.projectDao(), storage).save(project).isSuccess) } } finally { db.close() }
        val expectedPhoto = android.graphics.BitmapFactory.decodeFile(photo.absolutePath)!!
        fun previewMatchesPhoto(): Boolean {
            val bounds = ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInWindow
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return false
            try {
                val scale = minOf(bounds.width / expectedPhoto.width, bounds.height / expectedPhoto.height)
                val left = bounds.left + (bounds.width - expectedPhoto.width * scale) / 2
                val top = bounds.top + (bounds.height - expectedPhoto.height * scale) / 2
                var error = 0L
                for (y in 1..8) for (x in 1..8) {
                    val sourceX = expectedPhoto.width * x / 10
                    val sourceY = expectedPhoto.height * y / 10
                    val actual = screenshot.getPixel((left + sourceX * scale).toInt().coerceIn(0, screenshot.width - 1),
                        (top + sourceY * scale).toInt().coerceIn(0, screenshot.height - 1))
                    val expected = expectedPhoto.getPixel(sourceX, sourceY)
                    for (shift in listOf(0, 8, 16)) error += kotlin.math.abs((actual shr shift and 255) - (expected shr shift and 255))
                }
                return error / 192.0 < 20
            } finally { screenshot.recycle() }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.waitUntil(15000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
            val photoOpenedAt = android.os.SystemClock.elapsedRealtime()
            action(project.name)
            ui.waitUntil(15000) { previewMatchesPhoto() }
            android.util.Log.i("PhotoSeekVerification", "initialSurfacePhotoMs=${android.os.SystemClock.elapsedRealtime() - photoOpenedAt}")
            capture("photo-surface-initial")
            for (orientation in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                scenario.onActivity { it.requestedOrientation = orientation }
                ui.waitUntil(10000) { context.resources.configuration.orientation == if (orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT }
                ui.waitUntil(15000) { previewMatchesPhoto() }
                ui.onNodeWithContentDescription("Fullscreen preview").performClick()
                ui.onNodeWithText("Tap to exit fullscreen").assertIsDisplayed().performClick()
                ui.waitUntil(15000) { previewMatchesPhoto() }
                capture("photo-surface-restored-$orientation")
            }
        } catch (error: Throwable) { capture("photo-surface-failure"); throw error }
        finally { scenario.close(); expectedPhoto.recycle(); source.delete(); photo.delete() }
    }
    @Test fun natural_motion_tools_voiceover_and_persistence() {
        runBlocking {
            dagger.hilt.android.EntryPointAccessors.fromApplication(context, AcceptancePreferences::class.java)
                .preferences().setSaveToPhotos(true)
        }
        val media = File(context.filesDir, "acceptance-media/waterfall.mp4").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().context.assets.open("waterfall.mp4").use { input -> media.outputStream().use(input::copyTo) }
        val ref = (MediaMetadataExtractor().extract(context, Uri.fromFile(media)) as Result.Success<MediaRef>).value
        val base = ProjectCreator().create("Natural acceptance ${System.nanoTime()}", listOf(ref))
        projectId = base.id
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        runBlocking { assertTrue(ProjectRepository(db.projectDao(), storage).save(base).isSuccess) }; db.close()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.waitUntil(15_000) { ui.onAllNodesWithText(base.name).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(base.name).performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
            Thread.sleep(1500)
            capture("natural-editor")
            fun naturalDecoded(): Boolean {
                val bounds = ui.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInWindow
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return false
                val retriever = MediaMetadataRetriever()
                var expected: android.graphics.Bitmap? = null
                try {
                    retriever.setDataSource(media.absolutePath)
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST) ?: return false
                    expected = frame
                    val aspect = base.canvas.width.toFloat() / base.canvas.height
                    val width = minOf(bounds.width, bounds.height * aspect)
                    val height = width / aspect
                    val left = bounds.left + (bounds.width-width)/2
                    val top = bounds.top + (bounds.height-height)/2
                    var error = 0L; var samples = 0
                    for (y in 2..7) for (x in 2..7) {
                        val actual = screenshot.getPixel((left+width*x/10).toInt().coerceIn(0,screenshot.width-1),
                            (top+height*y/10).toInt().coerceIn(0,screenshot.height-1))
                        val wanted = frame.getPixel(frame.width*x/10,frame.height*y/10)
                        for (shift in listOf(0,8,16)) { error += kotlin.math.abs((actual shr shift and 255)-(wanted shr shift and 255)); samples++ }
                    }
                    val mean = error.toDouble()/samples
                    android.util.Log.i("ResponsivePreview", "bounds=$bounds screenshot=${screenshot.width}x${screenshot.height} meanRGBError=$mean")
                    return mean < 45
                } finally { expected?.recycle(); retriever.release(); screenshot.recycle() }
            }
            ui.waitUntil(15000) { naturalDecoded() }
            fun configuration(command: String) {
                val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                Thread.sleep(1400)
            }
            repeat(3) {
                try {
                    configuration("settings put system font_scale 1.5")
                    capture("editor-large-font")
                    ui.onNodeWithText("Export").assertIsDisplayed()
                    ui.onNodeWithTag("timeline").assertIsDisplayed()
                    ui.onAllNodesWithText("Audio").onLast().performScrollTo().assertIsDisplayed()
                    configuration("settings put system font_scale 1.0")
                    configuration("wm size 480x960"); configuration("wm density 240")
                    capture("editor-compact")
                    ui.onNodeWithText("Export").assertIsDisplayed()
                    ui.onNodeWithTag("timeline").assertIsDisplayed()
                    ui.onAllNodesWithText("Speed").onLast().performScrollTo().assertIsDisplayed()
                } finally {
                    configuration("settings put system font_scale 1.0")
                    configuration("wm size reset"); configuration("wm density reset")
                }
                try { ui.waitUntil(15000) { naturalDecoded() } }
                catch (error: Throwable) { capture("editor-restored-failure"); throw error }
            }
            capture("editor-restored")
            ui.onNodeWithTag("timeline").performTouchInput { click(Offset(width * .6f, videoY(height))) }
            action("Crop")
            action("Flip H")
            ui.onNodeWithText("Apply").performClick()
            awaitDocument { it.timeline.videoClips.single().transform.flipHorizontal }
            ui.onNodeWithContentDescription("Undo").performClick()
            awaitDocument { !it.timeline.videoClips.single().transform.flipHorizontal }
            ui.onNodeWithContentDescription("Redo").performClick()
            awaitDocument { it.timeline.videoClips.single().transform.flipHorizontal }
            capture("natural-selected")
            action("Filters")
            ui.onNodeWithText("Warm").performClick()
            capture("natural-filters")
            ui.onNodeWithText("Apply").performClick()
            awaitDocument { it.timeline.videoClips.single().filter.filterId == "warm" }
            action("Adjust")
            ui.onNodeWithTag("grade-brightness").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.25f) }
            capture("natural-adjustments")
            ui.onNodeWithText("Apply").performClick()
            awaitDocument { it.timeline.videoClips.single().colorAdjustments.brightness == 0.25f }
            ui.onAllNodesWithText("Text").onLast().performScrollTo().performClick()
            ui.onNode(hasSetTextAction()).performTextReplacement("Adventure\nمرحبا 世界")
            action("Font")
            action("Serif")
            action("Apply")
            awaitDocument { it.timeline.textOverlays.single().fontId == "serif" }
            capture("natural-text-before-drag")
            ui.onNodeWithTag("editor-preview").performTouchInput { swipe(center, center + Offset(30f, -20f), 300) }
            awaitDocument { it.timeline.textOverlays.single().positionX > 0.51f }
            capture("natural-text-handles")
            ui.onNodeWithContentDescription("Undo").performClick()
            awaitDocument { it.timeline.textOverlays.single().positionX == 0.5f }
            ui.onNodeWithContentDescription("Redo").performClick()
            awaitDocument { it.timeline.textOverlays.single().positionX > 0.51f }
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
                .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
            ui.onNodeWithText("Audio").performClick()
            action("Voiceover")
            ui.waitUntil(10_000) { ui.onAllNodesWithText("Record").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Record").performClick()
            Thread.sleep(1500)
            ui.onNodeWithText("Stop").performClick()
            ui.onNodeWithText("Play take").performClick()
            capture("voiceover-review")
            ui.onNodeWithText("Save Voiceover").performClick()
            awaitDocument { it.timeline.audioClips.size == 1 }
            capture("natural-rich-editor")
            val recorded = document().timeline.audioClips.single()
            assertTrue(recorded.sourceDurationMicros > 500_000)
            val audioRef = document().mediaRef(recorded.mediaRefId)!!
            assertTrue(File(Uri.parse(audioRef.uri).path!!).length() > 0)
            ui.onNodeWithContentDescription("Close").performClick()
            ui.onNodeWithText(base.name).performClick()
            assertEquals("warm", document().timeline.videoClips.single().filter.filterId)
            assertEquals("serif", document().timeline.textOverlays.single().fontId)
            ui.waitUntil(15000) { runCatching { ui.onNodeWithContentDescription("Play/Pause").assertIsEnabled() }.isSuccess }
            ui.onNodeWithText("Export").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("720p").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("720p").performClick()
            action("Export Video")
            ui.waitUntil(120_000) { ui.onAllNodesWithText("Export Complete").fetchSemanticsNodes().isNotEmpty() }
            capture("natural-export-complete")
            ui.waitUntil(15_000) { ui.onAllNodesWithText("Saved to Photos").fetchSemanticsNodes().isNotEmpty() }
            val output = File(context.filesDir, "exports").listFiles()!!.filter { it.name.contains(projectId) }.maxBy { it.lastModified() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.absolutePath)
                assertNotNull(retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST))
                assertEquals(ref.durationMicros!! / 1000.0, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble(), 150.0)
            } finally { retriever.release() }
            action("Done")
            ui.waitUntil(15000) { runCatching { ui.onNodeWithContentDescription("Play/Pause").assertIsEnabled() }.isSuccess }
            ui.onNodeWithContentDescription("Aspect Ratio").performClick()
            action("16:9"); action("Apply")
            ui.onNodeWithTag("timeline").performTouchInput { click(Offset(width * .6f, videoY(height))) }
            action("Crop"); action("Scale"); action("Fill"); action("Apply")
            awaitDocument { it.canvas.width > it.canvas.height && it.canvas.scaleMode == com.videoeditor.core.model.ScaleMode.Fill }
            capture("natural-landscape-editor")
        } finally { scenario.close() }
    }

}
