package com.videoeditor.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoeditor.core.designsystem.spacing.VERadius
import com.videoeditor.core.designsystem.spacing.VESpacing

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var panel by remember { mutableStateOf<String?>(null) }
    panel?.let { title ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { panel = null }, containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(title) },
            text = { Column {
                when (title) {
                    "Theme" -> listOf("System", "Dark", "Light").forEach { mode ->
                        androidx.compose.material3.TextButton(onClick = { viewModel.setThemeMode(mode); panel = null }) { Text(mode) }
                    }
                    "Default Resolution" -> com.videoeditor.core.model.ExportResolution.entries.forEach { resolution ->
                        androidx.compose.material3.TextButton(onClick = { viewModel.setDefaultResolution(resolution.name); panel = null }) { Text(resolution.label) }
                    }
                    "Help & Feedback" -> Column { Text("Import photos or videos, select a clip to trim or split, then add music and captions. Drag timeline handles to trim; hold a clip to move it. Export produces an MP4 you can save or share. To report an issue, include the app version, Android version and steps to reproduce when contacting your app distributor.")
                        androidx.compose.material3.TextButton(onClick = {
                            val feedback = "Video Editor 1.0.0\nAndroid ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\nDevice: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\nSteps to reproduce:\nExpected behavior:\nActual behavior:"
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, feedback) }
                            runCatching { context.startActivity(android.content.Intent.createChooser(intent, "Share feedback details")) }
                        }) { Text("Share Diagnostic Details") }
                    }
                    "About" -> Text("Video Editor 1.0.0\nOffline editing. No account, AI features or forced watermark. Built with Android Media3. MP4 H.264/AAC export; source frame timing.\n\nPrivacy: this app has no network permission or analytics. Projects and recorded audio are stored locally. Android backup and the save/share destinations you choose follow their own policies.")
                    "Thumbnail cache cleared" -> Text("Decoded thumbnails and waveform cache have been cleared. They will regenerate when needed.")
                    else -> Text("Temporary cache files older than one day are scheduled for cleanup. Projects, source media and completed exports are retained.")
                }
            } }, confirmButton = { androidx.compose.material3.TextButton(onClick = { panel = null }) { Text("Done") } })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SettingsTopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VESpacing.Md),
        ) {
            SectionHeader("Appearance")
            SettingsRow(
                icon = Icons.Default.Brightness6,
                title = "Theme",
                value = prefs.themeMode,
                onClick = { panel = "Theme" },
            )
            Spacer(modifier = Modifier.height(VESpacing.Sm))

            SectionHeader("Project Settings")
            SettingsRow(
                icon = Icons.Outlined.PhotoSizeSelectLarge,
                title = "Default Resolution",
                value = prefs.defaultResolution.replace("RES_", ""),
                onClick = { panel = "Default Resolution" },
            )
            SettingsRow(
                icon = Icons.Outlined.Speed,
                title = "Frame Timing",
                value = "Automatic",
                onClick = { panel = "About" },
            )
            SwitchRow(
                icon = Icons.Outlined.Save,
                title = "Auto Save",
                checked = prefs.autosaveEnabled,
                onCheckedChange = viewModel::setAutosaveEnabled,
            )
            SwitchRow(
                icon = Icons.Outlined.PhotoLibrary,
                title = "Save to Photos",
                checked = prefs.saveToPhotos,
                onCheckedChange = viewModel::setSaveToPhotos,
            )
            Spacer(modifier = Modifier.height(VESpacing.Sm))

            SectionHeader("Storage")
            SettingsRow(
                icon = Icons.Default.Storage,
                title = "Clear Thumbnail Cache",
                value = "",
                onClick = { viewModel.clearThumbnailCache(); panel = "Thumbnail cache cleared" },
            )
            SettingsRow(
                icon = Icons.Default.Storage,
                title = "Clear Temporary Files",
                value = "",
                onClick = { viewModel.clearTemporaryFiles(); panel = "Temporary files" },
            )
            Spacer(modifier = Modifier.height(VESpacing.Sm))

            SectionHeader("General")
            SettingsRow(
                icon = Icons.Outlined.HelpOutline,
                title = "Help & Feedback",
                value = "",
                onClick = { panel = "Help & Feedback" },
            )
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = "About",
                value = "Version 1.0.0",
                onClick = { panel = "About" },
            )
            Spacer(modifier = Modifier.height(VESpacing.Xxl))
        }
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VESpacing.Xs, vertical = VESpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = VESpacing.Sm),
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VERadius.Button))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = VESpacing.Md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(VESpacing.Md))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.widthIn(max = 140.dp),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(VESpacing.Xs))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VERadius.Button))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = VESpacing.Md, vertical = VESpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(VESpacing.Md))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
