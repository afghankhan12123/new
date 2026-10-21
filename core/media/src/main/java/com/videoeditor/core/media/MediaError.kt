package com.videoeditor.core.media

/** Typed errors surfaced by the media engine. */
enum class ExportFailureCategory { Decoder, Encoder, Shader, Egl, UnsupportedMedia, MissingAsset, Cancelled, Storage, InvalidPlan, Finalization, Unknown }

sealed interface MediaError {
    val message: String

    data class MetadataExtractionFailed(override val message: String) : MediaError
    data class ThumbnailFailed(override val message: String) : MediaError
    data class WaveformFailed(override val message: String) : MediaError
    data class MediaNotFound(override val message: String) : MediaError
    data class UnsupportedFormat(override val message: String) : MediaError
    data class ExportFailed(override val message: String, val cause: Throwable? = null, val category: ExportFailureCategory = ExportFailureCategory.Unknown, val sessionId: String? = null) : MediaError
    data class InsufficientStorage(override val message: String) : MediaError
}
