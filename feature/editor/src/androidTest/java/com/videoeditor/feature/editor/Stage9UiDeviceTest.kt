package com.videoeditor.feature.editor

import android.net.Uri
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
class Stage9UiDeviceTest {

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

        val f1 = File(context.filesDir, "stage9_ui_clip1.mp4")
        val f2 = File(context.filesDir, "stage9_ui_clip2.mp4")
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

    @Test
    fun clip_visual_effects_add_update_reorder_undo_redo_and_persistence() = runBlocking {
        // 1. Add Vignette effect
        instrumentation.runOnMainSync {
            viewModel.select(TimelineSelection.Video("c1"))
            viewModel.addClipEffect("c1", VisualEffect.createDefault(VisualEffectType.Vignette))
        }
        compose.waitUntil(5_000) {
            val clip = viewModel.uiState.value.project?.timeline?.videoClips?.firstOrNull()
            clip?.effects?.any { it.type == VisualEffectType.Vignette } == true
        }

        // 2. Add Sepia effect
        instrumentation.runOnMainSync {
            viewModel.addClipEffect("c1", VisualEffect.createDefault(VisualEffectType.Sepia))
        }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.videoClips?.first()?.effects?.size == 2
        }

        val clipWith2 = viewModel.uiState.value.project!!.timeline.videoClips.first()
        val fxVignette = clipWith2.effects[0]
        val fxSepia = clipWith2.effects[1]
        assertEquals(VisualEffectType.Vignette, fxVignette.type)
        assertEquals(VisualEffectType.Sepia, fxSepia.type)

