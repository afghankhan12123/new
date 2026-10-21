package com.videoeditor.app

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
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
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Navigate the production editor, not a panel-only harness. Retains owned fixture/evidence. */
class Stage8AudioWorkspaceUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun contextual_audio_actions_mount_focused_panels_and_commit_to_real_project() {
        val folder = File(context.filesDir, "stage8-workspace-${System.nanoTime()}").apply { mkdirs() }
        fun media(name: String): MediaRef {
            val file = File(folder, name.substringAfterLast('/'))
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
            return MediaMetadataExtractor().extract(context, Uri.fromFile(file)).getOrNull()!!
        }
        val video = media("clip1_720p_6s.mp4"); val music = media("music_20s.m4a")
        val voice = media("stage8/voice_trigger_880_2s_4s.m4a")
        val pip = media("clip2_480p_5s.mp4")
        val photoFile = File(folder, "photo.png")
        android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            photoFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val photo = MediaMetadataExtractor().extract(context, Uri.fromFile(photoFile)).getOrNull()!!
        val base = ProjectCreator().create("Stage8 workspace ${System.nanoTime()}", listOf(video))
        val first = base.timeline.videoClips.single().copy(sourceStartMicros = 500_000, sourceEndMicros = 3_500_000, muted = true)
        val second = first.copy(id = "second", timelineStartMicros = 3_000_000)
        val project = base.copy(mediaRefs = base.mediaRefs + listOf(music, voice, pip, photo), timeline = base.timeline.copy(
            videoClips = listOf(first, second), transitions = listOf(Transition("dissolve", first.id, second.id, 500_000)),
            textOverlays = listOf(TextOverlay("title", "Stage 8 workflow", durationMicros = 6_000_000, positionY = .85f)),
            stickers = listOf(StickerClip("star", "star", timelineDurationMicros = 6_000_000, positionX = .8f, positionY = .2f)),
            overlayClips = listOf(OverlayClip("pip", pip.id, timelineStartMicros = 1_000_000, timelineDurationMicros = 4_000_000,
                sourceEndMicros = 4_000_000, muted = true, transform = VisualTransform(scale = .3f, offsetX = .5f, offsetY = .5f)),
                OverlayClip("photo", photo.id, timelineDurationMicros = 6_000_000, isPhoto = true,
                    transform = VisualTransform(scale = .2f, offsetX = -.6f, offsetY = -.5f))),
            audioClips = listOf(AudioClip("music", music.id, sourceEndMicros = 6_000_000),
                AudioClip("voice", voice.id, sourceEndMicros = 6_000_000, balance = AudioBalance(1f)))))
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        val repository = ProjectRepository(db.projectDao(), ProjectDocumentStorage(context))
        runBlocking { check(repository.save(project).isSuccess) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.waitUntil(20000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(project.name).performClick()
            ui.waitUntil(20000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
            val select = ui.onNodeWithTag("timeline").fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .first { it.label == "Select audio clip 1" }
            ui.runOnIdle { assertTrue(select.action()) }
            fun open(label: String) {
                ui.onNodeWithTag("editor-tool-Edit").performScrollTo().performClick()
                ui.onNodeWithTag("clip-action-$label").performScrollTo().performClick()
                ui.onNodeWithTag("editor-panel").assertIsDisplayed()
                ui.onNodeWithTag("editor-preview").assertIsDisplayed()
                ui.onNodeWithTag("timeline").assertIsDisplayed()
                val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
                File(folder, "panel-$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            open("Automation")
            ui.onNodeWithText("Add point at playhead").performClick()
            ui.onNodeWithTag("volume-automation-apply").performClick()
            open("EQ")
            ui.onNodeWithText("Warm").performClick()
            ui.onNodeWithTag("equalizer-apply").performClick()
            open("Balance")
            ui.onNodeWithText("Left", useUnmergedTree = true).performClick()
            ui.onNodeWithTag("balance-apply").performClick()
            open("Fade")
            ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
            ui.onNodeWithText("Smooth").performClick()
            ui.onNodeWithTag("fade-apply").performClick()
            open("Ducking")
            ui.onNodeWithContentDescription("Enable automatic ducking").performClick()
            ui.onAllNodesWithContentDescription("Duck on voice_trigger_880_2s_4s.m4a").onLast().performScrollTo().performClick()
            ui.onNodeWithTag("ducking-apply").performClick()
            ui.waitUntil(15000) {
                runBlocking { repository.loadDocument(project.id).getOrNull() }?.timeline?.audioClips?.first()?.ducking?.enabled == true
            }
            val authored = runBlocking { repository.loadDocument(project.id).getOrNull()!! }.timeline.audioClips.first()
            assertEquals(1, authored.volumeEnvelope.points.size); assertEquals(EqualizerPreset.Warm.settings(), authored.equalizer)
            assertEquals(-1f, authored.balance.pan, 0f); assertEquals(FadeCurve.Smooth, authored.fadeInCurve)
            assertEquals(500_000L, authored.fadeInMicros); assertEquals(listOf("voice"), authored.ducking.triggerClipIds)
            ui.onNodeWithContentDescription("Undo").performClick()
            ui.waitUntil(10000) { runBlocking { repository.loadDocument(project.id).getOrNull() }?.timeline?.audioClips?.first()?.ducking?.enabled == false }
            ui.onNodeWithContentDescription("Redo").performClick()
            ui.waitUntil(15000) { runBlocking { repository.loadDocument(project.id).getOrNull() }?.timeline?.audioClips?.first() == authored }
            scenario.recreate()
            ui.waitUntil(15000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(20000) { ui.onAllNodesWithText("Loading preview…").fetchSemanticsNodes().isEmpty() }
            assertEquals(authored, runBlocking { repository.loadDocument(project.id).getOrNull()!! }.timeline.audioClips.first())
            val screenshot = instrumentation.uiAutomation.takeScreenshot()!!
            File(folder, "workspace-final.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            File(folder, "qualified-project.json").writeText(kotlinx.serialization.json.Json.encodeToString(Project.serializer(),
                runBlocking { repository.loadDocument(project.id).getOrNull()!! }))
        } finally { scenario.close(); db.close() }
    }
}
