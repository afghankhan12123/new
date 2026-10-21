package com.videoeditor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Lightweight project metadata for fast listing and search. The full editable
 * document is stored separately as a serialized file; this row is the index.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
    @ColumnInfo(name = "duration_micros") val durationMicros: Long,
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,
    @ColumnInfo(name = "aspect_ratio") val aspectRatio: String,
    @ColumnInfo(name = "clip_count") val clipCount: Int,
    @ColumnInfo(name = "audio_count") val audioCount: Int,
    @ColumnInfo(name = "text_count") val textCount: Int,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "in_trash") val inTrash: Boolean,
    @ColumnInfo(name = "last_export_failed") val lastExportFailed: Boolean,
    @ColumnInfo(name = "storage_bytes") val storageBytes: Long,
)
