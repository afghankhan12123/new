package com.videoeditor.feature.export

/** Used for estimates and actual output metadata; never rounds a small file to zero MB. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes.coerceAtLeast(0)} B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
