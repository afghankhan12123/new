package com.videoeditor.feature.editor

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.database.VideoEditorDatabase
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.database.storage.ProjectDocumentStorage
import com.videoeditor.core.media.MediaMetadataExtractor
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.media.timeline.TimelineMediaRepository
import com.videoeditor.core.media.waveform.WaveformExtractor
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import com.videoeditor.editing.timeline.TimelineSelection
import java.io.File
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class PremiumWorkspaceDeviceTest {

    @get:Rule val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var database: VideoEditorDatabase
    private lateinit var repository: ProjectRepository
    private lateinit var viewModel: EditorViewModel
    private lateinit var scope: CoroutineScope
    private lateinit var project: Project

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, VideoEditorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProjectRepository(database.projectDao(), ProjectDocumentStorage(context))

        val f1 = File(context.filesDir, "premium-ui-clip1.mp4")
        val f2 = File(context.filesDir, "premium-ui-clip2.mp4")
        context.assets.open("clip1_720p_6s.mp4").use { input -> f1.outputStream().use { input.copyTo(it) } }
        context.assets.open("clip2_480p_5s.mp4").use { input -> f2.outputStream().use { input.copyTo(it) } }

        val ref1 = MediaMetadataExtractor().extract(context, Uri.fromFile(f1)).getOrNull()!!
        val ref2 = MediaMetadataExtractor().extract(context, Uri.fromFile(f2)).getOrNull()!!

        val base = ProjectCreator().create("Stage9 UI Test", listOf(ref1, ref2), canvas = CanvasSettings(width = 1280, height = 720))
        project = base.copy(
            timeline = Timeline(
                videoClips = listOf(
                    Clip("c1", ref1.id, sourceStartMicros = 1_000_000L, sourceEndMicros = 4_000_000L, timelineStartMicros = 0L),
                    Clip("c2", ref2.id, sourceStartMicros = 1_000_000L, sourceEndMicros = 4_000_000L, timelineStartMicros = 3_000_000L)
                ),
                overlayClips = listOf(
                    OverlayClip("o1", ref2.id, timelineStartMicros = 500_000L, timelineDurationMicros = 2_000_000L, sourceEndMicros = 2_000_000L)
                )
            )
        )
        repository.save(project)

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val duckingCache = com.videoeditor.core.media.audio.DuckingAnalysisCache()

        instrumentation.runOnMainSync {
            viewModel = EditorViewModel(
                context,
                repository,
                MediaMetadataExtractor(),
                TimelineMediaRepository(WaveformExtractor(), Dispatchers.IO),
                CompositionPreviewController.Factory(context, CompositionBuilder(duckingCache)),
                Dispatchers.IO,
                Dispatchers.Main.immediate,
                scope,
                duckingAnalysis = com.videoeditor.core.media.audio.DuckingAnalysisService(
                    context,
                    com.videoeditor.core.media.audio.DuckingAnalyzer(),
                    duckingCache
                )
            )
            viewModel.loadProject(project.id)
            viewModel.onViewportSize(1080f, 540f)
        }

        compose.waitUntil(10_000) { viewModel.uiState.value.project != null }
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            runBlocking { viewModel.awaitPendingSavesForTest() }
            instrumentation.runOnMainSync { viewModel.releaseForTest() }
        }
        if (::scope.isInitialized) scope.cancel()
        if (::database.isInitialized) database.close()
    }

    private fun capture(name: String) {
        for (tag in listOf("editor-header", "editor-preview", "editor-panel", "editor-transport", "timeline", "editor-tools")) {
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
            android.util.Log.i("PremiumBounds", "$name $tag ${node?.boundsInRoot}")
        }
        val descriptor = instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/premium-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test fun paused_seeks_visual_updates_and_text_transaction_keep_frame_output() {
        compose.runOnUiThread { (compose as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>).activity.apply {
            actionBar?.hide(); window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        } }
        compose.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().systemBarsPadding()) {
                EditorScreen(project.id, onBack = {}, onExport = {}, viewModel = viewModel)
            }
        }
        fun ready(label: String) {
            try { compose.waitUntil(15_000) {
                val state = viewModel.previewController.state.value
                state.hasComposition && !state.isBuffering && state.error == null &&
                    viewModel.previewController.renderedPositionMicros >= 0
            } } catch (failure: Throwable) {
                capture("failure-$label")
                android.util.Log.e("PremiumWorkspace", "$label ${viewModel.previewController.state.value}")
                throw failure
            }
        }
        ready("initial"); capture("main")
        for (target in listOf(1_213_000L, 3_147_000L, 511_000L, 4_239_000L, 0L, 2_217_000L)) {
            compose.runOnUiThread { viewModel.seekTo(target) }
            ready("seek-$target")
            Thread.sleep(4500) // Deliberately cross the existing four-second output watchdog.
            compose.runOnUiThread {
                assertNull("seek $target", viewModel.previewController.state.value.error)
                assertFalse(viewModel.previewController.state.value.playRequested)
            }
        }
        compose.runOnUiThread { viewModel.seekTo(1_000_000); viewModel.clearSelection() }
        ready("text-start")
        compose.onNodeWithTag("editor-tool-Text").performScrollTo().performClick()
        compose.onNodeWithTag("text-entry").assertIsFocused().performTextReplacement("A new chapter")
        compose.waitForIdle(); Thread.sleep(1500); capture("keyboard")
        val preview = compose.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("editor-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Usable canvas with keyboard", preview.height >= 100 * context.resources.displayMetrics.density)
        assertTrue("Input panel stays below preview", panel.top >= preview.bottom)
        compose.onNodeWithTag("text-apply").assertIsDisplayed().performClick()
        compose.runOnUiThread {
            assertEquals(1, viewModel.uiState.value.project!!.timeline.textOverlays.size)
            assertEquals("A new chapter", viewModel.uiState.value.project!!.timeline.textOverlays.single().text)
        }
        ready("text-applied"); Thread.sleep(4500)
        compose.runOnUiThread { assertNull(viewModel.previewController.state.value.error); viewModel.clearSelection() }
        compose.onNodeWithTag("editor-tool-Text").performScrollTo().performClick()
        compose.onNodeWithTag("text-entry").performTextReplacement("Independent caption")
        compose.onNodeWithTag("text-apply").performClick()
        compose.runOnUiThread { assertEquals(2, viewModel.uiState.value.project!!.timeline.textOverlays.size) }
        ready("two-captions"); capture("two-captions")
        compose.runOnUiThread { viewModel.undo() }
        compose.runOnUiThread { assertEquals(1, viewModel.uiState.value.project!!.timeline.textOverlays.size) }
        compose.runOnUiThread { viewModel.select(TimelineSelection.Text(viewModel.uiState.value.project!!.timeline.textOverlays.single().id)) }
        compose.onNodeWithTag("clip-action-Edit").performScrollTo().performClick()
        compose.onNodeWithTag("text-entry").performTextReplacement("Canceled change")
        compose.onNodeWithTag("text-cancel").performClick()
        compose.runOnUiThread { assertEquals("A new chapter", viewModel.uiState.value.project!!.timeline.textOverlays.single().text) }
        ready("canceled-text")
        val caption = viewModel.uiState.value.project!!.timeline.textOverlays.single()
        compose.runOnUiThread {
            assertTrue(viewModel.beginTextTrim(caption.id, com.videoeditor.editing.timeline.TrimMath.Edge.End))
            viewModel.textTrimBy(-500_000f * viewModel.uiState.value.zoomPixelsPerMicro)
            viewModel.endTextTrim(true)
            assertEquals(caption.durationMicros - 500_000, viewModel.uiState.value.project!!.timeline.textOverlays.single().durationMicros)
            viewModel.undo()
            assertEquals(caption.durationMicros, viewModel.uiState.value.project!!.timeline.textOverlays.single().durationMicros)
            viewModel.clearSelection()
        }
        ready("text-trim")
        compose.onNodeWithTag("editor-tool-Effects").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("catalog-effect-Exposure").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1000); capture("effects")
        compose.onNodeWithTag("catalog-effect-Exposure").performClick()
        compose.onNodeWithTag("tool-apply").performClick()
        ready("effects-applied")
        compose.runOnUiThread { assertEquals(VisualEffectType.Exposure, viewModel.uiState.value.project!!.timeline.videoClips.first().effects.single().type); viewModel.clearSelection() }
        compose.onNodeWithTag("editor-tool-Stickers").performScrollTo().performClick()
        capture("stickers")
        compose.onNodeWithTag("sticker-star").performClick()
        compose.runOnUiThread { assertEquals(1, viewModel.uiState.value.project!!.timeline.stickers.size); viewModel.clearSelection() }
        ready("sticker-added")
        compose.onNodeWithTag("editor-tool-Transition").performScrollTo().performClick()
        compose.onNodeWithTag("transition-cross-dissolve").performClick()
        compose.onNodeWithTag("transition-apply").assertIsEnabled().performClick()
        ready("transition-applied")
        capture("final")
    }
    @Test fun composed_project_exports_at_both_supported_resolutions() = runBlocking {
        val current = project.copy(timeline = project.timeline.copy(
            textOverlays = listOf(TextOverlay("title-export", "A new chapter", bold = true, italic = true, hasOutline = true)),
            stickers = listOf(StickerClip("sticker-export", "star", timelineDurationMicros = 2_000_000L)),
            transitions = listOf(Transition("join-export", "c1", "c2", 500_000L)),
            videoClips = project.timeline.videoClips.mapIndexed { index, clip ->
                if (index == 0) clip.copy(effects = listOf(VisualEffect.createDefault(VisualEffectType.Sepia))) else clip
            }))
        compose.runOnUiThread { viewModel.previewController.suspendPlayback() }
        for (resolution in ExportResolution.entries) {
            val output = File(context.cacheDir, "premium-export-${resolution.label}.mp4")
            try {
                val result = com.videoeditor.core.media.export.ExportEngine(context, CompositionBuilder()).export(current,
                    com.videoeditor.core.media.export.ExportRequest(current.id, output, resolution, FrameRate.FPS_30, ExportQuality.Standard)) {}
                assertTrue("Export $resolution: $result", result.isSuccess)
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(output.absolutePath)
                    val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                    val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                    assertEquals(if (resolution == ExportResolution.RES_1080P) 1080 else 720, minOf(width, height))
                    assertNotNull(retriever.getFrameAtTime(1_000_000))
                    android.util.Log.i("PremiumExport", "$resolution dimensions=${width}x${height} bytes=${output.length()}")
                } finally { retriever.release() }
            } finally { output.delete() }
        }
    }
    @Test fun capture_cinematic_workspace_and_direct_text_on_real_media() = runBlocking {
        val file = File(context.filesDir, "premium-waterfall.mp4")
        context.assets.open("premium-waterfall.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(file)).getOrNull()!!
        val base = ProjectCreator().create("Forest journal", listOf(ref, ref))
        val visual = base.copy(canvas = base.canvas.copy(width = 1080, height = 1920, scaleMode = ScaleMode.Fill),
            timeline = base.timeline.copy(videoClips = listOf(
                base.timeline.videoClips[0].copy(sourceStartMicros = 300_000, sourceEndMicros = 2_800_000),
                base.timeline.videoClips[1].copy(sourceStartMicros = 300_000, sourceEndMicros = 2_800_000, timelineStartMicros = 2_500_000)),
                textOverlays = listOf(TextOverlay("visual-title", "A new chapter", fontId = "serif", fontSizeSp = 40f, hasShadow = true, positionY = .70f))))
        repository.save(visual)
        compose.runOnUiThread {
            viewModel.loadProject(visual.id)
            (compose as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>).activity.apply {
                actionBar?.hide()
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            }
        }
        compose.waitUntil(10000) { viewModel.uiState.value.project?.id == visual.id }
        compose.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().systemBarsPadding()) {
                EditorScreen(visual.id, onBack = {}, onExport = {}, viewModel = viewModel)
            }
        }
        compose.waitUntil(15000) { viewModel.previewController.renderedPositionMicros >= 0 }
        capture("cinematic-main")
        compose.onNodeWithTag("editor-tool-Text").performScrollTo().performClick()
        compose.onNodeWithTag("text-entry").performTextReplacement("Adventure awaits")
        Thread.sleep(1000); capture("cinematic-keyboard")
        compose.onNodeWithText("Fonts").performClick()
        Thread.sleep(500); capture("cinematic-fonts")
        compose.onNodeWithTag("text-cancel").performClick()
        compose.runOnUiThread { viewModel.clearSelection() }
        compose.onNodeWithTag("editor-tool-Effects").performScrollTo().performClick()
        capture("cinematic-effects-waiting")
        compose.waitUntil(15000) { compose.onAllNodesWithTag("effect-thumbnail-Exposure", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        capture("cinematic-effects")
        compose.onNodeWithTag("tool-cancel").performClick()
        compose.runOnUiThread { viewModel.clearSelection() }
        compose.onNodeWithTag("editor-tool-Stickers").performScrollTo().performClick()
        capture("cinematic-stickers")
        compose.onNodeWithTag("tool-cancel").performClick()
        compose.runOnUiThread { viewModel.select(TimelineSelection.Video(visual.timeline.videoClips.first().id)) }
        capture("cinematic-contextual")
        compose.onNodeWithTag("clip-action-Transition").performScrollTo().performClick()
        capture("cinematic-transitions")
        compose.onNodeWithTag("transition-cancel").performClick()
        Unit
    }
    @Test fun contextual_audio_speed_keeps_existing_processing_settings_and_undo() = runBlocking {
        val file = File(context.filesDir, "premium-music.m4a")
        context.assets.open("music_20s.m4a").use { input -> file.outputStream().use(input::copyTo) }
        compose.runOnUiThread {
            (compose as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>).activity.actionBar?.hide()
            viewModel.addAudioFromUri(Uri.fromFile(file))
        }
        compose.waitUntil(10000) { viewModel.uiState.value.project!!.timeline.audioClips.isNotEmpty() }
        val original = viewModel.uiState.value.project!!.timeline.audioClips.single()
        compose.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().systemBarsPadding()) {
                EditorScreen(project.id, onBack = {}, onExport = {}, viewModel = viewModel)
            }
        }
        compose.onNodeWithTag("clip-action-Speed").performScrollTo().performClick()
        compose.onNodeWithText("1.5x").performClick()
        compose.onNodeWithTag("tool-apply").performClick()
        compose.runOnUiThread {
            val changed = viewModel.uiState.value.project!!.timeline.audioClips.single()
            assertEquals(original.copy(playbackSpeed = 1.5f), changed)
            viewModel.undo()
            assertEquals(original, viewModel.uiState.value.project!!.timeline.audioClips.single())
        }
        Unit
    }
}
