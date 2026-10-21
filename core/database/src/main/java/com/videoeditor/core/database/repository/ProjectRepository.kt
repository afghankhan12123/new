package com.videoeditor.core.database.repository

import com.videoeditor.core.common.result.Result
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.database.dao.ProjectDao
import com.videoeditor.core.database.entity.ProjectEntity
import com.videoeditor.core.database.storage.ProjectDocumentStorage
import com.videoeditor.core.model.Project
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Combines the Room index (fast listing/search) with the serialized document
 * (the authoritative edit). All mutations write the document first, then update
 * the index, so the index never points at a document that doesn't exist.
 */
@Singleton
class ProjectRepository @Inject constructor(
    private val projectDao: ProjectDao,
    private val documentStorage: ProjectDocumentStorage,
) {

    fun observeRecent(): Flow<List<ProjectEntity>> = projectDao.observeRecent()

    fun observeAll(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    fun observeTrash(): Flow<List<ProjectEntity>> = projectDao.observeTrash()

    fun observeFavorites(): Flow<List<ProjectEntity>> = projectDao.observeFavorites()

    fun observeSearch(query: String): Flow<List<ProjectEntity>> =
        projectDao.observeSearch(query)

    fun observeFiltered(query: String, trash: Boolean, favorites: Boolean, videos: Boolean) =
        projectDao.observeFiltered(query, trash, favorites, videos)

    fun observeCount(): Flow<Int> = projectDao.observeCount()

    suspend fun getById(id: String): ProjectEntity? = projectDao.getById(id)

    /** Loads the full editable document for a project. */
    suspend fun loadDocument(id: String): Result<Project, Throwable> = withContext(Dispatchers.IO) { documentStorage.load(id) }

    /**
     * Persists a project: writes the document atomically, then upserts the index
     * row derived from it.
     */
    suspend fun save(project: Project): Result<Unit, Throwable> = withContext(Dispatchers.IO) {
        documentStorage.save(project).let { if (it.isFailure) return@withContext it }
        projectDao.upsert(project.toEntity().copy(storageBytes = documentStorage.documentFile(project.id).length()))
        Result.Success(Unit)
    }

    suspend fun rename(id: String, newName: String): Result<Unit, Throwable> {
        val doc = withContext(Dispatchers.IO) { documentStorage.load(id) }.getOrNull() ?: return Result.Failure(
            IllegalStateException("Project not found: $id")
        )
        val updated = doc.copy(name = newName, updatedAtMillis = System.currentTimeMillis())
        return save(updated)
    }

    /** Moves a project to Trash. Never touches the original source media. */
    suspend fun moveToTrash(id: String) {
        val doc = withContext(Dispatchers.IO) { documentStorage.load(id) }.getOrNull() ?: return
        save(doc.copy(inTrash = true, updatedAtMillis = System.currentTimeMillis()))
    }

    suspend fun restoreFromTrash(id: String) {
        val doc = withContext(Dispatchers.IO) { documentStorage.load(id) }.getOrNull() ?: return
        save(doc.copy(inTrash = false, updatedAtMillis = System.currentTimeMillis()))
    }

    /** Permanently deletes a project and its document. Source media is untouched. */
    suspend fun deletePermanently(id: String) = withContext(Dispatchers.IO) {
        documentStorage.delete(id)
        projectDao.deleteById(id)
    }

    /** Duplicates a project document under a new id. */
    suspend fun duplicate(id: String): Result<String, Throwable> {
        val doc = withContext(Dispatchers.IO) { documentStorage.load(id) }.getOrNull()
            ?: return Result.Failure(IllegalStateException("Project not found: $id"))
        val copy = doc.copy(
            id = com.videoeditor.core.common.util.Ids.newId(),
            name = "${doc.name} (copy)",
            createdAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis(),
        )
        val result = save(copy)
        if (result is Result.Failure) return result
        return Result.Success(copy.id)
    }

    suspend fun toggleFavorite(id: String): Result<Unit, Throwable> {
        val doc = withContext(Dispatchers.IO) { documentStorage.load(id) }.getOrNull()
            ?: return Result.Failure(IllegalStateException("Project not found: $id"))
        return save(doc.copy(isFavorite = !doc.isFavorite))
    }

    suspend fun markExportFailed(id: String, failed: Boolean): Result<Unit, Throwable> {
        val doc = withContext(Dispatchers.IO) { documentStorage.load(id) }.getOrNull()
            ?: return Result.Failure(IllegalStateException("Project not found: $id"))
        return save(doc.copy(lastExportFailed = failed))
    }

    fun totalStorageBytes(): Long = documentStorage.totalStorageBytes()
}

private fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id,
    name = name,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    durationMicros = timeline.renderedDurationMicros,
    width = canvas.width,
    height = canvas.height,
    aspectRatio = canvas.aspectRatio.label,
    clipCount = timeline.videoClips.size,
    audioCount = timeline.audioClips.size,
    textCount = timeline.textOverlays.size,
    isFavorite = isFavorite,
    inTrash = inTrash,
    lastExportFailed = lastExportFailed,
    storageBytes = 0L, // populated by caller when known
)
