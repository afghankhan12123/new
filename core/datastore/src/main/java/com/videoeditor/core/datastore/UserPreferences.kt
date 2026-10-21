package com.videoeditor.core.datastore

import com.videoeditor.core.model.ExportQuality
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.core.model.FrameRate

/** Snapshot of user-configurable preferences, read from DataStore. */
data class UserPreferences(
    val themeMode: String = "System",
    val defaultResolution: String = ExportResolution.RES_1080P.name,
    val defaultFrameRate: String = FrameRate.FPS_30.name,
    val defaultQuality: String = ExportQuality.Standard.name,
    val autosaveEnabled: Boolean = true,
    val saveToPhotos: Boolean = true,
)
