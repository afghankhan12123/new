package com.videoeditor.feature.editor

import androidx.compose.ui.test.*
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
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class TransitionToolsDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private lateinit var database:VideoEditorDatabase
    private lateinit var repository:ProjectRepository
    private lateinit var viewModel:EditorViewModel
    private lateinit var scope:CoroutineScope
    private lateinit var evidence:File
    private lateinit var project:Project

    private fun open(validHandles:Boolean=true) {
        evidence=File(context.getExternalFilesDir(null),"transition-ui/run-${android.os.SystemClock.elapsedRealtimeNanos()}").apply { check(mkdirs()) }
        database=Room.inMemoryDatabaseBuilder(context,VideoEditorDatabase::class.java).allowMainThreadQueries().build()
        repository=ProjectRepository(database.projectDao(),ProjectDocumentStorage(context))
        val refs=listOf("clip1_720p_6s.mp4","clip2_480p_5s.mp4").map { name ->
            val file=File(evidence,name)
            context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            runBlocking { MediaMetadataExtractor().extract(context,android.net.Uri.fromFile(file)).getOrNull()!! }
        }
        val base=ProjectCreator().create("Transition consumer controls",refs)
        project=base.copy(timeline=Timeline(videoClips=listOf(
            Clip("a",refs[0].id,if(validHandles)1_000_000 else 0,if(validHandles)3_000_000 else 6_000_000),
            Clip("b",refs[1].id,if(validHandles)1_000_000 else 0,if(validHandles)3_000_000 else 5_000_000,
                if(validHandles)2_000_000 else 6_000_000))))
        runBlocking { repository.save(project) }
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val duckingCache=com.videoeditor.core.media.audio.DuckingAnalysisCache()
        compose.runOnUiThread {
            viewModel=EditorViewModel(context,repository,MediaMetadataExtractor(),TimelineMediaRepository(WaveformExtractor(),Dispatchers.IO),
                CompositionPreviewController.Factory(context,CompositionBuilder(duckingCache)),Dispatchers.IO,Dispatchers.Main.immediate,scope,
                duckingAnalysis=com.videoeditor.core.media.audio.DuckingAnalysisService(context,
                    com.videoeditor.core.media.audio.DuckingAnalyzer(),duckingCache))
        }
        compose.setContent { androidx.compose.material3.MaterialTheme {
            EditorScreen(project.id,onBack={},onExport={},viewModel=viewModel)
        } }
        compose.waitUntil(15000) { viewModel.uiState.value.project?.id==project.id && !viewModel.uiState.value.preview.isBuffering }
        // Move the join under the fixed playhead with a real timeline ruler drag.
        compose.onNodeWithTag("timeline").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width*.85f,height*.04f),
                androidx.compose.ui.geometry.Offset(width*.35f,height*.04f),300)
        }
        compose.waitForIdle()
    }

    private fun openJoin() {
        compose.onNodeWithContentDescription("Add transition between clips").performClick()
        compose.onNodeWithTag("transition-sheet").assertExists()
        compose.onNodeWithTag("transition-cross-dissolve").performClick()
    }
    private fun screenshot(name:String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(evidence,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        val descriptor = instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/release-audit-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test fun real_controls_preview_cancel_apply_remove_history_and_reopen() {
        open()
        openJoin()
        compose.onNodeWithTag("transition-preview").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(15000) { viewModel.previewController.renderedPositionMicros in 1_850_000L..2_250_000L &&
            !viewModel.uiState.value.preview.isBuffering }
        assertEquals("Preview must not mutate the project",project.timeline,viewModel.uiState.value.project!!.timeline)
        assertFalse("Preview must not enter history",viewModel.uiState.value.canUndo)
        screenshot("preview-draft")
        compose.onNodeWithTag("transition-cancel").performClick()
        compose.waitForIdle()
        assertTrue(viewModel.uiState.value.project!!.timeline.transitions.isEmpty())
        assertFalse(viewModel.uiState.value.canUndo)
        openJoin()
        compose.onNodeWithTag("transition-duration").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1.5f) }
        compose.onNodeWithTag("transition-apply").assertIsEnabled().performClick()
        compose.waitUntil(10000) { viewModel.uiState.value.project!!.timeline.transitions.size==1 }
        val applied=viewModel.uiState.value.project!!
        assertEquals(project.timeline.videoClips,applied.timeline.videoClips)
        assertTrue(applied.timeline.transitions.single().durationMicros in 1_300_000..1_800_000)
        screenshot("applied")
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.waitUntil(10000) { viewModel.uiState.value.project!!.timeline.transitions.isEmpty() }
        compose.onNodeWithContentDescription("Redo").performClick()
        compose.waitUntil(10000) { viewModel.uiState.value.project!!.timeline.transitions==applied.timeline.transitions }
        compose.onNodeWithContentDescription("Edit Cross Dissolve").performClick()
        compose.onNodeWithTag("transition-remove").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10000) { viewModel.uiState.value.project!!.timeline.transitions.isEmpty() }
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.waitUntil(10000) { viewModel.uiState.value.project!!.timeline.transitions==applied.timeline.transitions }
        runBlocking { viewModel.awaitPendingSavesForTest() }
        val reopened=runBlocking { repository.loadDocument(project.id).getOrNull() }
        assertNotNull(reopened)
        assertEquals(applied.timeline,reopened!!.timeline)
        File(evidence,"reopened.json").writeText(kotlinx.serialization.json.Json.encodeToString(Project.serializer(),reopened))
    }

    @Test fun unavailable_source_handles_disable_preview_and_apply_without_history() {
        open(false)
        openJoin()
        compose.onNodeWithTag("transition-preview").assertIsNotEnabled()
        compose.onNodeWithTag("transition-apply").assertIsNotEnabled()
        compose.onNodeWithTag("transition-validation").assertTextContains("more footage",substring=true)
        screenshot("unavailable-handles")
        compose.onNodeWithTag("transition-cancel").performClick()
        assertEquals(project.timeline,viewModel.uiState.value.project!!.timeline)
        assertFalse(viewModel.uiState.value.canUndo)
    }

    @Test fun handle_creation_button_trims_clips_and_enables_transition_apply() {
        open(false)
        openJoin()
        screenshot("transition-before-handles")
        compose.onNodeWithTag("transition-apply").assertIsNotEnabled()
        compose.onNodeWithTag("transition-create-handles").assertIsDisplayed().performClick()
        compose.waitForIdle()
        screenshot("transition-after-handles")
        compose.onNodeWithTag("transition-apply").assertIsEnabled().performClick()
        compose.waitUntil(10000) { viewModel.uiState.value.project!!.timeline.transitions.size == 1 }
        screenshot("transition-applied-timeline")
        val applied = viewModel.uiState.value.project!!
        assertEquals(1, applied.timeline.transitions.size)
        // Undo transition
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.waitUntil(10000) { viewModel.uiState.value.project!!.timeline.transitions.isEmpty() }
        // Undo handle trim
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.waitUntil(10000) { viewModel.uiState.value.project!!.timeline.videoClips == project.timeline.videoClips }
    }

    @Test fun text_editor_preview_height_and_no_duplicate_layer() {
        open()
        compose.onNodeWithTag("editor-tool-Text").performScrollTo().performClick()
        compose.waitForIdle()
        screenshot("text-editor-open")
        compose.onNodeWithTag("editor-preview").assertIsDisplayed()
        val previewHeight = compose.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Preview height in pixels must be >= 300px for comfortable editing: $previewHeight", previewHeight >= 300f)
        compose.onNode(hasSetTextAction()).performTextReplacement("CapCut Clean Text")
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(10000) { viewModel.uiState.value.project!!.timeline.textOverlays.size == 1 }
        screenshot("text-overlay-applied")
        assertEquals("CapCut Clean Text", viewModel.uiState.value.project!!.timeline.textOverlays.single().text)

        // Tapping Text tool again when playhead intersects text overlay should edit existing overlay without duplicating
        compose.onNodeWithTag("editor-tool-Text").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Tapping Text tool again must not duplicate text layer", 1, viewModel.uiState.value.project!!.timeline.textOverlays.size)
        screenshot("text-reselected-no-duplicate")
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun topbar_resolution_dropdown_selection() {
        open()
        compose.onNodeWithTag("editor-resolution-badge").assertIsDisplayed().performClick()
        compose.waitForIdle()
        screenshot("resolution-dropdown-open")
        compose.onNodeWithTag("resolution-menu-720p").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(com.videoeditor.core.model.ExportResolution.RES_720P, viewModel.uiState.value.project?.exportPreferences?.resolution)
        screenshot("resolution-badge-720p")

        compose.onNodeWithTag("editor-resolution-badge").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("resolution-menu-1080p").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(com.videoeditor.core.model.ExportResolution.RES_1080P, viewModel.uiState.value.project?.exportPreferences?.resolution)
        screenshot("resolution-badge-1080p")
    }

    @After fun release() {
        if(::viewModel.isInitialized) {
            runBlocking { viewModel.awaitPendingSavesForTest() }
            compose.runOnUiThread { viewModel.cancelTransitionPreview();viewModel.releaseForTest() }
        }
        if(::scope.isInitialized)scope.cancel()
        if(::database.isInitialized)database.close()
    }
}
