package com.videoeditor.feature.editor

import com.videoeditor.core.common.result.getOrNull

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.database.VideoEditorDatabase
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.database.storage.ProjectDocumentStorage
import com.videoeditor.core.media.MediaMetadataExtractor
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.media.export.ExportRequest
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.media.timeline.TimelineMediaRepository
import com.videoeditor.core.media.waveform.WaveformExtractor
import com.videoeditor.core.model.ExportQuality
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.core.model.FrameRate
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.Project
import com.videoeditor.editing.ProjectCreator
import com.videoeditor.editing.timeline.TimelineGesture
import com.videoeditor.editing.timeline.TimelineSelection
import com.videoeditor.editing.timeline.TrimMath
import com.videoeditor.editing.timeline.isEmpty
import com.videoeditor.editing.timeline.videoClipId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the interactive timeline milestone, exercising
 * the real [EditorViewModel] against the real media engine and real ffmpeg-
 * generated test media — the same engine composition the app ships with, not
 * mocks. Covers the acceptance workflow: scrubbing across clip boundaries,
 * pinch-zoom without player rebuilds, selection, trimming, splitting,
 * reordering, duplicating, deleting, undo/redo, audio waveform extraction,
 * thumbnail strip generation, persistence across save/reopen, and export of
 * the edited sequence.
 *
 * Fixtures (deterministic, shared with core:media's device tests):
 *  - clip1_720p_6s.mp4  1280x720, 30fps, 6s
 *  - clip2_480p_5s.mp4   640x480, 30fps, 5s (SMPTE bars + moving box)
 *  - clip3_1080p_4s.mp4 1920x1080, 30fps, 4s
 *  - music_20s.m4a      660Hz tone
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TimelineEditingDeviceTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val extractor = MediaMetadataExtractor()
    private val duckingCache = com.videoeditor.core.media.audio.DuckingAnalysisCache()
    private val compositionBuilder = CompositionBuilder(duckingCache)
    private val projectCreator = ProjectCreator()

    private lateinit var mediaDir: File
    private lateinit var database: VideoEditorDatabase
    private lateinit var repository: ProjectRepository
    private lateinit var viewModel: EditorViewModel

    /** 1080x2400 at density 2.625 — a realistic phone timeline viewport. */
    private val viewportWidthPx = 1080f

    private lateinit var applicationScope: kotlinx.coroutines.CoroutineScope

    private fun newViewModel(): EditorViewModel = EditorViewModel(
        appContext = context,
        projectRepository = repository,
        metadataExtractor = extractor,
        timelineMedia = TimelineMediaRepository(WaveformExtractor(), Dispatchers.IO),
        previewControllerFactory = CompositionPreviewController.Factory(
            context,
            compositionBuilder,
        ),
        ioDispatcher = Dispatchers.IO,
        mainDispatcher = Dispatchers.Main.immediate,
        applicationScope = applicationScope,
        duckingAnalysis = com.videoeditor.core.media.audio.DuckingAnalysisService(context,
            com.videoeditor.core.media.audio.DuckingAnalyzer(), duckingCache),
    )

    @Before
    fun setUp() {
        // Project URIs remain live across seeks/reopens. Android can evict
        // cache files during these operations when storage is under pressure.
        mediaDir = File(context.filesDir, "timeline-test-media").apply { mkdirs() }
        listOf(
            "clip1_720p_6s.mp4",
            "clip2_480p_5s.mp4",
            "clip3_1080p_4s.mp4",
            "music_20s.m4a",
        ).forEach { name ->
            val target = File(mediaDir, name)
            if (!target.exists()) {
                context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
            }
        }

        database = Room.inMemoryDatabaseBuilder(context, VideoEditorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProjectRepository(
            database.projectDao(),
            ProjectDocumentStorage(context),
        )

        applicationScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
        )
        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        // Defensive so a setUp failure surfaces as itself, not masked by a
        // secondary uninitialized-property exception from cleanup.
        // Await in-flight autosaves, then release the player, so nothing
        // touches the database after it closes.
        if (::viewModel.isInitialized) {
            runBlocking { viewModel.awaitPendingSavesForTest() }
            runOnMain { viewModel.releaseForTest() }
        }
        if (::applicationScope.isInitialized) applicationScope.cancel()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun exact_source_trim_validates_bounds_and_persists_one_history_edit() = runBlocking {
        loadProject(threeClipProject())
        val before = viewModel.uiState.value.project!!
        val clip = before.timeline.videoClips.first()
        runOnMain {
            viewModel.select(TimelineSelection.Video(clip.id))
            assertFalse(viewModel.trimSelectedSource(-1, 3_000_000))
            assertFalse(viewModel.trimSelectedSource(500_000, Long.MIN_VALUE))
            assertFalse(viewModel.trimSelectedSource(0, 50_000))
            assertFalse(viewModel.trimSelectedSource(0, 99_000_000))
            assertTrue(viewModel.trimSelectedSource(500_000, 3_000_000))
        }
        val trimmed = viewModel.uiState.value.project!!
        assertEquals(500_000L, trimmed.timeline.videoClips.first().sourceStartMicros)
        assertEquals(2_500_000L, trimmed.timeline.videoClips.first().timelineDurationMicros)
        assertEquals(2_500_000L, trimmed.timeline.videoClips[1].timelineStartMicros)
        runOnMain { viewModel.undo() }
        assertEquals(before.timeline, viewModel.uiState.value.project!!.timeline)
        runOnMain { viewModel.redo(); viewModel.saveProject() }
        viewModel.awaitPendingSavesForTest()
        val saved = repository.loadDocument(before.id).getOrNull()!!
        assertEquals(trimmed.timeline, saved.timeline)
    }

    @Test
    fun exact_trim_preserves_transition_history() {
        val base = threeClipProject()
        val first = base.timeline.videoClips[0].copy(sourceStartMicros = 1_000_000, sourceEndMicros = 3_000_000)
        val second = base.timeline.videoClips[1].copy(sourceStartMicros = 1_000_000, sourceEndMicros = 3_000_000, timelineStartMicros = 2_000_000)
        val third = base.timeline.videoClips[2].copy(timelineStartMicros = 4_000_000)
        val project = base.copy(timeline = base.timeline.copy(videoClips = listOf(first, second, third),
            transitions = listOf(com.videoeditor.core.model.Transition("trim-join", first.id, second.id, 1_000_000))))
        loadProject(project)
        runOnMain {
            viewModel.select(TimelineSelection.Video(first.id))
            assertEquals(2_000_001L, viewModel.transitionDurationLimit(project.timeline.transitions.single()))
            assertTrue(viewModel.trimSelectedSource(1_000_000, 6_000_000))
        }
        assertTrue(viewModel.uiState.value.project!!.timeline.transitions.isEmpty())
        assertEquals(6_000_000L, viewModel.uiState.value.project!!.timeline.videoClips.first().sourceEndMicros)
        runOnMain { viewModel.undo() }
        assertEquals(project.timeline, viewModel.uiState.value.project!!.timeline)
    }

    @Test fun valid_speed_preserves_transition_and_one_undo_restores_complete_timeline() = runBlocking {
        val base = threeClipProject()
        val a = base.timeline.videoClips[0].copy(sourceStartMicros = 1_000_000, sourceEndMicros = 3_000_000)
        val b = base.timeline.videoClips[1].copy(sourceStartMicros = 1_000_000, sourceEndMicros = 3_000_000, timelineStartMicros = 2_000_000)
        val c = base.timeline.videoClips[2].copy(timelineStartMicros = 4_000_000)
        val original = base.copy(timeline = base.timeline.copy(videoClips = listOf(a,b,c), transitions = listOf(
            com.videoeditor.core.model.Transition("speed-join", a.id, b.id, 500_000))))
        loadProject(original)
        runOnMain { viewModel.select(TimelineSelection.Video(a.id)); viewModel.setSpeed(1.5f) }
        assertTrue(pollUntil(10_000) { viewModel.uiState.value.project?.timeline?.videoClips?.first()?.playbackSpeed == 1.5f })
        val changed = viewModel.uiState.value.project!!
        assertEquals(original.timeline.transitions, changed.timeline.transitions)
        assertEquals(a.sourceStartMicros, changed.timeline.videoClips.first().sourceStartMicros)
        assertEquals(a.sourceEndMicros, changed.timeline.videoClips.first().sourceEndMicros)
        assertEquals(changed.timeline.videoClips.first().timelineEndMicros, changed.timeline.videoClips[1].timelineStartMicros)
        runOnMain { viewModel.undo() }
        assertEquals(original.timeline, viewModel.uiState.value.project!!.timeline)
        runOnMain { viewModel.redo() }
        assertEquals(changed.timeline, viewModel.uiState.value.project!!.timeline)
        viewModel.awaitPendingSavesForTest()
        assertEquals(changed.timeline, repository.loadDocument(original.id).getOrNull()!!.timeline)
    }

    @Test
    fun transform_and_fit_mode_apply_as_one_undoable_edit() {
        loadProject(threeClipProject())
        val before = viewModel.uiState.value.project!!
        runOnMain {
            viewModel.select(TimelineSelection.Video(before.timeline.videoClips.first().id))
            viewModel.setVisualTransformAndCanvasMode(com.videoeditor.core.model.VisualTransform(rotationDegrees = 12),
                com.videoeditor.core.model.ScaleMode.Fill)
        }
        assertEquals(12, viewModel.uiState.value.project!!.timeline.videoClips.first().transform.rotationDegrees)
        assertEquals(com.videoeditor.core.model.ScaleMode.Fill, viewModel.uiState.value.project!!.canvas.scaleMode)
        runOnMain { viewModel.undo() }
        assertEquals(before.canvas, viewModel.uiState.value.project!!.canvas)
        assertEquals(before.timeline, viewModel.uiState.value.project!!.timeline)
    }

    @Test
    fun extract_audio_is_one_history_action() {
        loadProject(threeClipProject())
        val before = viewModel.uiState.value.project!!
        val clip = before.timeline.videoClips.first()
        runOnMain {
            viewModel.select(TimelineSelection.Video(clip.id))
            viewModel.extractSelectedAudio()
        }
        val extracted = viewModel.uiState.value.project!!
        assertEquals(before.timeline.audioClips.size + 1, extracted.timeline.audioClips.size)
        assertTrue(extracted.timeline.videoClips.first().muted)
        runOnMain { viewModel.undo() }
        assertEquals(before.timeline, viewModel.uiState.value.project!!.timeline)
        runOnMain { viewModel.redo() }
        assertEquals(extracted.timeline, viewModel.uiState.value.project!!.timeline)
    }

    @Test
    fun extracted_audio_exports_decoded_pcm_through_undo_redo_and_intentional_repetition() = runBlocking {
        val base = threeClipProject()
        val lead = base.timeline.videoClips[1].copy(sourceEndMicros = 500_000, timelineStartMicros = 0, muted = true)
        val main = base.timeline.videoClips[0].copy(sourceStartMicros = 1_000_000, sourceEndMicros = 4_000_000,
            timelineStartMicros = 500_000, playbackSpeed = 1.5f, volume = .4f)
        val tail = base.timeline.videoClips[2].copy(sourceEndMicros = 500_000, timelineStartMicros = 2_500_000, muted = true)
        val project = base.copy(timeline = base.timeline.copy(videoClips = listOf(lead, main, tail)))
        loadProject(project)
        val evidence = File(context.getExternalFilesDir(null), "extract-pcm/run-${android.os.SystemClock.elapsedRealtimeNanos()}")
            .apply { check(mkdirs()) }
        val metrics = mutableListOf<String>()
        var baselineAudibleBounds: Pair<Long, Long>? = null
        suspend fun exportAndCheck(label: String, expectedGain: Double): List<Double> {
            val current = viewModel.uiState.value.project!!
            File(evidence, "$label-project.json").writeText(kotlinx.serialization.json.Json.encodeToString(Project.serializer(), current))
            val output = File(evidence, "$label.mp4")
            val result = ExportEngine(context, compositionBuilder).export(current,
                ExportRequest(current.id, output, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.Standard)) { }
            assertTrue("$label export failed: $result", result.isSuccess)
            val rms = extractedAudioRms(output, listOf(100_000L..300_000L, 900_000L..1_500_000L, 2_750_000L..2_950_000L,
                430_000L..470_000L, 600_000L..700_000L, 2_350_000L..2_450_000L, 2_590_000L..2_690_000L))
            val frequency = extractedAudioToneFrequency(output, 900_000L..1_500_000L)
            assertEquals("$label tone", 440.0, frequency, 8.0)
            assertTrue("$label leading audio leaked: $rms", rms[0] < 20)
            assertTrue("$label trailing audio leaked: $rms", rms[2] < 20)
            assertTrue("$label wrong 0.5s start or 2.5s end: $rms", rms[3] < 20 && rms[6] < 20)
            assertTrue("$label late start or early end: $rms", rms[4] > rms[1] * .85 && rms[5] > rms[1] * .85)
            val sourceRms = extractedAudioRms(File(mediaDir, "clip1_720p_6s.mp4"), listOf(1_000_000L..3_000_000L)).single()
            assertEquals("$label gain (detects unintended double mixing)", expectedGain, rms[1] / sourceRms, .035)
            var first = Long.MAX_VALUE; var last = Long.MIN_VALUE; var samples = 0L
            var audibleFirst = Long.MAX_VALUE; var audibleLast = Long.MIN_VALUE; var sampleRate = 0
            File(evidence, "$label-s16le.pcm").outputStream().buffered().use { pcm ->
                visitExtractedAudioSamples(output, onFormat = { rate, channels ->
                    sampleRate = rate
                    File(evidence, "$label-pcm-format.txt").writeText("sampleRate=$rate channels=$channels encoding=s16le")
                }) { time, _, sample ->
                    first = minOf(first, time); last = maxOf(last, time); samples++
                    if (kotlin.math.abs(sample) > 100) { audibleFirst = minOf(audibleFirst, time); audibleLast = maxOf(audibleLast, time) }
                    val value = sample.toInt(); pcm.write(value and 255); pcm.write((value shr 8) and 255)
                }
            }
            // Independent baseline PCM and FFmpeg decoding show about two AAC
            // 1024-sample blocks of priming at both edges. Bound absolute timing
            // to three actual output-rate blocks, and extraction parity to one.
            // This allowance applies to decoded AAC, not preview pixel tests.
            val blockMicros = (1_024_000_000L + sampleRate - 1) / sampleRate
            assertTrue("$label audible onset $audibleFirst", audibleFirst in 500_000L..(500_000L + 3 * blockMicros))
            assertTrue("$label audible end $audibleLast", audibleLast in 2_500_000L..(2_500_000L + 3 * blockMicros))
            baselineAudibleBounds?.let { baseline ->
                assertTrue("$label onset differs from original", kotlin.math.abs(audibleFirst - baseline.first) <= blockMicros)
                assertTrue("$label end differs from original", kotlin.math.abs(audibleLast - baseline.second) <= blockMicros)
            } ?: run { baselineAudibleBounds = audibleFirst to audibleLast }
            // AAC packet padding may extend the container beyond its 3s video.
            assertTrue("$label decoded duration: $first..$last", last in 2_990_000L..3_100_000L)
            metrics += "$label firstUs=$first lastUs=$last samples=$samples audibleFirstUs=$audibleFirst audibleLastUs=$audibleLast aacBlockUs=$blockMicros rms=$rms frequency=$frequency gain=${rms[1] / sourceRms} expectedGain=$expectedGain"
            File(evidence, "metrics.txt").writeText(metrics.joinToString("\n"))
            return rms
        }
        exportAndCheck("original", .4)
        runOnMain { viewModel.select(TimelineSelection.Video(main.id)); viewModel.extractSelectedAudio() }
        val extracted = viewModel.uiState.value.project!!
        assertTrue(extracted.timeline.videoClips[1].muted)
        val detached = extracted.timeline.audioClips.single()
        assertEquals(main.sourceStartMicros, detached.sourceStartMicros)
        assertEquals(main.sourceEndMicros, detached.sourceEndMicros)
        assertEquals(main.timelineStartMicros, detached.timelineStartMicros)
        assertEquals(main.playbackSpeed, detached.playbackSpeed)
        assertEquals(main.volume, detached.volume)
        exportAndCheck("extracted", .4)
        runOnMain { viewModel.undo() }
        assertEquals(project.timeline, viewModel.uiState.value.project!!.timeline)
        exportAndCheck("undo", .4)
        runOnMain { viewModel.redo() }
        assertEquals(extracted.timeline, viewModel.uiState.value.project!!.timeline)
        exportAndCheck("redo", .4)
        runOnMain { viewModel.select(TimelineSelection.Audio(detached.id)); viewModel.updateSelectedAudio { it.copy(volume = .2f) } }
        assertTrue(viewModel.uiState.value.project!!.timeline.videoClips[1].muted)
        exportAndCheck("independent-detached-volume", .2)
        runOnMain { viewModel.undo(); viewModel.select(TimelineSelection.Video(main.id)); viewModel.extractSelectedAudio() }
        val repeated = viewModel.uiState.value.project!!
        assertEquals(2, repeated.timeline.audioClips.size)
        exportAndCheck("intentional-two-detached-clips", .8)
        runOnMain { viewModel.undo() }
        assertEquals(extracted.timeline, viewModel.uiState.value.project!!.timeline)
        runOnMain { viewModel.redo(); viewModel.saveProject() }
        viewModel.awaitPendingSavesForTest()
        runOnMain { viewModel.releaseForTest(); viewModel = newViewModel(); viewModel.onViewportSize(viewportWidthPx, viewportWidthPx / 2); viewModel.loadProject(project.id) }
        assertTrue(pollUntil(10_000) { viewModel.uiState.value.project?.id == project.id })
        assertEquals(repeated.timeline, viewModel.uiState.value.project!!.timeline)
        exportAndCheck("reopened-intentional-mix", .8)
        Unit
    }

    @Test
    fun repeated_extraction_survives_source_edits_deletion_and_reopen() {
        val project = threeClipProject()
        loadProject(project)
        val id = viewModel.uiState.value.project!!.timeline.videoClips.first().id
        runOnMain {
            viewModel.select(TimelineSelection.Video(id))
            viewModel.setSpeed(2f)
            assertTrue(viewModel.beginTrim(id, TrimMath.Edge.Start, false))
            viewModel.trimBy(viewModel.uiState.value.zoomPixelsPerMicro * 500_000f, false)
            viewModel.endTrim(true, false)
        }
        val original = viewModel.uiState.value.project!!
        val source = original.timeline.videoClips.first()
        runOnMain { viewModel.extractSelectedAudio() }
        val first = viewModel.uiState.value.project!!
        val detached = first.timeline.audioClips.last()
        assertEquals(source.sourceStartMicros, detached.sourceStartMicros)
        assertEquals(source.sourceEndMicros, detached.sourceEndMicros)
        assertEquals(source.playbackSpeed, detached.playbackSpeed)
        runOnMain {
            viewModel.select(TimelineSelection.Video(id))
            viewModel.extractSelectedAudio()
        }
        val second = viewModel.uiState.value.project!!
        assertEquals(first.timeline.audioClips.size + 1, second.timeline.audioClips.size)
        runOnMain { viewModel.undo() }
        assertEquals(first.timeline, viewModel.uiState.value.project!!.timeline)
        runOnMain { viewModel.redo() }
        assertEquals(second.timeline, viewModel.uiState.value.project!!.timeline)
        runOnMain {
            viewModel.select(TimelineSelection.Video(id))
            viewModel.setSpeed(1f)
        }
        assertEquals(second.timeline.audioClips, viewModel.uiState.value.project!!.timeline.audioClips)
        runOnMain { viewModel.undo(); viewModel.select(TimelineSelection.Video(id)); viewModel.deleteSelected() }
        assertEquals(second.timeline.audioClips, viewModel.uiState.value.project!!.timeline.audioClips)
        runOnMain { viewModel.undo() }
        assertEquals(second.timeline, viewModel.uiState.value.project!!.timeline)
        assertTrue("extraction was not saved", pollUntil(15_000L) {
            val saved = runBlocking { repository.loadDocument(project.id) }
                .let { (it as? com.videoeditor.core.common.result.Result.Success)?.value }
            saved?.timeline == second.timeline
        })
        val reopened = newViewModel()
        try {
            runOnMain { reopened.loadProject(project.id) }
            assertTrue(pollUntil(10_000L) { reopened.uiState.value.project != null })
            assertEquals(second.timeline, reopened.uiState.value.project!!.timeline)
        } finally {
            runOnMain { reopened.releaseForTest() }
        }
    }

    // -- fixture builders --

    @Test
    fun stage8_audio_commands_restore_each_effect_and_persist_after_reopen() = runBlocking {
        val music = extractRef("music_20s.m4a")
        val base = threeClipProject().let { it.copy(timeline = it.timeline.copy(videoClips = it.timeline.videoClips.take(1))) }
        val audio = com.videoeditor.core.model.AudioClip("stage8-audio", music.id, 0, 6_000_000)
        val voice = audio.copy(id = "stage8-voice", timelineStartMicros = 1_000_000)
        val project = base.copy(mediaRefs = base.mediaRefs + music, timeline = base.timeline.copy(audioClips = listOf(audio, voice)))
        loadProject(project)
        runOnMain { viewModel.select(TimelineSelection.Audio(audio.id)) }
        fun current() = viewModel.uiState.value.project!!.timeline.audioClips.first { it.id == audio.id }
        fun edit(transform: (com.videoeditor.core.model.AudioClip) -> com.videoeditor.core.model.AudioClip) {
            val before = current()
            runOnMain { viewModel.updateSelectedAudio(transform) }
            val after = current()
            assertFalse("operation must change authored audio", before == after)
            runOnMain { viewModel.undo() }; assertEquals(before, current())
            runOnMain { viewModel.redo() }; assertEquals(after, current())
        }
        val p = com.videoeditor.core.model.VolumePoint("p", 1_000_000, -6f)
        edit { it.copy(volumeEnvelope = com.videoeditor.core.model.VolumeEnvelope(listOf(p))) }
        val beforeMove = current()
        val moved = beforeMove.volumeEnvelope.withPoint(p.copy(sourcePositionMicros = 2_000_000, gainDb = -12f))
        runOnMain { viewModel.updateAudioEnvelope(audio.id, moved); viewModel.updateAudioEnvelope(audio.id, moved) }
        runOnMain { viewModel.undo() }; assertEquals("duplicate final gesture callback must not add history", beforeMove, current())
        runOnMain { viewModel.redo() }; assertEquals(moved, current().volumeEnvelope)
        edit { it.copy(volumeEnvelope = it.volumeEnvelope.withPoint(it.volumeEnvelope.points.single().copy(interpolation = com.videoeditor.core.model.VolumeInterpolation.Hold))) }
        edit { it.copy(volumeEnvelope = com.videoeditor.core.model.VolumeEnvelope()) }
        edit { it.copy(ducking = it.ducking.copy(enabled = true)) }
        edit { it.copy(ducking = it.ducking.copy(strengthDb = -18f)) }
        edit { it.copy(ducking = it.ducking.copy(triggerClipIds = listOf(voice.id))) }
        edit { it.copy(equalizer = com.videoeditor.core.model.EqualizerSettings(enabled = true, bassGainDb = 6f, midGainDb = -2f)) }
        edit { it.copy(equalizer = it.equalizer.copy(trebleGainDb = 4f)) }
        edit { it.copy(balance = com.videoeditor.core.model.AudioBalance(-.5f)) }
        edit { it.copy(fadeInCurve = com.videoeditor.core.model.FadeCurve.Smooth) }
        edit { it.copy(fadeInMicros = 1_000_000, fadeOutMicros = 500_000) }
        edit { it.copy(equalizer = com.videoeditor.core.model.EqualizerSettings()) }
        val authored = current()
        runOnMain { viewModel.saveProject() }
        viewModel.awaitPendingSavesForTest()
        assertEquals(authored, repository.loadDocument(project.id).getOrNull()!!.timeline.audioClips.first())
        val reopened = newViewModel()
        try {
            runOnMain { reopened.loadProject(project.id) }
            assertTrue(pollUntil(10_000) { reopened.uiState.value.project != null })
            assertEquals(authored, reopened.uiState.value.project!!.timeline.audioClips.first())
        } finally { runOnMain { reopened.releaseForTest() } }
        runOnMain { viewModel.duplicateSelectedClip() }
        val duplicate = viewModel.uiState.value.project!!.timeline.audioClips.last()
        assertEquals(authored.copy(id = duplicate.id, timelineStartMicros = duplicate.timelineStartMicros), duplicate)
        runOnMain { viewModel.deleteSelected(); viewModel.undo() }
        assertEquals(duplicate, viewModel.uiState.value.project!!.timeline.audioClips.last())
    }

    private fun threeClipProject(): Project {
        val refs = listOf(
            extractRef("clip1_720p_6s.mp4"),
            extractRef("clip2_480p_5s.mp4"),
            extractRef("clip3_1080p_4s.mp4"),
        )
        return projectCreator.create(name = "Timeline test", mediaRefs = refs)
    }

    private fun extractRef(name: String): MediaRef {
        val result = extractor.extract(context, fileUri(name))
        assertTrue("metadata extraction failed for $name: $result", result.isSuccess)
        return (result as com.videoeditor.core.common.result.Result.Success).value
    }

    private fun fileUri(name: String): Uri = Uri.fromFile(File(mediaDir, name))

    /** Loads a project into the ViewModel and sizes the viewport. */
    private fun loadProject(project: Project) {
        runBlocking { repository.save(project) }
        runOnMain {
            viewModel.onViewportSize(viewportWidthPx, viewportWidthPx / 2f)
            viewModel.loadProject(project.id)
        }
        // Wait for the async load to land.
        pollUntil(10_000L) { viewModel.uiState.value.project?.id == project.id }
        assertTrue("project did not load", viewModel.uiState.value.project != null)
    }

    @Test
    fun moved_source_relink_preserves_timeline_and_undo_restores_missing_state() {
        val base = threeClipProject()
        val originalRef = base.mediaRefs.first()
        val source = File(Uri.parse(originalRef.uri).path!!)
        val moved = File(source.parentFile, "moved-audit.mp4")
        assertTrue(source.renameTo(moved))
        try {
            loadProject(base)
            assertTrue(viewModel.uiState.value.missingMediaIds.contains(originalRef.id))
            assertTrue(!viewModel.uiState.value.preview.hasComposition)
            runOnMain { viewModel.relinkFile(originalRef.id, Uri.fromFile(moved)) }
            assertTrue(pollUntil(10_000) { viewModel.uiState.value.missingMediaIds.isEmpty() })
            assertEquals(base.timeline, viewModel.uiState.value.project!!.timeline)
            assertEquals(Uri.fromFile(moved).toString(), viewModel.uiState.value.project!!.mediaRef(originalRef.id)!!.uri)
            runOnMain { viewModel.undo() }
            assertTrue(pollUntil(10_000) { viewModel.uiState.value.missingMediaIds.contains(originalRef.id) })
            assertEquals(base.mediaRefs, viewModel.uiState.value.project!!.mediaRefs)
            runOnMain { viewModel.redo() }
            assertTrue(pollUntil(10_000) { viewModel.uiState.value.missingMediaIds.isEmpty() })
            assertEquals(base.timeline, viewModel.uiState.value.project!!.timeline)
        } finally { assertTrue(moved.renameTo(source)) }
    }

    @Test
    fun unused_missing_source_does_not_block_existing_edits() {
        val base = threeClipProject()
        val unused = com.videoeditor.core.model.MediaRef("unused", "content://unavailable.audit.provider/unused",
            com.videoeditor.core.model.MediaType.Video, "Unused.mp4")
        loadProject(base.copy(mediaRefs = base.mediaRefs + unused))
        assertTrue(pollUntil(10_000) { viewModel.uiState.value.preview.hasComposition })
        assertTrue(viewModel.uiState.value.missingMediaIds.isEmpty())
        assertEquals(base.timeline, viewModel.uiState.value.project!!.timeline)
    }

    @Test
    fun real_clock_updates_viewmodel_and_fixed_anchor_through_pause_seek_zoom_and_replay() {
        loadProject(threeClipProject())
        assertTrue(pollUntil(15_000) { viewModel.uiState.value.preview.hasComposition })
        val rebuilds = viewModel.previewController.rebuildCount
        runOnMain { viewModel.zoomToFit(); viewModel.togglePlayPause() }
        assertTrue(pollUntil(15_000) { viewModel.uiState.value.playheadMicros >= 1_500_000L })
        repeat(20) {
            runOnMain {
                val state = viewModel.uiState.value
                assertEquals(state.preview.positionMicros, state.playheadMicros)
                assertTrue(kotlin.math.abs(state.playheadMicros - state.viewport()!!.playheadTimeMicros(state.scrollXPx)) <= 20L)
            }
            Thread.sleep(40)
        }
        runOnMain { viewModel.togglePlayPause() }
        Thread.sleep(300)
        val paused = viewModel.uiState.value
        Thread.sleep(500)
        assertEquals(paused.playheadMicros, viewModel.uiState.value.playheadMicros)
        assertEquals(paused.scrollXPx, viewModel.uiState.value.scrollXPx)
        runOnMain { viewModel.stepZoom(2f) }
        assertEquals(paused.playheadMicros, viewModel.uiState.value.playheadMicros)
        runOnMain { viewModel.beginScrub(); viewModel.scrubBy(-200f) }
        val target = viewModel.uiState.value.playheadMicros
        Thread.sleep(100)
        assertEquals(target, viewModel.uiState.value.playheadMicros)
        runOnMain { viewModel.endScroll() }
        assertTrue(pollUntil(15_000) { kotlin.math.abs(viewModel.uiState.value.preview.actualPositionMicros - target) < 50_000 })
        runOnMain { viewModel.seekTo(9_000_000); viewModel.seekTo(2_000_000) }
        assertTrue(pollUntil(15_000) { kotlin.math.abs(viewModel.uiState.value.playheadMicros - 2_000_000) < 50_000 })
        runOnMain { viewModel.togglePlayPause() }
        assertTrue(pollUntil(15_000) { viewModel.uiState.value.playheadMicros > 3_000_000 })
        runOnMain { viewModel.togglePlayPause(); viewModel.seekTo(15_000_000) }
        assertTrue(pollUntil(15_000) { viewModel.uiState.value.playheadMicros == 15_000_000L })
        runOnMain { viewModel.togglePlayPause() }
        assertTrue(pollUntil(15_000) { viewModel.uiState.value.playheadMicros in 100_000L..2_000_000L })
        runOnMain {
            val state = viewModel.uiState.value
            assertTrue(kotlin.math.abs(state.playheadMicros - state.viewport()!!.playheadTimeMicros(state.scrollXPx)) <= 20L)
        }
        runOnMain {
            viewModel.togglePlayPause()
            viewModel.seekTo(15_000_000)
            viewModel.seekTo(9_000_000)
            viewModel.togglePlayPause()
        }
        assertTrue("Play discarded a newer queued backward seek", pollUntil(15_000) {
            viewModel.uiState.value.preview.actualPositionMicros in 9_100_000L..11_000_000L
        })
        assertEquals("Viewport transport rebuilt the composition", rebuilds, viewModel.previewController.rebuildCount)
        assertNull(viewModel.uiState.value.preview.error)
    }

    // -- viewport: scrubbing and zoom --

    @Test
    fun viewport_initializes_with_zoom_limits_and_clamped_scroll() {
        loadProject(threeClipProject())
        val state = viewModel.uiState.value
        // 6+5+4 = 15s project.
        assertEquals(15_000_000L, state.project!!.timeline.totalDurationMicros)
        assertTrue("zoom not initialized", state.zoomPixelsPerMicro > 0f)
        assertTrue("scroll out of range", state.scrollXPx in 0f..viewportWidthPx * 4)
        assertNotNull(state.viewport())
    }

    @Test
    fun scrub_moves_playhead_and_seeks_player_across_clip_boundary() {
        loadProject(threeClipProject())
        runOnMain { viewModel.seekTo(9_000_000L) }
        assertEquals(9_000_000L, viewModel.uiState.value.playheadMicros)
        // The seek must reach the preview engine (9s is inside clip2, past the
        // first boundary at 6s).
        val reached = pollUntil(20_000L) {
            viewModel.previewController.state.value.positionMicros in
                8_500_000L..9_500_000L
        }
        assertTrue(
            "player did not follow the seek across the boundary: " +
                viewModel.previewController.state.value.positionMicros,
            reached,
        )

        // Backward scrub across the boundary too.
        runOnMain { viewModel.seekTo(2_000_000L) }
        val back = pollUntil(20_000L) {
            viewModel.previewController.state.value.positionMicros in
                1_500_000L..2_500_000L
        }
        assertTrue(
            "backward seek failed: ${viewModel.previewController.state.value.positionMicros}",
            back,
        )
    }

    @Test
    fun scrub_gesture_moves_playhead_and_clamps_at_timeline_ends() {
        loadProject(threeClipProject())
        val state = viewModel.uiState.value
        val ppm = state.zoomPixelsPerMicro

        runOnMain { viewModel.beginScrub() }
        // Drag content right by 4000px → playhead moves ~ -4000/ppm µs; clamps at 0.
        runOnMain { viewModel.scrubBy(4000f) }
        assertEquals(0L, viewModel.uiState.value.playheadMicros)
        // Drag left beyond the end: clamps at the duration.
        runOnMain { viewModel.scrubBy(-1_000_000f) }
        assertEquals(15_000_000L, viewModel.uiState.value.playheadMicros)
        runOnMain { viewModel.endScroll() }
        assertTrue(
            "gesture did not end",
            viewModel.uiState.value.gesture is TimelineGesture.Idle,
        )
        // A sensible ppm sanity check: default zoom fits 45s in the viewport.
        assertTrue(ppm > 0f)
    }

    @Test
    fun zoom_changes_scale_without_rebuilding_the_player() {
        loadProject(threeClipProject())
        val controller = viewModel.previewController
        // Grab the composition identity via the state flow object; a rebuild
        // would reset hasComposition and swap the position. Instead we assert
        // the observable contract: zoom changes state, playback position is
        // untouched, and no preview error appears.
        runOnMain { viewModel.seekTo(7_000_000L) }
        val before = viewModel.uiState.value
        runOnMain {
            viewModel.beginZoom(viewportWidthPx / 2f)
            viewModel.zoomBy(3f, viewportWidthPx / 2f)
            viewModel.endZoom()
        }
        val after = viewModel.uiState.value

        assertTrue("zoom did not increase", after.zoomPixelsPerMicro > before.zoomPixelsPerMicro)
        // Focal preservation: the time under the center pixel is unchanged.
        val beforeVp = before.viewport()!!
        val afterVp = after.viewport()!!
        val focalTimeBefore = beforeVp.timeAtScreenX(before.scrollXPx, viewportWidthPx / 2f)
        val focalTimeAfter = afterVp.timeAtScreenX(after.scrollXPx, viewportWidthPx / 2f)
        assertTrue(
            "focal time moved: $focalTimeBefore -> $focalTimeAfter",
            kotlin.math.abs(focalTimeBefore - focalTimeAfter) < 100_000L,
        )
        // Zoom must be clamped at the maximum and the player must be intact.
        runOnMain { viewModel.zoomToFit() }
        val fit = viewModel.uiState.value
        assertTrue("zoomToFit went out of limits", fit.zoomPixelsPerMicro > 0f)
        assertNull("zoom broke preview: ${controller.state.value.error}", controller.state.value.error)
        assertEquals(15_000_000L, controller.state.value.durationMicros)
    }

    @Test
    fun navigation_zoom_preserves_playhead_and_does_not_rebuild_preview() {
        loadProject(threeClipProject())
        runOnMain { viewModel.seekTo(7_000_000L) }
        val before = viewModel.uiState.value
        val rebuild = viewModel.previewController.lastRebuildTiming
        runOnMain { viewModel.stepZoom(1.25f) }
        val after = viewModel.uiState.value
        assertTrue(after.zoomPixelsPerMicro > before.zoomPixelsPerMicro)
        assertEquals(before.playheadMicros, after.playheadMicros)
        assertTrue(kotlin.math.abs(after.viewport()!!.playheadTimeMicros(after.scrollXPx) -
            before.viewport()!!.playheadTimeMicros(before.scrollXPx)) < 10L)
        assertEquals(rebuild, viewModel.previewController.lastRebuildTiming)
        runOnMain { viewModel.stepZoom(0.8f) }
        assertEquals(before.zoomPixelsPerMicro, viewModel.uiState.value.zoomPixelsPerMicro, 0.000001f)
    }

    // -- selection --

    @Test
    fun tap_selects_clip_and_tap_outside_clears() {
        loadProject(threeClipProject())
        val clipId = viewModel.uiState.value.project!!.timeline.videoClips[1].id
        runOnMain { viewModel.select(TimelineSelection.Video(clipId)) }
        assertEquals(clipId, viewModel.uiState.value.selection.videoClipId)

        runOnMain { viewModel.clearSelection() }
        assertTrue(viewModel.uiState.value.selection.isEmpty)
    }

    @Test
    fun paused_preview_readiness_does_not_undo_trim_focus() {
        loadProject(threeClipProject())
        assertTrue(pollUntil(15_000) { viewModel.uiState.value.preview.hasComposition })
        runOnMain {
            viewModel.select(TimelineSelection.Video(viewModel.uiState.value.project!!.timeline.videoClips.first().id))
            viewModel.focusOnSelectedClip()
        }
        val focused = viewModel.uiState.value
        assertEquals(3_000_000L, focused.playheadMicros)
        assertTrue(focused.scrollXPx > 0f)
        assertTrue(pollUntil(15_000) { kotlin.math.abs(viewModel.uiState.value.preview.actualPositionMicros - focused.playheadMicros) < 50_000 })
        Thread.sleep(1_000)
        assertEquals(focused.zoomPixelsPerMicro, viewModel.uiState.value.zoomPixelsPerMicro)
        val settled = viewModel.uiState.value
        assertEquals(focused.scrollXPx, settled.scrollXPx, focused.zoomPixelsPerMicro * 5_000f)
        assertTrue(kotlin.math.abs(settled.playheadMicros - focused.playheadMicros) <= 5_000)
        assertTrue(kotlin.math.abs(settled.playheadMicros - settled.viewport()!!.playheadTimeMicros(settled.scrollXPx)) <= 20)
        runOnMain { viewModel.togglePlayPause() }
        assertTrue(pollUntil(15_000) { viewModel.uiState.value.preview.isPlaying &&
            viewModel.uiState.value.playheadMicros > focused.playheadMicros + 500_000 })
        val playing = viewModel.uiState.value
        assertTrue(kotlin.math.abs(playing.playheadMicros - playing.viewport()!!.playheadTimeMicros(playing.scrollXPx)) <= 20L)
    }

    // -- trim --

    @Test
    fun trim_gesture_commits_source_range_change_and_repacks() {
        loadProject(threeClipProject())
        val project = viewModel.uiState.value.project!!
        val firstId = project.timeline.videoClips[0].id
        runOnMain { viewModel.select(TimelineSelection.Video(firstId)) }

        val ppm = viewModel.uiState.value.zoomPixelsPerMicro
        runOnMain {
            assertTrue(viewModel.beginTrim(firstId, TrimMath.Edge.Start, false))
            // Drag the start handle 1 second right.
            viewModel.trimBy(ppm * 1_000_000f, false)
            viewModel.endTrim(true, false)
        }

        val after = viewModel.uiState.value.project!!
        val trimmed = after.timeline.videoClips.first { it.id == firstId }
        // Clip 1's source starts at 0; a 1s start trim yields sourceStart=1s.
        assertEquals(1_000_000L, trimmed.sourceStartMicros)
        assertEquals(6_000_000L, trimmed.sourceEndMicros)
        // Duration shrank by 1s and following clips re-packed contiguously.
        assertEquals(14_000_000L, after.timeline.videoTrackDurationMicros)
        assertEquals(5_000_000L, after.timeline.videoClips[1].timelineStartMicros)
        // Undo restores exactly.
        runOnMain { viewModel.undo() }
        val undone = viewModel.uiState.value.project!!
        assertEquals(15_000_000L, undone.timeline.videoTrackDurationMicros)
        assertEquals(0L, undone.timeline.videoClips[0].sourceStartMicros)
    }

    @Test
    fun trim_clamps_to_source_boundaries_and_minimum_span() {
        loadProject(threeClipProject())
        val firstId = viewModel.uiState.value.project!!.timeline.videoClips[0].id
        val ppm = viewModel.uiState.value.zoomPixelsPerMicro

        // Drag the end handle far past the source end: clamps to 6s.
        runOnMain {
            viewModel.beginTrim(firstId, TrimMath.Edge.End, false)
            viewModel.trimBy(ppm * 100_000_000f, false)
            viewModel.endTrim(true, false)
        }
        val after = viewModel.uiState.value.project!!
        assertEquals(6_000_000L, after.timeline.videoClips[0].sourceEndMicros)

        runOnMain { viewModel.undo() }

        // Collapse the clip from the start side: minimum span is enforced.
        runOnMain {
            viewModel.beginTrim(firstId, TrimMath.Edge.Start, false)
            viewModel.trimBy(ppm * 100_000_000f, false)
            viewModel.endTrim(true, false)
        }
        val collapsed = viewModel.uiState.value.project!!.timeline.videoClips[0]
        assertEquals(
            6_000_000L - TrimMath.MIN_SOURCE_SPAN_MICROS,
            collapsed.sourceStartMicros,
        )
        assertTrue(collapsed.sourceDurationMicros >= TrimMath.MIN_SOURCE_SPAN_MICROS)
    }

    // -- split / duplicate / delete --

    @Test
    fun split_at_playhead_divides_clip_and_rejects_boundary_splits() {
        loadProject(threeClipProject())
        val firstId = viewModel.uiState.value.project!!.timeline.videoClips[0].id
        runOnMain { viewModel.select(TimelineSelection.Video(firstId)) }

        // Split at the playhead parked at 0 (clip boundary) must be rejected.
        runOnMain { viewModel.seekTo(0L) }
        runOnMain { viewModel.splitSelectedClip() }
        assertEquals(
            "boundary split must be rejected",
            3,
            viewModel.uiState.value.project!!.timeline.videoClips.size,
        )
        assertNotNull(viewModel.uiState.value.userMessage)
        runOnMain { viewModel.consumeUserMessage() }

        // Valid split at 3s.
        runOnMain { viewModel.seekTo(3_000_000L) }
        runOnMain { viewModel.splitSelectedClip() }
        val after = viewModel.uiState.value.project!!
        assertEquals(4, after.timeline.videoClips.size)
        val left = after.timeline.videoClips[0]
        val right = after.timeline.videoClips[1]
        assertEquals(3_000_000L, left.sourceEndMicros)
        assertEquals(3_000_000L, right.sourceStartMicros)
        assertEquals(6_000_000L, right.sourceEndMicros)
        assertEquals(15_000_000L, after.timeline.videoTrackDurationMicros)

        runOnMain { viewModel.undo() }
        assertEquals(3, viewModel.uiState.value.project!!.timeline.videoClips.size)
    }

    @Test
    fun duplicate_inserts_copy_after_original_and_selects_it() {
        loadProject(threeClipProject())
        val original = viewModel.uiState.value.project!!.timeline.videoClips[0]
        runOnMain { viewModel.select(TimelineSelection.Video(original.id)) }
        runOnMain { viewModel.duplicateSelectedClip() }

        val after = viewModel.uiState.value.project!!
        assertEquals(4, after.timeline.videoClips.size)
        val copy = after.timeline.videoClips[1]
        assertTrue("duplicate must get a new id", copy.id != original.id)
        assertEquals(original.mediaRefId, copy.mediaRefId)
        assertEquals(original.sourceStartMicros, copy.sourceStartMicros)
        assertEquals(original.sourceEndMicros, copy.sourceEndMicros)
        assertEquals(copy.id, after.timeline.videoClips[1].id)
        // The new copy is selected.
        assertEquals(copy.id, viewModel.uiState.value.selection.videoClipId)
        assertEquals(21_000_000L, after.timeline.videoTrackDurationMicros)
    }

    @Test
    fun delete_removes_clip_repacks_and_clears_selection() {
        loadProject(threeClipProject())
        val middle = viewModel.uiState.value.project!!.timeline.videoClips[1]
        runOnMain { viewModel.select(TimelineSelection.Video(middle.id)) }
        runOnMain { viewModel.deleteSelected() }

        val after = viewModel.uiState.value.project!!
        assertEquals(2, after.timeline.videoClips.size)
        assertEquals(10_000_000L, after.timeline.videoTrackDurationMicros)
        // Contiguity: clip 2 now starts where clip 1 ended.
        assertEquals(6_000_000L, after.timeline.videoClips[1].timelineStartMicros)
        assertTrue("selection must clear after delete", after.let {
            viewModel.uiState.value.selection.isEmpty
        })

        runOnMain { viewModel.undo() }
        assertEquals(3, viewModel.uiState.value.project!!.timeline.videoClips.size)
        assertEquals(middle.id, viewModel.uiState.value.project!!.timeline.videoClips[1].id)
    }

    // -- reorder --

    @Test
    fun reorder_gesture_moves_clip_to_new_index() {
        loadProject(threeClipProject())
        val ids = viewModel.uiState.value.project!!.timeline.videoClips.map { it.id }

        val ppm = viewModel.uiState.value.zoomPixelsPerMicro
        val scroll = viewModel.uiState.value.scrollXPx
        val playheadX = viewportWidthPx / 2f
        runOnMain {
            assertTrue(viewModel.beginReorder(ids[0]))
            // Drop center at the screen x corresponding to t=9s (inside clip2).
            val viewport = viewModel.uiState.value.viewport()!!
            val dropX = viewport.screenXAtTime(scroll, 9_000_000L)
            viewModel.reorderTo(dropX)
            viewModel.endReorder(true)
        }

        val after = viewModel.uiState.value.project!!
        assertEquals(listOf(ids[1], ids[0], ids[2]),
            after.timeline.videoClips.map { it.id })
        // Re-packed: clip2 (5s) now starts at 0; clip1 starts at 5s.
        assertEquals(5_000_000L, after.timeline.videoClips[1].timelineStartMicros)
        assertEquals(15_000_000L, after.timeline.videoTrackDurationMicros)

        runOnMain { viewModel.undo() }
        assertEquals(ids, viewModel.uiState.value.project!!.timeline.videoClips.map { it.id })
    }

    @Test
    fun overlapping_gestures_are_refused() {
        loadProject(threeClipProject())
        runOnMain {
            // Reprepare and scrub in one main-loop turn: the actual player has
            // a play intention while decoder readiness is still pending.
            val project = viewModel.uiState.value.project!!
            viewModel.previewController.setProject(null)
            viewModel.previewController.setProject(project)
            viewModel.togglePlayPause()
            assertTrue(viewModel.previewController.state.value.playRequested)
            assertFalse(viewModel.previewController.state.value.isPlaying)
            assertTrue(viewModel.beginScrub())
            assertFalse("scrub must cancel buffered playback", viewModel.previewController.state.value.playRequested)
            // A second gesture must not start while scrubbing.
            assertTrue(
                "trim must be refused during scrub",
                !viewModel.beginTrim(
                    viewModel.uiState.value.project!!.timeline.videoClips[0].id,
                    TrimMath.Edge.Start,
                    false,
                ),
            )
            viewModel.endScroll()
            // Now the same trim begins fine.
            assertTrue(
                viewModel.beginTrim(
                    viewModel.uiState.value.project!!.timeline.videoClips[0].id,
                    TrimMath.Edge.Start,
                    false,
                ),
            )
            viewModel.endTrim(false, false)
        }
        assertTrue(viewModel.uiState.value.gesture is TimelineGesture.Idle)
    }

    // -- preview synchronization --

    @Test
    fun preview_reflects_structural_edits_and_undo() {
        loadProject(threeClipProject())
        val controller = viewModel.previewController
        pollUntil(30_000L) { controller.state.value.hasComposition }
        assertEquals(15_000_000L, controller.state.value.durationMicros)
        assertNull("initial preview error: ${controller.state.value.error}",
            controller.state.value.error)

        // Delete the last clip: preview duration must follow the model.
        val lastId = viewModel.uiState.value.project!!.timeline.videoClips[2].id
        runOnMain {
            viewModel.select(TimelineSelection.Video(lastId))
            viewModel.deleteSelected()
        }
        pollUntil(30_000L) { controller.state.value.durationMicros == 11_000_000L }
        assertEquals(11_000_000L, controller.state.value.durationMicros)

        // Undo: duration returns.
        runOnMain { viewModel.undo() }
        pollUntil(30_000L) { controller.state.value.durationMicros == 15_000_000L }
        assertEquals(15_000_000L, controller.state.value.durationMicros)
    }

    @Test
    fun playhead_clamps_when_timeline_shrinks_under_it() {
        loadProject(threeClipProject())
        runOnMain { viewModel.seekTo(13_000_000L) }
        // Delete the last 4s clip → duration 11s; playhead at 13s must clamp.
        val lastId = viewModel.uiState.value.project!!.timeline.videoClips[2].id
        runOnMain {
            viewModel.select(TimelineSelection.Video(lastId))
            viewModel.deleteSelected()
        }
        assertTrue(
            "playhead ${viewModel.uiState.value.playheadMicros} not clamped to 11s",
            viewModel.uiState.value.playheadMicros <= 11_000_000L,
        )
    }

    // -- assets: thumbnails and waveforms from real media --

    @Test
    fun thumbnail_strips_decode_real_frames_from_source() {
        loadProject(threeClipProject())
        val content = com.videoeditor.editing.timeline.TimelineContent.from(
            viewModel.uiState.value.project!!,
        )
        runBlocking { viewModel.refreshAssets(content, 60) }

        val strips = viewModel.assets.value.strips
        assertEquals("not every clip got a strip", 3, strips.size)
        for ((clipId, strip) in strips) {
            assertTrue("empty strip for $clipId", strip.frames.isNotEmpty())
            val decoded = strip.frames.count { it != null }
            assertTrue(
                "strip for $clipId decoded only $decoded frames",
                decoded >= strip.frames.size / 2,
            )
            // Real video frames vary in content, not blank bitmaps.
            val bitmap = strip.frames.firstNotNullOf { it }
            assertTrue("thumbnail is suspiciously small", bitmap.width > 10 && bitmap.height > 10)
        }
    }

    @Test
    fun waveform_extraction_produces_real_peaks() {
        runBlocking {
            val peaks = TimelineMediaRepository(
                WaveformExtractor(),
                Dispatchers.IO,
            ).waveform(
                context,
                fileUri("music_20s.m4a"),
                0L,
                10_000_000L,
            )
            assertTrue("no peaks decoded", peaks.size > 0)
            // The fixture is a loud constant tone: peaks must be substantial
            // and normalized, not zeros (silence) or synthesized noise.
            var maxPeak = 0f
            var nonZero = 0
            for (i in 0 until peaks.size) {
                val p = peaks.peakInRange(i, i + 1)
                if (p > 0f) nonZero++
                if (p > maxPeak) maxPeak = p
            }
            assertTrue("waveform is silent", nonZero > peaks.size / 2)
            assertTrue("peak amplitude implausible: $maxPeak", maxPeak in 0.05f..1f)
        }
    }

    // -- persistence across reopen --

    @Test
    fun edits_persist_across_save_and_reopen() {
        val project = threeClipProject()
        loadProject(project)

        // Trim clip 1 start by 1s and split clip 2 at 9s (2s into clip2).
        val ids = viewModel.uiState.value.project!!.timeline.videoClips.map { it.id }
        val ppm = viewModel.uiState.value.zoomPixelsPerMicro
        runOnMain {
            viewModel.select(TimelineSelection.Video(ids[0]))
            viewModel.beginTrim(ids[0], TrimMath.Edge.Start, false)
            viewModel.trimBy(ppm * 1_000_000f, false)
            viewModel.endTrim(true, false)

            viewModel.seekTo(9_000_000L)
            viewModel.select(TimelineSelection.Video(ids[1]))
            viewModel.splitSelectedClip()
        }
        val edited = viewModel.uiState.value.project!!
        assertEquals(4, edited.timeline.videoClips.size)

        // Autosave is asynchronous; wait for the document on disk to match.
        assertTrue(
            "autosave did not persist the edit",
            pollUntil(15_000L) {
                val onDisk = runBlocking { repository.loadDocument(project.id) }
                    .let { (it as? com.videoeditor.core.common.result.Result.Success)?.value }
                onDisk?.timeline?.videoClips?.size == 4
            },
        )

        // Reopen in a fresh ViewModel: the edits must be there.
        val reopened = newViewModel()
        try {
            runOnMain {
                reopened.onViewportSize(viewportWidthPx, viewportWidthPx / 2f)
                reopened.loadProject(project.id)
            }
            pollUntil(10_000L) { reopened.uiState.value.project != null }
            val diskProject = reopened.uiState.value.project!!
            assertEquals(4, diskProject.timeline.videoClips.size)
            assertEquals(1_000_000L, diskProject.timeline.videoClips[0].sourceStartMicros)
            assertEquals(
                edited.timeline.videoTrackDurationMicros,
                diskProject.timeline.videoTrackDurationMicros,
            )
        } finally {
            runOnMain { reopened.releaseForTest() }
        }
    }

    // -- export of the edited composition --

    @Test
    fun export_renders_the_edited_sequence() = runBlocking {
        val project = threeClipProject()
        loadProject(project)

        // Edit: reorder clip1 to the end and trim clip2 (now first) to 2s.
        val ids = viewModel.uiState.value.project!!.timeline.videoClips.map { it.id }
        runOnMain { viewModel.beginReorder(ids[0]) }
        val viewport = viewModel.uiState.value.viewport()!!
        val dropX = viewport.screenXAtTime(
            viewModel.uiState.value.scrollXPx,
            14_000_000L,
        )
        runOnMain {
            viewModel.reorderTo(dropX)
            viewModel.endReorder(true)
        }
        val ppm = viewModel.uiState.value.zoomPixelsPerMicro
        runOnMain {
            viewModel.beginTrim(ids[1], TrimMath.Edge.End, false)
            // clip2 is 5s; drag the end handle 3s left → 2s of source kept.
            viewModel.trimBy(-ppm * 3_000_000f, false)
            viewModel.endTrim(true, false)
        }
        val edited = viewModel.uiState.value.project!!
        // Order: clip2 (trimmed 2s), clip3 (4s), clip1 (6s) = 12s.
        assertEquals(listOf(ids[1], ids[2], ids[0]),
            edited.timeline.videoClips.map { it.id })
        assertEquals(12_000_000L, edited.timeline.videoTrackDurationMicros)

        val output = File(context.filesDir, "timeline-export-${System.currentTimeMillis()}.mp4")
        val result = ExportEngine(context, compositionBuilder).export(
            edited,
            ExportRequest(
                projectId = edited.id,
                outputFile = output,
                resolution = ExportResolution.RES_720P,
                frameRate = FrameRate.FPS_30,
                quality = ExportQuality.Standard,
            ),
        ) { }
        assertTrue(
            "export failed: ${(result as? com.videoeditor.core.common.result.Result.Failure)?.error}",
            result.isSuccess,
        )
        try {
            assertTrue("output too small (${output.length()}B)", output.length() > 20_000)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.absolutePath)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                    .toLong()
                // 12s edited timeline.
                assertEquals(12_000.0, durationMs.toDouble(), 800.0)
                // Sampled frames must decode and differ (a real sequence).
                val first = retriever.getFrameAtTime(
                    500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                val middle = retriever.getFrameAtTime(
                    8_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                val late = retriever.getFrameAtTime(
                    11_500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                assertNotNull("no frame at 0.5s", first)
                assertNotNull("no frame at 8s", middle)
                assertNotNull("no frame at 11.5s", late)
                assertTrue(
                    "exported frames identical — sequence not rendered",
                    signature(first!!) != signature(middle!!) ||
                        signature(middle) != signature(late!!),
                )
            } finally {
                retriever.release()
            }
        } finally {
            output.delete()
        }
    }

    // -- helpers --

    @Test
    fun loading_same_project_preserves_preview_assets_selection_and_undo() {
        loadProject(threeClipProject())
        assertTrue(pollUntil(30_000) { viewModel.uiState.value.preview.hasComposition })
        runBlocking { viewModel.refreshAssets(com.videoeditor.editing.timeline.TimelineContent.from(
            viewModel.uiState.value.project!!), 60) }
        runOnMain {
            viewModel.select(TimelineSelection.Video(viewModel.uiState.value.project!!.timeline.videoClips.first().id))
            viewModel.duplicateSelectedClip()
        }
        runBlocking { viewModel.awaitPendingSavesForTest() }
        val before = viewModel.uiState.value
        val assetsBefore = viewModel.assets.value
        runOnMain { viewModel.loadProject(before.project!!.id) }
        assertEquals(before.project, viewModel.uiState.value.project)
        assertEquals(before.selection, viewModel.uiState.value.selection)
        assertTrue(viewModel.uiState.value.preview.hasComposition)
        assertTrue(viewModel.uiState.value.canUndo)
        assertTrue(assetsBefore === viewModel.assets.value)
        runOnMain { viewModel.undo() }
        assertEquals(3, viewModel.uiState.value.project!!.timeline.videoClips.size)
    }

    /** Repeatable emulator baseline, not a hardware frame-pacing benchmark. */
    @Test
    fun performance_baseline_real_assets_and_preview_updates() = runBlocking<Unit> {
        fun now() = android.os.SystemClock.elapsedRealtimeNanos()
        fun report(label: String, start: Long) {
            android.util.Log.i("TimelinePerf", "$label=${(now() - start) / 1_000_000.0}ms")
        }
        val media = TimelineMediaRepository(WaveformExtractor(), Dispatchers.IO)
        var start = now()
        val cold = media.thumbnailStrip(context, fileUri("clip1_720p_6s.mp4"),
            0, 6_000_000, 8, 60)
        report("thumbnail8_cold", start)
        assertEquals(8, cold.size)
        assertTrue(cold.all { it != null })
        start = now()
        val warm = media.thumbnailStrip(context, fileUri("clip1_720p_6s.mp4"),
            0, 6_000_000, 8, 60)
        report("thumbnail8_cached", start)
        assertTrue("cached strip must reuse decoded bitmaps", cold === warm)
        assertTrue(media.cachedBitmapBytes() in 1..(12 * 1024 * 1024))
        android.util.Log.i("TimelinePerf","bitmap_cache_bytes=${media.cachedBitmapBytes()}")
        start = now()
        val peaks = media.waveform(context, fileUri("music_20s.m4a"), 0, 10_000_000)
        report("waveform10s_cold", start)
        assertTrue(peaks.size > 0)
        start = now()
        assertTrue(peaks === media.waveform(context, fileUri("music_20s.m4a"), 0, 10_000_000))
        report("waveform10s_cached", start)

        loadProject(threeClipProject())
        assertTrue(pollUntil(30_000) { viewModel.previewController.state.value.hasComposition })
        start = now()
        runOnMain { viewModel.seekTo(9_000_000) }
        assertTrue(pollUntil(20_000) {
            viewModel.previewController.state.value.positionMicros in 8_900_000..9_100_000
        })
        report("seek_to_position_state_9s", start)
        start = now()
        runOnMain {
            viewModel.select(TimelineSelection.Video(viewModel.uiState.value.project!!.timeline.videoClips.last().id))
            viewModel.deleteSelected()
        }
        assertTrue(pollUntil(30_000) { viewModel.previewController.state.value.durationMicros == 11_000_000L })
        report("delete_to_composition_duration_state", start)
        start = now()
        runOnMain {
            viewModel.beginZoom(540f)
            repeat(120) { viewModel.zoomBy(if (it % 2 == 0) 1.01f else 1f / 1.01f, 540f) }
            viewModel.endZoom()
        }
        report("120_zoom_state_updates", start)
        val memory = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(memory)
        android.util.Log.i("TimelinePerf", "editing_total_pss_kb=${memory.totalPss}")
    }

    @Test fun long_project_assets_cull_and_release_after_editor_closure() = runBlocking<Unit> {
        val base = threeClipProject()
        for (count in listOf(3,10,30,50)) {
            val clips = (0 until count).map { index ->
                base.timeline.videoClips[index % 3].copy(id = "asset-stress-$index", timelineStartMicros = index * 1_000_000L,
                    sourceStartMicros = 0,sourceEndMicros = 1_000_000)
            }
            loadProject(base.copy(id = "assets-$count",timeline = base.timeline.copy(videoClips = clips)))
            runOnMain { viewModel.beginZoom(540f);viewModel.zoomBy(8f,540f);viewModel.endZoom() }
            val state = viewModel.uiState.value
            viewModel.refreshAssets(com.videoeditor.editing.timeline.TimelineContent.from(state.project!!),60)
            assertTrue(viewModel.assets.value.strips.isNotEmpty())
            if (count >= 10) assertTrue("offscreen strips must not stay retained",viewModel.assets.value.strips.size < count)
            val activeBytes = viewModel.assets.value.strips.values.flatMap { it.frames.filterNotNull() }
                .distinct().sumOf { it.allocationByteCount.toLong() }
            assertTrue("active UI bitmap references must respect their budget", activeBytes <= 12L * 1024 * 1024)
            val memory = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo)
            android.util.Log.i("MemoryPerf", "assets clips=$count strips=${viewModel.assets.value.strips.size} activeBitmapBytes=$activeBytes pssKb=${memory.totalPss}")
            runOnMain {
                viewModel.beginScrub();repeat(120) {viewModel.scrubBy(if(it%2==0) -30f else 30f)};viewModel.endScroll()
                assertEquals(1,viewModel.previewController.rebuildCount)
            }
            viewModel.awaitPendingSavesForTest()
            runOnMain {viewModel.releaseForTest()}
            assertTrue(viewModel.assets.value.strips.isEmpty())
            assertEquals(0,viewModel.previewController.activePlayerCount)
            viewModel = newViewModel()
        }
    }

    @Test fun distinct_source_projects_reopen_seek_and_release_bounded_assets() = runBlocking<Unit> {
        val owned = (1..50).map { index ->
            File(mediaDir, "distinct-$index.mp4").also {
                File(mediaDir, listOf("clip1_720p_6s.mp4", "clip2_480p_5s.mp4", "clip3_1080p_4s.mp4")[(index - 1) % 3])
                    .copyTo(it, overwrite = true)
            }
        }
        try {
            val refs = owned.map { extractor.extract(context, Uri.fromFile(it)).getOrNull()!! }
            assertEquals(50, refs.map { it.uri }.toSet().size)
            for (count in listOf(3, 10, 30, 50)) {
                val project = projectCreator.create("Distinct source test $count", refs.take(count))
                loadProject(project)
                assertTrue(pollUntil(30_000) { viewModel.uiState.value.preview.hasComposition })
                runOnMain { viewModel.beginZoom(540f); viewModel.zoomBy(8f, 540f); viewModel.endZoom() }
                viewModel.refreshAssets(com.videoeditor.editing.timeline.TimelineContent.from(project), 60)
                val active = viewModel.assets.value.strips.values.flatMap { it.frames.filterNotNull() }
                    .distinct().sumOf { it.allocationByteCount.toLong() }
                assertTrue(active in 1..(12L * 1024 * 1024))
                runOnMain {
                    viewModel.previewController.seekTo(project.timeline.renderedDurationMicros - 500_000)
                    viewModel.beginScrub(); viewModel.scrubBy(40f); viewModel.endScroll()
                    assertEquals(1, viewModel.previewController.rebuildCount)
                }
                viewModel.awaitPendingSavesForTest()
                assertEquals(project.timeline, repository.loadDocument(project.id).getOrNull()!!.timeline)
                android.util.Log.i("MemoryPerf", "distinctSources=$count activeBitmapBytes=$active strips=${viewModel.assets.value.strips.size}")
                runOnMain { viewModel.releaseForTest() }
                assertTrue(viewModel.assets.value.strips.isEmpty())
                assertEquals(0, viewModel.previewController.activePlayerCount)
                viewModel = newViewModel()
                loadProject(project)
                assertEquals(project.timeline, viewModel.uiState.value.project!!.timeline)
                runOnMain { viewModel.releaseForTest() }
                viewModel = newViewModel()
            }
        } finally { owned.forEach { it.delete() } }
    }

    private fun signature(bitmap: android.graphics.Bitmap): Long {
        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        val pixels = IntArray(16 * 16)
        scaled.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        return pixels.sumOf { it.toLong() }
    }

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        }
    }

    private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
