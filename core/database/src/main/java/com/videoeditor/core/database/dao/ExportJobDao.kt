package com.videoeditor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.videoeditor.core.database.entity.ExportJobEntity

@Dao
interface ExportJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: ExportJobEntity)

    @Query("SELECT * FROM export_jobs WHERE project_id = :projectId ORDER BY started_at DESC")
    suspend fun getForProject(projectId: String): List<ExportJobEntity>

    @Query("SELECT * FROM export_jobs WHERE id = :id")
    suspend fun getById(id: String): ExportJobEntity?

    @Query("DELETE FROM export_jobs WHERE project_id = :projectId")
    suspend fun deleteForProject(projectId: String)
}
