package com.videoeditor.app

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.database.VideoEditorDatabase
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.database.storage.ProjectDocumentStorage
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Drives the real installed Android camera; verifies the returned FileProvider bytes. */
class CameraCaptureUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    @Test fun native_camera_recording_returns_a_decodable_editable_project() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, VideoEditorDatabase.NAME).build()
        val repository = ProjectRepository(db.projectDao(), ProjectDocumentStorage(context))
        val existing = runBlocking { db.projectDao().observeAll().first().map { it.id }.toSet() }
        val captures = File(context.filesDir, "captures")
        val originalFiles = captures.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        fun find(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == description) return node
            if (description == "Recorded two seconds" && node.viewIdResourceName == "com.android.camera2:id/recording_time" &&
                Regex("00:0[2-9]").matches(node.text?.toString().orEmpty())) return node
            for (index in 0 until node.childCount) find(node.getChild(index), description)?.let { return it }
            return null
        }
        fun nativeAction(description: String, click: Boolean = true) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            var node: AccessibilityNodeInfo? = null
            while (node == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                node = find(automation.rootInActiveWindow, description)
                if (node == null) node = automation.windows.firstNotNullOfOrNull { find(it.root, description) }
                if (node == null) Thread.sleep(50)
            }
            if (node == null) {
                fun report(root: AccessibilityNodeInfo?) {
                    if (root == null) return
                    android.util.Log.i("CameraProbe", "${root.packageName} text=${root.text} description=${root.contentDescription} id=${root.viewIdResourceName}")
                    for (index in 0 until root.childCount) report(root.getChild(index))
                }
                automation.windows.forEach { report(it.root) }
            }
            assertNotNull("Native camera action missing: $description", node)
            if (click) assertTrue("Native action did not click: $description", node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ui.onNodeWithContentDescription("Camera").performClick()
            ui.waitForIdle()
            nativeAction("Shutter")
            nativeAction("Recorded two seconds", click = false)
            automation.executeShellCommand("screencap -p /sdcard/Download/release-native-camera-recording.png").let {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
            }
            nativeAction("Shutter"); nativeAction("Done")
            ui.waitUntil(15000) { ui.onAllNodesWithText("1 item selected").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Next").assertIsDisplayed().performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty() }
            val created = runBlocking { db.projectDao().observeAll().first().single { it.id !in existing } }
            val project = runBlocking { repository.loadDocument(created.id).getOrNull()!! }
            val ref = project.mediaRefs.single()
            assertEquals("content", Uri.parse(ref.uri).scheme)
            assertTrue("Camera result has no duration", (ref.durationMicros ?: 0) > 500_000)
            assertTrue(project.timeline.videoClips.single().sourceDurationMicros > 500_000)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(context, Uri.parse(ref.uri))
                val frame = reader.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull("Camera result does not decode", frame)
                assertTrue(frame!!.width > 0 && frame.height > 0); frame.recycle()
            } finally { reader.release() }
        } finally {
            scenario.close()
            runBlocking { db.projectDao().observeAll().first().filter { it.id !in existing }.forEach { repository.deletePermanently(it.id) } }
            captures.listFiles()?.filter { it.name !in originalFiles }?.forEach(File::delete)
            db.close()
        }
    }
}
