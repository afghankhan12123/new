package com.videoeditor.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.videoeditor.core.database.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects WHERE in_trash = 0 ORDER BY updated_at DESC")
    fun observeRecent(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE in_trash = 0 ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE in_trash = 1 ORDER BY updated_at DESC")
    fun observeTrash(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE in_trash = 0 AND is_favorite = 1 ORDER BY updated_at DESC")
    fun observeFavorites(): Flow<List<ProjectEntity>>

    @Query(
        "SELECT * FROM projects WHERE in_trash = 0 AND instr(lower(name), lower(:query)) > 0 " +
            "ORDER BY updated_at DESC"
    )
    fun observeSearch(query: String): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE in_trash = :trash AND (:favorites = 0 OR is_favorite = 1) AND (:videos = 0 OR clip_count > 0) AND instr(lower(name), lower(:query)) > 0 ORDER BY updated_at DESC")
    fun observeFiltered(query: String, trash: Boolean, favorites: Boolean, videos: Boolean): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE projects SET in_trash = 1, updated_at = :now WHERE id = :id")
    suspend fun moveToTrash(id: String, now: Long)

    @Query("UPDATE projects SET in_trash = 0, updated_at = :now WHERE id = :id")
    suspend fun restoreFromTrash(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM projects WHERE in_trash = 0")
    fun observeCount(): Flow<Int>
}
