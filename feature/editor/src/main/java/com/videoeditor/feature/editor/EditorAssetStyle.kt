package com.videoeditor.feature.editor

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Asset-kit surfaces scoped to the editor, preserving the established app theme. */
internal object EditorAssetStyle {
    val Export = Brush.horizontalGradient(listOf(EditorColor.PrimaryBlue, EditorColor.PrimaryBlue))
    val TextTrack = Brush.horizontalGradient(listOf(Color(0xFF6643F9), Color(0xFF9431DD)))
}
