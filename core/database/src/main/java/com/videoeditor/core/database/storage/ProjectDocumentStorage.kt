package com.videoeditor.core.database.storage

import android.content.Context
import com.videoeditor.core.common.result.Result
import com.videoeditor.core.common.result.runCatchingResult
import com.videoeditor.core.model.Project
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the full editable [Project] document as a JSON file, separate from
 * the Room index row. Android AtomicFile retains a synced recovery copy so an
 * interrupted save never corrupts the on-disk project.
 */
@Singleton
class ProjectDocumentStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val projectsDir: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    fun documentFile(projectId: String): File = File(projectsDir, "$projectId.json")

    fun save(project: Project): Result<Unit, Throwable> = synchronized(documentLock) { runCatchingResult {
        val atomic = android.util.AtomicFile(documentFile(project.id))
        val output = atomic.startWrite()
        try {
            output.write(json.encodeToString(project).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    } }

    fun load(projectId: String): Result<Project, Throwable> = synchronized(documentLock) { runCatchingResult {
        android.util.AtomicFile(documentFile(projectId)).openRead().bufferedReader(Charsets.UTF_8).use {
            json.decodeFromString<Project>(it.readText())
        }
    } }

    fun delete(projectId: String) {
        synchronized(documentLock) { android.util.AtomicFile(documentFile(projectId)).delete() }
    }

    fun exists(projectId: String): Boolean = documentFile(projectId).exists()

    /** Total bytes used by all project documents. */
    fun totalStorageBytes(): Long =
        projectsDir.listFiles()?.sumOf { it.length() } ?: 0L

    private companion object { val documentLock = Any() }
}
