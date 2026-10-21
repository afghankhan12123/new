package com.videoeditor.feature.editor

import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.room.Room
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
import com.videoeditor.core.model.OverlayClip
import com.videoeditor.core.model.VisualTransform
import com.videoeditor.editing.ProjectCreator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PipGestureDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun real_canvas_drag_pinch_rotation_and_document_round_trip() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, "pip-gesture-source.mp4")
        context.assets.open("clip1_720p_6s.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val project = ProjectCreator().create("PiP gesture", listOf(ref)).let { it.copy(timeline = it.timeline.copy(
            overlayClips = listOf(OverlayClip("pip", ref.id, timelineStartMicros = 1_000_000, timelineDurationMicros = 3_000_000,
                sourceEndMicros = 3_000_000, transform = VisualTransform(scale = .35f))),
        )) }
        val db = Room.inMemoryDatabaseBuilder(context, VideoEditorDatabase::class.java).allowMainThreadQueries().build()
        val repository = ProjectRepository(db.projectDao(), ProjectDocumentStorage(context))
        repository.save(project)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val duckingCache = com.videoeditor.core.media.audio.DuckingAnalysisCache()
        val vm = EditorViewModel(context, repository, MediaMetadataExtractor(),
            TimelineMediaRepository(WaveformExtractor(), Dispatchers.IO),
            CompositionPreviewController.Factory(context, CompositionBuilder(duckingCache)), Dispatchers.IO, Dispatchers.Main.immediate, appScope,
            duckingAnalysis = com.videoeditor.core.media.audio.DuckingAnalysisService(context,
                com.videoeditor.core.media.audio.DuckingAnalyzer(), duckingCache))
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun layerCenter(): Offset {
            val node = compose.onNodeWithTag("layer-canvas-handles").fetchSemanticsNode().size
            val document = vm.uiState.value.project!!
            val plane = fittedCanvasPlane(node.width.toFloat(), node.height.toFloat(), document.canvas)
            val layer = document.timeline.overlayClips.single()
            return plane.center + Offset(layer.transform.offsetX * plane.width / 2, layer.transform.offsetY * plane.height / 2)
        }
        try {
            main { vm.loadProject(project.id); vm.onViewportSize(1080f, 540f) }
            compose.waitUntil(10_000) { vm.uiState.value.project != null }
            main { vm.selectOverlay("pip") }
            compose.setContent {
                Column {
                    Box(Modifier.fillMaxWidth().height(240.dp)) {
                        AndroidView(factory = { SurfaceView(it).also(vm.previewController::attachSurface) }, modifier = Modifier.fillMaxSize())
                        LayerCanvasHandles(Modifier.fillMaxSize(), vm)
                    }
                    PipTimelineLane(vm) { }
                }
            }
            // A coalesced model edit may initialize another graph while the
            // requested seek is still decoding. It must retain that target.
            main {
                vm.seekTo(2_000_000)
                vm.updateOverlay(vm.uiState.value.project!!.timeline.overlayClips.single().copy(opacity = .9f))
            }
            compose.waitUntil(10_000) {
                vm.previewController.state.value.actualPositionMicros == 2_000_000L &&
                    vm.previewController.renderedPositionMicros >= 2_000_000L &&
                    vm.uiState.value.playheadMicros == 2_000_000L
            }
            Thread.sleep(600)
            assertEquals("coalesced edit replaced the seek with a stale clock", 2_000_000L, vm.uiState.value.playheadMicros)
            compose.onNodeWithTag("layer-canvas-handles").performTouchInput { swipe(center, center + Offset(80f, 30f), 300) }
            compose.waitForIdle()
            val dragged = vm.uiState.value.project!!.timeline.overlayClips.single()
            assertTrue("actual touch did not reposition overlay", dragged.transform.offsetX > .05f && dragged.transform.offsetY > .02f)
            val pinchCenter = layerCenter()
            compose.onNodeWithTag("layer-canvas-handles").performTouchInput {
                pinch(start0 = pinchCenter - Offset(40f, 0f), end0 = pinchCenter - Offset(80f, 0f), start1 = pinchCenter + Offset(40f, 0f), end1 = pinchCenter + Offset(80f, 0f), durationMillis = 300)
            }
            compose.waitForIdle()
            val resized = vm.uiState.value.project!!.timeline.overlayClips.single()
            assertTrue("pinch did not resize", resized.transform.scale > dragged.transform.scale * 1.4f)
            val rotationCenter = layerCenter()
            compose.onNodeWithTag("layer-canvas-handles").performTouchInput {
                down(0, rotationCenter - Offset(60f, 0f)); down(1, rotationCenter + Offset(60f, 0f))
                moveTo(0, rotationCenter - Offset(40f, 40f)); moveTo(1, rotationCenter + Offset(40f, 40f)); move()
                up(0); up(1)
            }
            compose.waitForIdle()
            val rotated = vm.uiState.value.project!!.timeline.overlayClips.single()
            assertTrue("two-finger touch did not rotate", kotlin.math.abs(rotated.transform.rotationDegrees) > 10)
            main { vm.undo() }
            assertEquals("gesture was not one reversible edit", resized, vm.uiState.value.project!!.timeline.overlayClips.single())
            main { vm.redo(); vm.updateOverlay(rotated.copy(opacity = .6f, volume = .4f, muted = true, zIndex = 3)); vm.duplicateOverlay("pip") }
            val layers = vm.uiState.value.project!!.timeline.overlayClips
            assertEquals(2, layers.size)
            main { vm.deleteOverlay(layers.last().id); vm.undo() }
            assertEquals(layers, vm.uiState.value.project!!.timeline.overlayClips)
            vm.awaitPendingSavesForTest()
            val reopened = repository.loadDocument(project.id).getOrNull()!!
            assertEquals("disk project lost overlay edits", vm.uiState.value.project!!.timeline.overlayClips, reopened.timeline.overlayClips)
            assertEquals(project.mediaRefs, reopened.mediaRefs)
        } finally {
            vm.awaitPendingSavesForTest()
            main { vm.releaseForTest() }
            appScope.cancel(); db.close()
        }
    }
}
