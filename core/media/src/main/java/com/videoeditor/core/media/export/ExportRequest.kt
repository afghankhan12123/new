package com.videoeditor.core.media.export

import com.videoeditor.core.model.ExportQuality
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.core.model.FrameRate
import java.io.File

/** A fully-resolved export job request. */
data class ExportRequest(
    val projectId: String,
    val outputFile: File,
    val resolution: ExportResolution,
    val frameRate: FrameRate,
    val quality: ExportQuality,
)

/** Real progress reported by the rendering engine, 0..1. */
data class ExportProgress(
    val fraction: Float,
    val stage: ExportStage,
)

enum class ExportStage {
    Preparing,
    Rendering,
    ProcessingAudio,
    Finalizing,
    Saving,
}
