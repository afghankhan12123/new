package com.videoeditor.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.database.VideoEditorDatabase
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.database.storage.ProjectDocumentStorage
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectReliabilityDeviceTest {
    @Test fun unicode_search_filters_trash_and_atomic_recovery() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, VideoEditorDatabase::class.java).build()
        val storage = ProjectDocumentStorage(context)
        val repository = ProjectRepository(db.projectDao(), storage)
        val first = ProjectCreator().create("رحلة 世界 Urdu اردو Pashto پښتو 100% ${"long ".repeat(40)}", emptyList())
        val second = ProjectCreator().create("City Lights", emptyList())
        val created = mutableListOf(first.id, second.id)
        try {
            assertTrue(repository.save(first).isSuccess); assertTrue(repository.save(second).isSuccess)
            assertEquals(listOf(first.id), repository.observeSearch("世界").first().map { it.id })
            assertEquals(listOf(first.id), repository.observeSearch("100%").first().map { it.id })
            assertEquals(listOf(second.id), repository.observeSearch("CITY").first().map { it.id })
            assertEquals(2, repository.observeFiltered("", false, false, false).first().size)
            assertTrue(repository.observeFiltered("", false, true, false).first().isEmpty())
            assertTrue(repository.toggleFavorite(first.id).isSuccess)
            assertEquals(listOf(first.id), repository.observeFiltered("اردو", false, true, false).first().map { it.id })
            repository.moveToTrash(first.id)
            assertTrue(repository.observeSearch("世界").first().isEmpty())
            assertTrue(repository.loadDocument(first.id).getOrNull()!!.inTrash)
            assertTrue(repository.rename(first.id, "Renamed 世界").isSuccess)
            assertTrue(repository.getById(first.id)!!.inTrash)
            repository.restoreFromTrash(first.id)
            assertFalse(repository.loadDocument(first.id).getOrNull()!!.inTrash)
            val duplicate = repository.duplicate(first.id).getOrNull()!!; created += duplicate
            assertEquals("Renamed 世界 (copy)", repository.loadDocument(duplicate).getOrNull()!!.name)
            // Simulate process death during AtomicFile writing: an unfinished
            // .new cannot replace the last successfully committed document.
            File(storage.documentFile(first.id).path + ".new").writeText("{partial")
            assertEquals("Renamed 世界", ProjectDocumentStorage(context).load(first.id).getOrNull()!!.name)
            assertFalse(File(storage.documentFile(first.id).path + ".new").exists())
            assertTrue(repository.save(repository.loadDocument(first.id).getOrNull()!!.copy(name = "Recovered")).isSuccess)
            assertEquals("Recovered", ProjectDocumentStorage(context).load(first.id).getOrNull()!!.name)
        } finally { created.forEach { repository.deletePermanently(it) }; db.close() }
    }
}
