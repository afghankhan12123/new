package com.videoeditor.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.videoeditor.core.designsystem.color.VEColor

private val DarkColorScheme = darkColorScheme(
    primary = VEColor.PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = VEColor.DarkElevatedSurface,
    onPrimaryContainer = VEColor.DarkPrimaryText,
    secondary = VEColor.AccentCyan,
    onSecondary = Color.White,
    background = VEColor.DarkBackground,
    onBackground = VEColor.DarkPrimaryText,
    surface = VEColor.DarkPrimarySurface,
    onSurface = VEColor.DarkPrimaryText,
    surfaceVariant = VEColor.DarkSecondarySurface,
    onSurfaceVariant = VEColor.DarkSecondaryText,
    outline = VEColor.DarkBorder,
    error = VEColor.Error,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = VEColor.PrimaryBlueLight,
    onPrimary = Color.White,
    primaryContainer = VEColor.LightSecondarySurface,
    onPrimaryContainer = VEColor.LightPrimaryText,
    secondary = VEColor.AccentCyan,
    onSecondary = Color.White,
    background = VEColor.LightBackground,
    onBackground = VEColor.LightPrimaryText,
    surface = VEColor.LightPrimarySurface,
    onSurface = VEColor.LightPrimaryText,
    surfaceVariant = VEColor.LightSecondarySurface,
    onSurfaceVariant = VEColor.LightSecondaryText,
    outline = VEColor.LightBorder,
    error = VEColor.Error,
    onError = Color.White,
)

@Composable
fun VideoEditorTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VETypography,
        content = content,
    )
}