        // 3. Update intensity of Sepia
        val updatedSepia = fxSepia.copy(intensity = 0.75f)
        instrumentation.runOnMainSync {
            viewModel.updateClipEffect("c1", updatedSepia)
        }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.videoClips?.first()?.effects?.get(1)?.intensity == 0.75f
        }

        // 4. Reorder effects
        instrumentation.runOnMainSync {
            viewModel.reorderClipEffects("c1", 1, 0)
        }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.videoClips?.first()?.effects?.first()?.type == VisualEffectType.Sepia
        }

        // 5. Test Undo
        instrumentation.runOnMainSync { viewModel.undo() }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.videoClips?.first()?.effects?.first()?.type == VisualEffectType.Vignette
        }

        // 6. Test Redo
        instrumentation.runOnMainSync { viewModel.redo() }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.videoClips?.first()?.effects?.first()?.type == VisualEffectType.Sepia
        }

        // 7. Verify Document Persistence
        viewModel.awaitPendingSavesForTest()
        val loaded = repository.loadDocument(project.id).getOrNull()
        assertNotNull(loaded)
        val loadedEffects = loaded!!.timeline.videoClips.first().effects
        assertEquals(2, loadedEffects.size)
        assertEquals(VisualEffectType.Sepia, loadedEffects[0].type)
        assertEquals(0.75f, loadedEffects[0].intensity)
        assertEquals(VisualEffectType.Vignette, loadedEffects[1].type)
    }

    @Test
    fun overlay_mask_chroma_and_blend_mode_undo_redo_and_persistence() = runBlocking {
        // 1. Set Blend Mode to Multiply
        instrumentation.runOnMainSync {
            viewModel.selectOverlay("o1")
            viewModel.setOverlayBlendMode("o1", BlendMode.Multiply)
        }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.blendMode == BlendMode.Multiply
        }

        // 2. Set Layer Mask to Circle with feather = 0.08
        val circleMask = LayerMask(
            type = MaskType.Circle,
            scaleX = 0.4f,
            scaleY = 0.4f,
            feather = 0.08f,
            invert = false,
            enabled = true
        )
        instrumentation.runOnMainSync {
            viewModel.setOverlayMask("o1", circleMask)
        }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.mask?.type == MaskType.Circle
        }

        // 3. Set Chroma Key
        val chroma = ChromaKeySettings(
            enabled = true,
            keyColor = ChromaKeySettings.PRESET_GREEN,
            tolerance = 0.4f,
            softness = 0.12f,
            spillSuppression = 0.55f
        )
        instrumentation.runOnMainSync {
            viewModel.setOverlayChromaKey("o1", chroma)
        }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.chromaKey?.enabled == true
        }

        val overlayState = viewModel.uiState.value.project!!.timeline.overlayClips.first()
        assertEquals(BlendMode.Multiply, overlayState.blendMode)
        assertEquals(MaskType.Circle, overlayState.mask?.type)
        assertEquals(0.08f, overlayState.mask?.feather)
        assertTrue(overlayState.chromaKey!!.enabled)
        assertEquals(0.4f, overlayState.chromaKey!!.tolerance)

        // 4. Undo chroma key
        instrumentation.runOnMainSync { viewModel.undo() }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.chromaKey == null
        }

        // 5. Redo chroma key
        instrumentation.runOnMainSync { viewModel.redo() }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.chromaKey?.enabled == true
        }

        // 6. Persistence check
        viewModel.awaitPendingSavesForTest()
        val loaded = repository.loadDocument(project.id).getOrNull()
        assertNotNull(loaded)
        val loadedOverlay = loaded!!.timeline.overlayClips.first()
        assertEquals(BlendMode.Multiply, loadedOverlay.blendMode)
        assertNotNull(loadedOverlay.mask)
        assertEquals(MaskType.Circle, loadedOverlay.mask!!.type)
        assertEquals(0.08f, loadedOverlay.mask!!.feather)
        assertNotNull(loadedOverlay.chromaKey)
        assertTrue(loadedOverlay.chromaKey!!.enabled)
        assertEquals(ChromaKeySettings.PRESET_GREEN, loadedOverlay.chromaKey!!.keyColor)
    }

    @Test
    fun polygon_mask_and_add_blend_mode_undo_redo_and_persistence() = runBlocking {
        // 1. Set BlendMode to Add
        instrumentation.runOnMainSync {
            viewModel.selectOverlay("o1")
            viewModel.setOverlayBlendMode("o1", BlendMode.Add)
        }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.blendMode == BlendMode.Add
        }

        // 2. Set Polygon Mask
        val polyMask = LayerMask(
            type = MaskType.Polygon,
            polygonSides = 5,
            scaleX = 0.6f,
            scaleY = 0.6f,
            rotationDegrees = 45f,
            feather = 0.05f,
            centerX = 0.1f,
            centerY = -0.1f,
            enabled = true
        )
        instrumentation.runOnMainSync {
            viewModel.setOverlayMask("o1", polyMask)
        }
        compose.waitUntil(5_000) {
            val mask = viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.mask
            mask?.type == MaskType.Polygon && mask.polygonSides == 5
        }

        val overlayState = viewModel.uiState.value.project!!.timeline.overlayClips.first()
        assertEquals(BlendMode.Add, overlayState.blendMode)
        assertEquals(MaskType.Polygon, overlayState.mask?.type)
        assertEquals(5, overlayState.mask?.polygonSides)
        assertEquals(45f, overlayState.mask?.rotationDegrees)

        // 3. Undo polygon mask
        instrumentation.runOnMainSync { viewModel.undo() }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.mask == null
        }

        // 4. Redo polygon mask
        instrumentation.runOnMainSync { viewModel.redo() }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.mask?.type == MaskType.Polygon
        }

        // 5. Verify Room persistence
        viewModel.awaitPendingSavesForTest()
        val loaded = repository.loadDocument(project.id).getOrNull()
        assertNotNull(loaded)
        val loadedOverlay = loaded!!.timeline.overlayClips.first()
        assertEquals(BlendMode.Add, loadedOverlay.blendMode)
        assertNotNull(loadedOverlay.mask)
        assertEquals(MaskType.Polygon, loadedOverlay.mask!!.type)
        assertEquals(5, loadedOverlay.mask!!.polygonSides)
        assertEquals(0.1f, loadedOverlay.mask!!.centerX)
    }

    @Test
    fun transitions_apply_wipeup_split_and_undo_redo() = runBlocking {
        val transWipeUp = Transition("t-wu", "c1", "c2", durationMicros = 1_000_000L, type = TransitionType.WipeUp)
        instrumentation.runOnMainSync {
            val applied = viewModel.applyTransition(transWipeUp)
            assertTrue("Expected transition to be applied", applied)
        }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.transitions?.any { it.type == TransitionType.WipeUp } == true
        }
        assertEquals(TransitionType.WipeUp, viewModel.uiState.value.project!!.timeline.transitions.first().type)

        // Undo transition
        instrumentation.runOnMainSync { viewModel.undo() }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.transitions?.isEmpty() == true
        }

        // Redo transition
        instrumentation.runOnMainSync { viewModel.redo() }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.transitions?.any { it.type == TransitionType.WipeUp } == true
        }

        // Persistence
        viewModel.awaitPendingSavesForTest()
        val loaded = repository.loadDocument(project.id).getOrNull()
        assertNotNull(loaded)
        assertEquals(TransitionType.WipeUp, loaded!!.timeline.transitions.first().type)
    }

    @Test
    fun clip_and_overlay_keyframing_undo_redo_and_persistence() = runBlocking {
        val kf1 = VisualKeyframe("kf1", 0L, 0.2f, KeyframeInterpolation.Linear)
        val kf2 = VisualKeyframe("kf2", 1_000_000L, 0.8f, KeyframeInterpolation.Smooth)

        // Keyframe overlay opacity
        instrumentation.runOnMainSync {
            viewModel.selectOverlay("o1")
            viewModel.setOverlayKeyframe("o1", "opacity", kf1)
            viewModel.setOverlayKeyframe("o1", "opacity", kf2)
        }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.keyframeTracks?.get("opacity")?.keyframes?.size == 2
        }

        val overlay = viewModel.uiState.value.project!!.timeline.overlayClips.first()
        val opacityTrack = overlay.keyframeTracks["opacity"]
        assertNotNull(opacityTrack)
        assertEquals(2, opacityTrack!!.keyframes.size)
        assertEquals(KeyframeInterpolation.Smooth, opacityTrack.keyframes[1].interpolation)

        // Undo second keyframe
        instrumentation.runOnMainSync { viewModel.undo() }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.keyframeTracks?.get("opacity")?.keyframes?.size == 1
        }

        // Redo second keyframe
        instrumentation.runOnMainSync { viewModel.redo() }
        compose.waitUntil(5_000) {
            viewModel.uiState.value.project?.timeline?.overlayClips?.first()?.keyframeTracks?.get("opacity")?.keyframes?.size == 2
        }

        viewModel.awaitPendingSavesForTest()
        val loaded = repository.loadDocument(project.id).getOrNull()
        assertNotNull(loaded)
        val loadedTrack = loaded!!.timeline.overlayClips.first().keyframeTracks["opacity"]
        assertNotNull(loadedTrack)
        assertEquals(2, loadedTrack!!.keyframes.size)
        assertEquals(KeyframeInterpolation.Smooth, loadedTrack.keyframes[1].interpolation)
    }
}
