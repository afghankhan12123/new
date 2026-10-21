package com.videoeditor.feature.editor

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource

/** Kit geometry with existing native semantics; unmatched specialized controls retain their symbol. */
internal object EditorIcons {
    fun resource(name: String): Int? = when (name.substringAfterLast('.')) {
        "Videocam" -> R.drawable.ic_editor_video
        "ArrowBack" -> R.drawable.ic_editor_back
        "Close" -> R.drawable.ic_editor_close
        "Check" -> R.drawable.ic_editor_check
        "Crop" -> R.drawable.ic_editor_crop_ratio
        "PlayArrow" -> R.drawable.ic_editor_play
        "Pause" -> R.drawable.ic_editor_pause
        "Undo" -> R.drawable.ic_editor_undo
        "Redo" -> R.drawable.ic_editor_redo
        "Fullscreen" -> R.drawable.ic_editor_fullscreen
        "ExpandMore" -> R.drawable.ic_editor_chevron_down
        "ExpandLess" -> R.drawable.ic_editor_panel
        "ContentCut" -> R.drawable.ic_editor_split
        "CompareArrows" -> R.drawable.ic_editor_transitions
        "RotateRight" -> R.drawable.ic_editor_rotate
        "Gradient" -> R.drawable.ic_editor_fade
        "VolumeUp" -> R.drawable.ic_editor_volume
        "Speed" -> R.drawable.ic_editor_speed
        "ContentCopy" -> R.drawable.ic_editor_duplicate
        "Delete" -> R.drawable.ic_editor_delete
        "TextFields" -> R.drawable.ic_editor_text
        "MusicNote" -> R.drawable.ic_editor_music
        "Filter" -> R.drawable.ic_editor_filters
        "Tune" -> R.drawable.ic_editor_adjust
        "StarOutline" -> R.drawable.ic_editor_stickers
        "Add" -> R.drawable.ic_editor_plus
        "Lock" -> R.drawable.ic_editor_lock
        "LockOpen" -> R.drawable.ic_editor_unlock
        "Search" -> R.drawable.ic_editor_search
        "Mic" -> R.drawable.ic_editor_voiceover
        "Flip" -> R.drawable.ic_editor_flip_horizontal
        "FlipCameraAndroid" -> R.drawable.ic_editor_flip_horizontal
        "Layers" -> R.drawable.ic_editor_layers
        "FormatAlignLeft" -> R.drawable.ic_editor_align_left
        "FormatAlignCenter" -> R.drawable.ic_editor_align_center
        "FormatAlignRight" -> R.drawable.ic_editor_align_right
        "Refresh" -> R.drawable.ic_editor_reset
        "Image" -> R.drawable.ic_editor_image
        "PhotoLibrary" -> R.drawable.ic_editor_image
        "ArrowForward" -> R.drawable.ic_editor_arrow_right
        else -> null
    }
    fun tool(label: String): Int? = when (label) {
        "Edit" -> R.drawable.ic_editor_edit
        "Audio" -> R.drawable.ic_editor_audio
        "Text" -> R.drawable.ic_editor_text
        "Filters" -> R.drawable.ic_editor_filters
        "Adjust" -> R.drawable.ic_editor_adjust
        "Speed" -> R.drawable.ic_editor_speed
        "Transition" -> R.drawable.ic_editor_transitions
        "Canvas" -> R.drawable.ic_editor_canvas
        "Effects" -> R.drawable.ic_editor_filters
        "Mask" -> R.drawable.ic_editor_crop_ratio
        "Chroma" -> R.drawable.ic_editor_adjust
        "Blend" -> R.drawable.ic_editor_layers
        "More" -> R.drawable.ic_editor_more
        "Overlay" -> R.drawable.ic_editor_picture_in_picture
        "Trim" -> R.drawable.ic_editor_trim
        "Split" -> R.drawable.ic_editor_split
        "Crop" -> R.drawable.ic_editor_crop
        "Rotate" -> R.drawable.ic_editor_rotate
        "Fade" -> R.drawable.ic_editor_fade
        "Volume" -> R.drawable.ic_editor_volume
        "Duplicate" -> R.drawable.ic_editor_duplicate
        "Delete" -> R.drawable.ic_editor_delete
        else -> null
    }
}

@Composable
internal fun EditorIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current) {
    val resource = EditorIcons.resource(imageVector.name)
    if (resource == null) Icon(imageVector, contentDescription, modifier, tint)
    else Icon(painterResource(resource), contentDescription, modifier, tint)
}

@Composable
internal fun EditorToolbarIcon(label: String, tint: Color, fallback: ImageVector, modifier: Modifier) {
    val resource = EditorIcons.tool(label)
    if (resource == null) EditorIcon(fallback, null, modifier, tint)
    else Icon(painterResource(resource), null, modifier, tint)
}
