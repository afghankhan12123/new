package com.videoeditor.app

import androidx.compose.ui.test.performImeAction

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
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
import com.videoeditor.editing.ProjectCreator
import com.videoeditor.editing.timeline.TimelineLayout
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Capture real connected supporting states and exercise their actions. */
class VisualStatesUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun capture(label: String) {
        ui.waitForIdle(); Thread.sleep(700)
        shell("screencap -p /sdcard/Download/release-visual-$label.png")
    }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).let {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
    }
    private fun action(label: String) {
        editorTestAction(ui, label)
    }

    @Test fun themes_project_options_search_canvas_and_unsaved_decisions() {
        val preferences = dagger.hilt.android.EntryPointAccessors.fromApplication(context, AcceptancePreferences::class.java).preferences()
        val previous = runBlocking { preferences.preferences.first() }
        fun awaitTheme(mode: String, dark: Boolean) {
            ui.waitUntil(5000) {
                runBlocking { preferences.preferences.first().themeMode == mode } && runCatching {
                    // Settings fills its root with MaterialTheme.background. Observe
                    // actual propagated pixels rather than just the saved preference.
                    val color = ui.onRoot().captureToImage().toPixelMap()[4, 4]
                    val brightness = (color.red + color.green + color.blue) / 3f
                    if (dark) brightness < .3f else brightness > .8f
                }.getOrDefault(false)
            }
        }
        val file = File(context.filesDir, "visual-fixture-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("waterfall.mp4").use { source -> file.outputStream().use(source::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(file)).getOrNull()!!
        val project = ProjectCreator().create("Waterfall 世界 visual ${System.nanoTime()}", listOf(ref))
        val extras = listOf("Forest Waterfall", "Nature Walk", "Waterfall Memories").mapIndexed { index, title ->
            ProjectCreator().create(title, listOf(ref)).let { it.copy(updatedAtMillis = project.updatedAtMillis - (index + 1) * 1000) }
        }
        val storage = ProjectDocumentStorage(context)
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        val repository = ProjectRepository(db.projectDao(), storage)
        if (runBlocking { repository.observeAll().first().isEmpty() }) {
            val emptyScenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                ui.waitUntil(15000) { ui.onAllNodesWithText("No projects yet").fetchSemanticsNodes().isNotEmpty() }
                capture("empty-projects-home")
                action("See All"); capture("empty-projects-list")
            } finally { emptyScenario.close() }
        }
        runBlocking { preferences.setThemeMode("Dark"); preferences.setAutosaveEnabled(true); assertTrue(repository.save(project).isSuccess); extras.forEach { assertTrue(repository.save(it).isSuccess) } }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var renamed = project.name
        try {
            ui.waitUntil(15000) { ui.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
            capture("home-dark")
            ui.onAllNodesWithContentDescription("Settings").onFirst().performClick()
            action("Theme"); action("Light"); awaitTheme("Light", false); capture("settings-light")
            ui.onNodeWithContentDescription("Back").performClick(); capture("home-light")
            action(project.name)
            ui.waitUntil(15000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
            action("Export")
            ui.waitUntil(15000) { ui.onAllNodesWithText("Export Video").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { activity ->
                val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertFalse("Dark export must retain readable status icons with light Home", controller.isAppearanceLightStatusBars)
                assertFalse("Dark export must retain readable navigation icons with light Home", controller.isAppearanceLightNavigationBars)
            }
            capture("export-light-home")
            ui.onNodeWithContentDescription("Back").performClick()
            ui.onNodeWithContentDescription("Close").performClick()
            ui.onAllNodesWithContentDescription("Settings").onFirst().performClick()
            action("Theme"); action("Dark"); awaitTheme("Dark", true)
            action("Theme"); action("System")
            val systemDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            awaitTheme("System", systemDark); capture("settings-system")
            action("Theme"); action("Dark"); awaitTheme("Dark", true)
            ui.onAllNodes(isToggleable()).onFirst().performClick()
            ui.waitUntil(5000) { !runBlocking { preferences.preferences.first().autosaveEnabled } }
            capture("settings-dark")
            action("Help & Feedback"); capture("help")
            action("Share Diagnostic Details"); ui.waitForIdle(); Thread.sleep(700)
            assertTrue("Diagnostic action did not open the native chooser",
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName)
            capture("diagnostic-chooser"); shell("input keyevent KEYCODE_BACK")
            ui.waitUntil(10000) { ui.onAllNodesWithText("Share Diagnostic Details").fetchSemanticsNodes().isNotEmpty() }
            action("Done"); action("About"); capture("about"); action("Done")
            ui.onNodeWithContentDescription("Back").performClick()
            action("See All")
            capture("all-projects")
            ui.onNode(hasSetTextAction()).performTextReplacement(project.name)
            ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("Options").fetchSemanticsNodes().size == 1 }
            ui.onNode(hasSetTextAction()).performImeAction()
            capture("search-results")
            ui.onNodeWithContentDescription("Options").performClick(); capture("project-options")
            action("Project Details"); capture("project-details"); action("Done")
            ui.onNodeWithContentDescription("Options").performClick(); action("Favorite")
            action("Favorites"); ui.onNode(hasText(project.name) and hasClickAction() and !hasSetTextAction()).assertIsDisplayed()
            ui.onNodeWithContentDescription("Options").performClick(); action("Rename")
            renamed = "Waterfall renamed 世界 ${System.nanoTime()}"
            ui.onAllNodes(hasSetTextAction()).onLast().performTextReplacement(renamed); action("Done")
            ui.onNode(hasSetTextAction()).performTextReplacement(renamed)
            ui.waitUntil(5000) {
                ui.onAllNodes(hasText(renamed) and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().size == 1 &&
                    ui.onAllNodesWithContentDescription("Options").fetchSemanticsNodes().size == 1
            }
            ui.onNodeWithContentDescription("Options").performClick(); action("Delete"); capture("delete-confirmation"); action("Cancel")
            ui.onNode(hasSetTextAction()).performTextReplacement("no results ${System.nanoTime()}")
            ui.waitUntil(5000) { ui.onAllNodesWithText("No projects found").fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(hasSetTextAction()).performImeAction()
            capture("empty-search")
            ui.onNodeWithContentDescription("Clear search").performClick()
            ui.onNode(hasSetTextAction()).performTextReplacement(renamed)
            ui.waitUntil(5000) {
                ui.onAllNodes(hasText(renamed) and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().size == 1 &&
                    ui.onAllNodesWithContentDescription("Options").fetchSemanticsNodes().size == 1
            }
            action(renamed)
            ui.waitUntil(15000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("Aspect Ratio").performClick(); capture("aspect-ratio")
            action("1:1"); action("Apply")
            ui.onNodeWithContentDescription("Close").performClick()
            ui.onNodeWithText("Unsaved Changes").assertIsDisplayed(); capture("unsaved-changes")
            assertEquals(project.canvas, runBlocking { storage.load(project.id).getOrNull() }!!.canvas)
            action("Cancel"); ui.onNodeWithTag("timeline").assertIsDisplayed()
            ui.onNodeWithContentDescription("Close").performClick(); action("Save and Continue")
            ui.waitUntil(5000) { runBlocking { storage.load(project.id).getOrNull() }!!.canvas.width == runBlocking { storage.load(project.id).getOrNull() }!!.canvas.height }
            action(renamed)
            ui.waitUntil(15000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("timeline").performTouchInput {
                click(Offset(width * .6f, height * (TimelineLayout.rowTop(TimelineLayout.ROW_VIDEO) + TimelineLayout.VIDEO_HEIGHT / 2) / TimelineLayout.TOTAL_HEIGHT))
            }
            action("Rotate")
            ui.onNodeWithContentDescription("Close").performClick(); action("Discard Changes")
            assertEquals(0, runBlocking { storage.load(project.id).getOrNull() }!!.timeline.videoClips.single().transform.rotationDegrees)
            ui.onNodeWithContentDescription("Back").performClick()
            // Native configuration changes retain reachable Home actions.
            shell("settings put system font_scale 1.5"); Thread.sleep(1000)
            capture("home-large-font")
            ui.onAllNodesWithText("New Project").onFirst().assertIsDisplayed()
            shell("settings put system font_scale 1.0")
            for ((label, size, density) in listOf(Triple("compact", "480x960", 240), Triple("standard", "1080x1920", 440), Triple("wide", "1440x2560", 420))) {
                shell("wm size $size"); shell("wm density $density"); Thread.sleep(1200)
                capture("home-$label")
                ui.onAllNodesWithText("New Project").onFirst().assertIsDisplayed()
                ui.onAllNodesWithContentDescription("Settings").onFirst().assertIsDisplayed()
            }
            shell("wm size reset"); shell("wm density reset")
        } finally {
            scenario.close(); shell("settings put system font_scale 1.0"); shell("wm size reset"); shell("wm density reset")
            runBlocking { repository.deletePermanently(project.id); extras.forEach { repository.deletePermanently(it.id) }; preferences.setThemeMode(previous.themeMode); preferences.setAutosaveEnabled(previous.autosaveEnabled) }
            db.close(); file.delete()
        }
    }
}
