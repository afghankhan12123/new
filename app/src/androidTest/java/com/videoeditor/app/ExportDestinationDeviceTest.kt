package com.videoeditor.app

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.Rule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.database.VideoEditorDatabase
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.database.storage.ProjectDocumentStorage
import com.videoeditor.core.media.MediaMetadataExtractor
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.editing.ProjectCreator
import com.videoeditor.feature.export.ExportPhase
import com.videoeditor.feature.export.ExportViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real engine, MediaStore and FileProvider, with bytes read back from content URIs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExportDestinationDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    @Test fun gallery_files_share_cancellation_and_provider_failure() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, "destination-source.mp4")
        instrumentation.context.assets.open("clip1_720p_6s.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val original = ProjectCreator().create("Destination audit", listOf(ref))
        val project = original.copy(timeline = original.timeline.copy(videoClips = original.timeline.videoClips.map {
            it.copy(sourceEndMicros = 1_000_000)
        }))
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, "destination-audit.db").build()
        val repo = ProjectRepository(db.projectDao(), ProjectDocumentStorage(context))
        assertTrue(repo.save(project).isSuccess)
        var exhausted = true
        val budget = object : com.videoeditor.core.media.export.ExportStorageBudget() {
            override fun availableBytes(directory: File) = if (exhausted) 0L else super.availableBytes(directory)
        }
        val vm = ExportViewModel(context, repo, ExportEngine(context, CompositionBuilder()), Dispatchers.IO, storageBudget = budget)
        val interrupted = File(context.filesDir, "exports/export_${project.id}_interrupted.mp4").apply { parentFile!!.mkdirs(); writeText("incomplete") }
        File(interrupted.parentFile, "pending_${project.id}.txt").writeText(interrupted.name)
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(predicate: () -> Boolean) {
            ui.waitUntil(60_000) { predicate() }
            assertTrue("Timed out: ${vm.uiState.value}", predicate())
        }
        val created = mutableListOf<Uri>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val evidenceDirectory = "Download/VideoEditorEvidence/destination-${System.nanoTime()}/"
        fun capture(name: String) {
            ui.waitForIdle()
            // UiAutomation captures the compositor, which can lag Compose semantics by a frame.
            Thread.sleep(250)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
            try {
                val resolver = context.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "release-export-$name.png")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, evidenceDirectory)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    }) ?: error("Cannot create destination screenshot evidence")
                try {
                    resolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                    assertEquals(1, resolver.update(uri, android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null))
                } catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
            } finally { bitmap.recycle() }
        }
        try {
            scenario.onActivity { activity ->
                androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
                if (android.os.Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = false
                activity.setContent {
                    com.videoeditor.core.designsystem.theme.VideoEditorTheme(themeMode = com.videoeditor.core.designsystem.theme.ThemeMode.Dark) {
                        androidx.compose.material3.Scaffold(containerColor = com.videoeditor.core.designsystem.color.VEColor.DarkBackground) { insets ->
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(insets)) {
                                com.videoeditor.feature.export.ExportScreen(project.id, {}, vm)
                            }
                        }
                    }
                }
            }
            await { vm.uiState.value.project != null }
            assertEquals(ExportPhase.Failed, vm.uiState.value.phase)
            assertTrue(vm.uiState.value.errorMessage!!.contains("interrupted"))
            assertFalse(interrupted.exists())
            ui.onAllNodesWithText("Export Failed").onLast().assertIsDisplayed()
            capture("interrupted-failure")
            assertTrue(repo.loadDocument(project.id).isSuccess)
            ui.onNodeWithText("Change Settings").performClick()
            main { vm.setResolution(ExportResolution.RES_720P) }
            ui.onNodeWithText("Export Video").performClick()
            await { vm.uiState.value.phase == ExportPhase.Failed }
            assertTrue(vm.uiState.value.errorMessage!!.contains("Not enough storage"))
            assertNull(vm.uiState.value.outputFile)
            ui.onAllNodesWithText("Export Failed").onLast().assertIsDisplayed()
            capture("low-storage-failure")
            assertFalse(File(interrupted.parentFile, "pending_${project.id}.txt").exists())
            assertTrue(repo.loadDocument(project.id).isSuccess)
            exhausted = false
            ui.onNodeWithText("Try Again").performClick()
            await { vm.uiState.value.phase in listOf(ExportPhase.Completed, ExportPhase.Failed) }
            assertEquals(ExportPhase.Completed, vm.uiState.value.phase)
            capture("retried-complete")
            val file = vm.uiState.value.outputFile!!
            assertTrue(file.canonicalPath.startsWith(context.filesDir.canonicalPath))
            fun assertBytes(uri: Uri) {
                assertEquals("content", uri.scheme)
                context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(file.readBytes(), it.readBytes()) }
            }
            assertBytes(vm.shareUri()!!)
            main { vm.saveToFiles(null) }
            assertNull(vm.uiState.value.saveMessage)
            main { vm.saveToFiles(Uri.parse("content://unavailable.audit.provider/no-file")) }
            await { vm.uiState.value.saveMessage?.startsWith("Could not save to Files") == true }
            assertTrue(file.exists())
            // A writable content provider verifies copying independently of the SAF picker UI.
            repeat(2) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "duplicate-audit.mp4")
                    put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Audit")
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
                created += uri
                main { vm.saveToFiles(uri) }
                await { vm.uiState.value.saveMessage == "Saved to Files" }
                assertBytes(uri)
            }
            main { vm.saveToGallery() }
            await { vm.uiState.value.savedToGallery }
            val collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(collection, arrayOf("_id"), "_display_name = ?", arrayOf(file.name), null)!!.use {
                assertTrue(it.moveToFirst())
                val uri = android.content.ContentUris.withAppendedId(collection, it.getLong(0))
                created += uri
                assertBytes(uri)
            }
        } finally {
            scenario.close()
            created.forEach { context.contentResolver.delete(it, null, null) }
            main { vm.cancelExport() }
            vm.uiState.value.outputFile?.delete()
            repo.deletePermanently(project.id); source.delete()
            db.close(); context.deleteDatabase("destination-audit.db")
        }
    }

    @Test fun export_1080p_fidelity_and_player_intent_verification() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, "destination-source-1080p.mp4")
        instrumentation.context.assets.open("clip1_720p_6s.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val original = ProjectCreator().create("1080p Export Audit", listOf(ref), canvas = com.videoeditor.core.model.CanvasSettings(width = 1080, height = 1920, aspectRatio = com.videoeditor.core.model.AspectRatioPreset.Portrait9x16))
        val project = original.copy(
            exportPreferences = com.videoeditor.core.model.ExportPreferences(resolution = ExportResolution.RES_1080P),
            timeline = original.timeline.copy(videoClips = original.timeline.videoClips.map {
                it.copy(sourceEndMicros = 1_000_000)
            })
        )
        val db = Room.databaseBuilder(context, VideoEditorDatabase::class.java, "destination-audit-1080p.db").build()
        val repo = ProjectRepository(db.projectDao(), ProjectDocumentStorage(context))
        assertTrue(repo.save(project).isSuccess)
        val vm = ExportViewModel(context, repo, ExportEngine(context, CompositionBuilder()), Dispatchers.IO)
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(predicate: () -> Boolean) {
            ui.waitUntil(60_000) { predicate() }
            assertTrue("Timed out: ${vm.uiState.value}", predicate())
        }
        fun captureScreen(name: String) {
            ui.waitForIdle()
            Thread.sleep(300)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/release-audit-$name.png")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
                activity.setContent {
                    com.videoeditor.core.designsystem.theme.VideoEditorTheme(themeMode = com.videoeditor.core.designsystem.theme.ThemeMode.Dark) {
                        androidx.compose.material3.Scaffold(containerColor = com.videoeditor.core.designsystem.color.VEColor.DarkBackground) { insets ->
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(insets)) {
                                com.videoeditor.feature.export.ExportScreen(project.id, {}, vm)
                            }
                        }
                    }
                }
            }
            await { vm.uiState.value.project != null }
            assertEquals(ExportResolution.RES_1080P, vm.uiState.value.resolution)
            captureScreen("export-1080p-dialog")

            ui.onNodeWithText("Export Video").performClick()
            await { vm.uiState.value.phase in listOf(ExportPhase.Completed, ExportPhase.Failed) }
            assertEquals(ExportPhase.Completed, vm.uiState.value.phase)
            captureScreen("export-1080p-completed")

            val file = vm.uiState.value.outputFile
            assertNotNull("Output file must not be null", file)
            assertTrue("Output file must exist", file!!.exists())
            assertTrue("Output file must have non-zero length", file.length() > 0)

            // Verify video dimensions with retriever
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val effectiveWidth = if (rotation == 90 || rotation == 270) height else width
                val effectiveHeight = if (rotation == 90 || rotation == 270) width else height
                assertEquals("Exported width must match 1080p configuration", 1080, effectiveWidth)
                assertEquals("Exported height must match 1080p configuration", 1920, effectiveHeight)
            } finally {
                retriever.release()
            }

            // Copy exported file to Downloads via MediaStore for host analysis
            val resolver = context.contentResolver
            val downloadUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "release-audit-1080p-exported.mp4")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                })
            if (downloadUri != null) {
                resolver.openOutputStream(downloadUri)!!.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                resolver.update(downloadUri, android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }

            // Verify external player play/share URI and intent ClipData
            val shareUri = vm.shareUri()
            assertNotNull("Share URI must be valid", shareUri)
            assertEquals("content", shareUri!!.scheme)
        } finally {
            scenario.close()
            main { vm.cancelExport() }
            vm.uiState.value.outputFile?.delete()
            repo.deletePermanently(project.id)
            source.delete()
            db.close()
            context.deleteDatabase("destination-audit-1080p.db")
        }
    }
}
