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
import com.videoeditor.core.model.StickerClip
import com.videoeditor.editing.ProjectCreator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Device-level contract for the sticker editor: real touch gestures on the
 * preview canvas, one undoable edit per completed gesture, and survival of a
 * document save/reopen round trip — the same guarantees the PiP layer test
 * establishes for overlays.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StickerGestureDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sticker_gestures_undo_redo_and_document_round_trip() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, "sticker-gesture-source.mp4")
        context.assets.open("clip1_720p_6s.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val project = ProjectCreator().create("Sticker gesture", listOf(ref)).let { it.copy(timeline = it.timeline.copy(
            stickers = listOf(StickerClip(id = "s1", assetKey = "premium_heart",
                timelineStartMicros = 0L, timelineDurationMicros = 6_000_000L)),
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
            val layer = document.timeline.stickers.single()
            return Offset(plane.left + layer.positionX * plane.width, plane.top + layer.positionY * plane.height)
        }
        try {
            main { vm.loadProject(project.id); vm.onViewportSize(1080f, 540f) }
            compose.waitUntil(10_000) { vm.uiState.value.project != null }
            main { vm.selectSticker("s1") }
            compose.setContent {
                Column {
                    Box(Modifier.fillMaxWidth().height(240.dp)) {
                        AndroidView(factory = { SurfaceView(it).also(vm.previewController::attachSurface) }, modifier = Modifier.fillMaxSize())
                        LayerCanvasHandles(Modifier.fillMaxSize(), vm)
                    }
                    StickerTimelineLane(vm)
                }
            }
            compose.waitUntil(10_000) { vm.uiState.value.project?.timeline?.stickers?.isNotEmpty() == true }

            // Drag moves the sticker from the center toward the lower right.
            compose.onNodeWithTag("layer-canvas-handles").performTouchInput { swipe(center, center + Offset(80f, 30f), 300) }
            compose.waitForIdle()
            val dragged = vm.uiState.value.project!!.timeline.stickers.single()
            assertTrue("drag did not move the sticker", dragged.positionX > 0.5f && dragged.positionY > 0.5f)

            // Pinch scales it up.
            val pinchCenter = layerCenter()
            compose.onNodeWithTag("layer-canvas-handles").performTouchInput {
                pinch(start0 = pinchCenter - Offset(40f, 0f), end0 = pinchCenter - Offset(80f, 0f),
                    start1 = pinchCenter + Offset(40f, 0f), end1 = pinchCenter + Offset(80f, 0f), durationMillis = 300)
            }
            compose.waitForIdle()
            val resized = vm.uiState.value.project!!.timeline.stickers.single()
            assertTrue("pinch did not resize", resized.scale > dragged.scale * 1.4f)

            // Two-finger twist rotates it.
            val rotationCenter = layerCenter()
            compose.onNodeWithTag("layer-canvas-handles").performTouchInput {
                down(0, rotationCenter - Offset(60f, 0f)); down(1, rotationCenter + Offset(60f, 0f))
                moveTo(0, rotationCenter - Offset(40f, 40f)); moveTo(1, rotationCenter + Offset(40f, 40f)); move()
                up(0); up(1)
            }
            compose.waitForIdle()
            val rotated = vm.uiState.value.project!!.timeline.stickers.single()
            assertTrue("two-finger twist did not rotate", kotlin.math.abs(rotated.rotationDegrees) > 10)

            // A completed gesture is one history entry: one undo restores the pre-gesture value.
            main { vm.undo() }
            assertEquals("rotation gesture was not one reversible edit", resized, vm.uiState.value.project!!.timeline.stickers.single())
            main { vm.redo() }
            assertEquals(rotated, vm.uiState.value.project!!.timeline.stickers.single())

            // Duplicate produces a distinct instance; delete+undo restores it exactly.
            main { vm.duplicateSticker("s1") }
            val pair = vm.uiState.value.project!!.timeline.stickers
            assertEquals(2, pair.size)
            assertEquals(2, pair.map { it.id }.distinct().size)
            // Duplicate is one user action: a single undo removes the whole copy,
            // not just its placement nudge.
            main { vm.undo() }
            assertEquals(listOf("s1"), vm.uiState.value.project!!.timeline.stickers.map { it.id })
            main { vm.redo() }
            assertEquals(pair, vm.uiState.value.project!!.timeline.stickers)
            main { vm.deleteSticker(pair.first { it.id != "s1" }.id); vm.undo() }
            assertEquals(pair, vm.uiState.value.project!!.timeline.stickers)

            // The document on disk carries the same stickers after autosave settles.
            vm.awaitPendingSavesForTest()
            val reopened = repository.loadDocument(project.id).getOrNull()!!
            assertEquals("disk project lost sticker edits", vm.uiState.value.project!!.timeline.stickers, reopened.timeline.stickers)
        } finally {
            vm.awaitPendingSavesForTest()
            main { vm.releaseForTest() }
            appScope.cancel(); db.close()
        }
    }
}
