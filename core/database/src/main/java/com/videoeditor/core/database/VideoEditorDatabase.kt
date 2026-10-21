package com.videoeditor.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.videoeditor.core.database.dao.ExportJobDao
import com.videoeditor.core.database.dao.ProjectDao
import com.videoeditor.core.database.entity.ExportJobEntity
import com.videoeditor.core.database.entity.ProjectEntity

@Database(
    entities = [
        ProjectEntity::class,
        ExportJobEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class VideoEditorDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun exportJobDao(): ExportJobDao

    companion object {
        const val NAME = "video_editor.db"
    }
}
