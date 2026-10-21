package com.videoeditor.core.designsystem.color

import androidx.compose.ui.graphics.Color

/**
 * Timeline-specific surface and accent tokens, layered on [VEColor] so the
 * editing workspace keeps one restrained palette. Kept separate from the app
 * tokens because the timeline is always dark (it sits over video regardless of
 * the app's light/dark theme) and needs a few extra roles: track backgrounds,
 * waveform and text-clip accents, and the fixed playhead.
 */
object VETimelineColor {
    /** The timeline panel background, a touch lighter than the app backdrop. */
    val Background = Color(0xFF17181C)

    /** Ruler strip background, slightly recessed. */
    val RulerBackground = Color(0xFF17181C)

    /** Per-track lane background. */
    val TrackBackground = Color(0xFF17181C)

    /** Hairline separators between rows. */
    val Divider = VEColor.DarkBorder

    /** Empty clip placeholder before its thumbnails decode. */
    val ClipPlaceholder = VEColor.DarkElevatedSurface

    /** Video clip selection outline. */
    val SelectionBorder = Color(0xFF79E2D3)

    /** Text-track clips. */
    val TextClip = Color(0xFFA078E8)
    val TextClipBackground = Color(0xFF714BAD)

    /** Audio-track clips and their waveform. */
    val AudioClipBackground = Color(0xFF176F79)
    val Waveform = Color(0xFF79E2D3)
    val WaveformMuted = VEColor.DarkMutedText

    /** Trim handles and the fixed playhead. */
    val Handle = Color.White
    val Playhead = Color.White

    /** Ruler ticks and labels. */
    val TickMajor = VEColor.DarkSecondaryText
    val TickMinor = VEColor.DarkMutedText
    val Label = VEColor.DarkSecondaryText

    /** Magnetic snap indicator. */
    val Snap = VEColor.AccentCyan
}
