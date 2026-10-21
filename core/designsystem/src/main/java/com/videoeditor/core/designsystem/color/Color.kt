package com.videoeditor.core.designsystem.color

import androidx.compose.ui.graphics.Color

/**
 * Central color tokens for the Video Editor design system.
 * Values follow the supplied premium dark/light palettes.
 */
object VEColor {

    // Dark theme
    val DarkBackground = Color(0xFF101113)
    val DarkPrimarySurface = Color(0xFF191A1E)
    val DarkSecondarySurface = Color(0xFF25262B)
    val DarkElevatedSurface = Color(0xFF303138)
    val DarkPrimaryText = Color(0xFFF8FAFC)
    val DarkSecondaryText = Color(0xFFABADB7)
    val DarkMutedText = Color(0xFF737680)
    val DarkBorder = Color(0xFF35363E)

    // Light theme
    val LightBackground = Color(0xFFFFFFFF)
    val LightSecondaryBackground = Color(0xFFF5F7FB)
    val LightPrimarySurface = Color(0xFFFFFFFF)
    val LightSecondarySurface = Color(0xFFEEF2F7)
    val LightPrimaryText = Color(0xFF101522)
    val LightSecondaryText = Color(0xFF667085)
    val LightMutedText = Color(0xFF8993A5)
    val LightBorder = Color(0xFFE4E9F1)

    // Shared accents
    val PrimaryBlue = Color(0xFF168BFF)
    val PrimaryBlueLight = Color(0xFF155EEF)
    val AccentCyan = Color(0xFF19C5F3)
    val AccentPurple = Color(0xFFA833F5)

    val Success = Color(0xFF26C98B)
    val Warning = Color(0xFFF59E42)
    val Error = Color(0xFFF04462)

    // Gradient stops (Cyan -> Blue -> Purple)
    val GradientStart = Color(0xFF19C5F3)
    val GradientMiddle = Color(0xFF0866FF)
    val GradientEnd = Color(0xFFA833F5)
}
