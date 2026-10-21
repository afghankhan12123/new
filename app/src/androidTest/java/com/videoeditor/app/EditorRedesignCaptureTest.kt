package com.videoeditor.app

import android.net.Uri
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
import com.videoeditor.core.media.MediaMetadataExtractor
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import com.videoeditor.editing.timeline.TimelineLayout
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Screenshot smoke only: exercises production navigation, preserves all existing user projects. */
class EditorRedesignCaptureTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).let {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
    }
    private fun action(label: String) {
        val selector = hasText(if (label == "Transition") "Transitions" else label) and hasClickAction() and !hasSetTextAction()
        if (ui.onAllNodes(selector).fetchSemanticsNodes().isEmpty() && label in listOf("Split", "Trim", "Crop", "Volume", "Duplicate", "Delete")) {
            ui.onNodeWithTag("editor-tool-Edit").performScrollTo().performClick()
            ui.waitForIdle()
        }
        val nodes = ui.onAllNodes(selector)
        for (i in nodes.fetchSemanticsNodes().indices.reversed()) {
            val node = nodes[i]
            runCatching { node.performScrollTo() }
            if (runCatching { node.assertIsDisplayed() }.isSuccess) { node.performClick(); return }
        }
        error("No visible action $label")
    }
    private fun capture(label: String) {
        ui.waitForIdle(); Thread.sleep(1800)
        ui.waitUntil(20000) { ui.onAllNodesWithText("Loading preview…").fetchSemanticsNodes().isEmpty() }
        if (label.substringBefore("_").toIntOrNull() in 2..16 && !label.startsWith("14_")) {
            val cancel = ui.onAllNodes(hasText("Cancel") and hasClickAction())
            if (cancel.fetchSemanticsNodes().isNotEmpty()) cancel.onLast().assertIsDisplayed()
        }
        val geometry = org.json.JSONObject()
        for (tag in listOf("editor-header", "editor-preview", "editor-transport", "timeline", "editor-panel", "editor-footer", "editor-tools")) {
            val nodes = ui.onAllNodesWithTag(tag).fetchSemanticsNodes()
            val node = nodes.firstOrNull() ?: if (tag == "editor-panel")
                ui.onAllNodesWithTag("transition-sheet").fetchSemanticsNodes().firstOrNull() else null
            node?.let { node ->
                val r = node.boundsInRoot
                geometry.put(tag, org.json.JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
            }
        }
        geometry.put("coordinate_system", "Compose boundsInRoot, pixels")
        geometry.put("density", context.resources.displayMetrics.density)
        File(context.getExternalFilesDir(null), "editor-$label.json").writeText(geometry.toString(2))
        shell("screencap -p /sdcard/Download/editor-$label.png")
    }
    @Test fun captureApprovedEditorScreens() {
        val file = File(context.filesDir, "design-review-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("waterfall.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(file)).getOrNull()!!.copy(displayName = "Forest waterfall")
        val musicFile = File(context.filesDir, "design-review-${System.nanoTime()}.m4a")
        instrumentation.context.assets.open("music_20s.m4a").use { input -> musicFile.outputStream().use(input::copyTo) }
        val music = MediaMetadataExtractor().extract(context, Uri.fromFile(musicFile)).getOrNull()!!.copy(displayName = "Music")
        val base = ProjectCreator().create("Visual review III ${System.nanoTime()}", listOf(ref, ref))
        // Leave real source handles around the join for a supported dissolve.
        val first = base.timeline.videoClips[0].let { it.copy(sourceEndMicros = it.sourceEndMicros - 1_000_000) }
        val second = base.timeline.videoClips[1].copy(sourceStartMicros = 1_000_000, timelineStartMicros = first.timelineEndMicros)
        val project = base.copy(mediaRefs = base.mediaRefs + music,
            canvas = base.canvas.copy(aspectRatio = AspectRatioPreset.Landscape16x9, width = 1920, height = 1080, scaleMode = ScaleMode.Fill),
            timeline = base.timeline.copy(videoClips = listOf(first, second),
                audioClips = listOf(AudioClip("review-audio", music.id, sourceEndMicros = 3_000_000, volume = .8f, fadeInMicros = 500_000, fadeOutMicros = 1_200_000)),
                textOverlays = listOf(TextOverlay("review-text", "Adventure awaits", durationMicros = 3_000_000)),
                overlayClips = listOf(OverlayClip("review-overlay", ref.id, timelineDurationMicros = 2_000_000,
                    sourceEndMicros = 2_000_000, muted = true, transform = VisualTransform(scale = .25f, offsetX = .6f, offsetY = .6f)))))
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        val repository = ProjectRepository(db.projectDao(), ProjectDocumentStorage(context))
        runBlocking { check(repository.save(project).isSuccess) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.waitUntil(15000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
            action(project.name)
            ui.waitUntil(15000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("timeline").performTouchInput { swipe(Offset(width * .7f, height * .08f), Offset(width * .4f, height * .08f), 500) }
            fun selectVideo() {
                ui.onNodeWithTag("timeline").performTouchInput {
                    click(Offset(width * .60f, height * (TimelineLayout.rowTop(TimelineLayout.ROW_VIDEO) + TimelineLayout.VIDEO_HEIGHT / 2) / TimelineLayout.TOTAL_HEIGHT))
                }
            }
            selectVideo(); capture("01_main_editor")
            ui.onNodeWithTag("editor-tool-Edit").performScrollTo().performClick()
            ui.onNodeWithContentDescription("Timeline options").performClick()
            ui.onNodeWithText("Fit timeline").assertIsDisplayed()
            ui.onNodeWithText("Zoom in timeline").assertIsDisplayed()
            shell("input keyevent 4")
            ui.onNodeWithTag("editor-tool-Edit").performScrollTo().performClick()
            selectVideo(); action("Trim")
            ui.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("00:00.100001")
            ui.onNodeWithTag("tool-apply").assertIsEnabled()
            ui.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("00:99.0")
            ui.onNodeWithTag("tool-apply").assertIsNotEnabled()
            shell("input keyevent 4"); action("Reset"); capture("02_trim"); action("Cancel")
            selectVideo(); action("Split"); capture("03_split"); action("Cancel")
            ui.onNodeWithContentDescription("Aspect Ratio").performClick(); action("9:16"); action("Fit"); capture("04_crop_ratio"); action("Cancel")
            selectVideo(); action("Crop"); action("Scale"); action("Fit"); action("Rotate")
            ui.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("12")
            shell("input keyevent 4"); Thread.sleep(600)
            action("Apply")
            ui.waitForIdle(); Thread.sleep(1200)
            selectVideo(); action("Crop"); capture("05_transform"); action("Cancel")
            ui.onNodeWithContentDescription("Undo").performClick()
            selectVideo(); action("Speed"); capture("06_speed"); action("Cancel")
            // Choose actual audio so fades are supported.
            ui.onNodeWithTag("timeline").performTouchInput {
                click(Offset(width * .60f, height * (TimelineLayout.rowTop(TimelineLayout.ROW_AUDIO) + TimelineLayout.AUDIO_HEIGHT / 2) / TimelineLayout.TOTAL_HEIGHT))
            }
            action("Volume"); capture("07_volume_fade"); ui.onNodeWithText("Fade Out").assertIsDisplayed(); action("Cancel")
            selectVideo(); action("Transition"); ui.onNodeWithTag("transition-cross-dissolve").performClick(); ui.onNodeWithTag("transition-apply").assertIsEnabled(); capture("08_transitions"); ui.onNodeWithTag("transition-preview").assertIsDisplayed(); action("Cancel")
            action("Audio"); capture("09_audio")
            check(ui.onNodeWithTag("audio-import").fetchSemanticsNode().boundsInRoot.bottom <=
                ui.onNodeWithTag("editor-footer").fetchSemanticsNode().boundsInRoot.top)
            ui.onNodeWithText("Import music or sound").performScrollTo().assertIsDisplayed()
            capture("detail-audio-import"); action("Done")
            ui.onNodeWithTag("timeline").performTouchInput {
                click(Offset(width * .60f, height * (TimelineLayout.rowTop(TimelineLayout.ROW_TEXT) + TimelineLayout.TEXT_HEIGHT / 2) / TimelineLayout.TOTAL_HEIGHT))
            }
            ui.onNodeWithTag("editor-tool-Edit").performScrollTo().performClick()
            ui.onNodeWithTag("clip-action-Edit").performClick()
            capture("10_text")
            check(ui.onNodeWithTag("text-size-slider").fetchSemanticsNode().boundsInRoot.bottom <=
                ui.onNodeWithTag("editor-footer").fetchSemanticsNode().boundsInRoot.top)
            ui.onNodeWithText("Size").performScrollTo().assertIsDisplayed()
            capture("detail-text-size"); action("Cancel")
            selectVideo(); action("Filters"); action("Vivid"); ui.onNodeWithTag("filter-intensity").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.7f) }; capture("11_filters")
            check(ui.onNodeWithTag("filter-intensity").fetchSemanticsNode().boundsInRoot.bottom <=
                ui.onNodeWithTag("editor-footer").fetchSemanticsNode().boundsInRoot.top)
            action("Cancel")
            selectVideo(); action("Adjust"); capture("12_adjust")
            ui.onNodeWithTag("grade-shadows").assertIsDisplayed()
            check(ui.onNodeWithTag("grade-shadows").fetchSemanticsNode().boundsInRoot.bottom <=
                ui.onNodeWithTag("editor-footer").fetchSemanticsNode().boundsInRoot.top)
            ui.onNodeWithTag("panel-expand").performClick(); capture("detail-adjust-expanded")
            ui.onNodeWithText("Shadows").assertIsDisplayed()
            ui.onNodeWithTag("panel-expand").performClick(); capture("detail-adjust-collapsed"); action("Cancel")
            action("Canvas"); action("9:16"); action("Fit"); capture("13_canvas"); action("Cancel")
            action("Overlay"); action("Layers"); ui.onNodeWithTag("select-pip-review-overlay").performClick(); capture("14_overlay")
            ui.onNodeWithTag("panel-expand").performClick(); capture("detail-pip-expanded")
            ui.onNodeWithTag("pip-Rotation").performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("panel-expand").performClick(); action("Done")
            action("Stickers"); capture("15_stickers")
            check(ui.onNodeWithContentDescription("Music note sticker design").fetchSemanticsNode().boundsInRoot.bottom <=
                ui.onNodeWithTag("editor-footer").fetchSemanticsNode().boundsInRoot.top)
            ui.onNodeWithTag("tool-apply").assertIsNotEnabled()
            for ((category, design) in listOf("Shapes" to "Hexagon", "Arrows" to "Left arrow", "Social" to "Speech bubble", "Decorative" to "Flower")) {
                action(category)
                ui.onNodeWithContentDescription("$design sticker design").assertExists()
                capture("detail-stickers-${category.lowercase()}")
            }
            action("Cancel")
            action("Effects"); capture("16_effects"); action("Cancel")
            for (width in listOf(360, 412, 430)) {
                shell("wm size ${width * 3}x${width * 6}"); shell("wm density 480"); Thread.sleep(1200)
                capture("responsive-${width}")
                ui.onNodeWithContentDescription("Play/Pause").assertIsDisplayed()
                ui.onNodeWithTag("editor-tools").assertIsDisplayed()
                selectVideo(); action("Crop"); capture("responsive-${width}-transform")
                ui.onNodeWithText("Apply").assertIsDisplayed(); ui.onNodeWithText("Rotation").assertIsDisplayed(); action("Cancel")
                selectVideo(); action("Adjust"); capture("responsive-${width}-adjust")
                ui.onNodeWithText("Brightness").assertIsDisplayed()
                ui.onNodeWithTag("panel-expand").performClick(); capture("responsive-${width}-adjust-expanded")
                ui.onNodeWithText("Shadows").assertIsDisplayed()
                ui.onNodeWithContentDescription("Play/Pause").assertIsDisplayed()
                ui.onNodeWithTag("panel-expand").performClick()
                ui.onNodeWithText("Shadows").performScrollTo().assertIsDisplayed()
                ui.onNodeWithText("Apply").assertIsDisplayed(); action("Cancel")
                action("Stickers"); capture("responsive-${width}-stickers")
                ui.onNodeWithTag("tool-apply").assertIsNotEnabled()
                ui.onNodeWithContentDescription("Music note sticker design").performScrollTo().assertIsDisplayed()
                ui.onNodeWithText("Cancel").assertIsDisplayed(); capture("responsive-${width}-stickers-scrolled"); action("Cancel")
                action("Text"); capture("responsive-${width}-text")
                ui.onNodeWithTag("text-size-slider").performScrollTo().assertIsDisplayed()
                ui.onNodeWithText("Apply").assertIsDisplayed(); capture("responsive-${width}-text-scrolled"); action("Cancel")
                if (width == 360) {
                    selectVideo(); action("Transition")
                    ui.onNodeWithTag("transition-cross-dissolve").performClick()
                    ui.onNodeWithTag("transition-preview").assertIsDisplayed()
                    capture("responsive-360-transitions"); action("Cancel")
                    action("Text")
                    ui.onAllNodes(hasSetTextAction()).onFirst().performClick()
                    shell("input text Review"); Thread.sleep(600)
                    capture("detail-360-text-keyboard")
                    ui.onNodeWithText("Apply").assertIsDisplayed()
                    shell("input keyevent 4"); Thread.sleep(600); action("Cancel")
                }
            }
            shell("wm size 2400x1080"); shell("wm density 420"); Thread.sleep(1200)
            selectVideo()
            ui.onNodeWithTag("editor-tool-Edit").performScrollTo().performClick()
            ui.onNodeWithTag("clip-action-Trim").assertIsDisplayed()
            capture("detail-landscape-actions")
            action("Crop"); capture("detail-landscape-transform"); action("Cancel")
            action("Filters"); capture("detail-landscape-filters"); action("Cancel")
            action("Stickers"); capture("detail-landscape-stickers"); action("Cancel")
            action("Effects"); capture("detail-landscape-effects"); action("Cancel")

        } finally {
            shell("wm size reset"); shell("wm density reset"); scenario.close(); db.close()
            // Leave the uniquely identified review fixture available for manual review.
        }
    }
}
