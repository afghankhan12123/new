package com.videoeditor.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.videoeditor.core.designsystem.color.VEColor

/** Reachable decision actions with the application's own surface and action styling. */
@Composable
fun DecisionDialog(title: String, message: String, primaryLabel: String, onPrimary: () -> Unit,
    onDismiss: () -> Unit, secondaryLabel: String? = null, onSecondary: (() -> Unit)? = null,
    destructive: Boolean = false, forceDark: Boolean = false, icon: ImageVector = Icons.Outlined.Description,
    dismissLabel: String = "Cancel") {
    val foreground = if (forceDark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (forceDark) VEColor.DarkSecondaryText else MaterialTheme.colorScheme.onSurfaceVariant
    val surface = if (forceDark) VEColor.DarkPrimarySurface else MaterialTheme.colorScheme.surface
    val border = if (forceDark) VEColor.DarkSecondarySurface else MaterialTheme.colorScheme.outline.copy(alpha = .4f)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .8f).dp
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = surface, border = BorderStroke(1.dp, border)) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = if (destructive) VEColor.Error else VEColor.PrimaryBlue, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text(title, color = foreground, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(message, color = muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                if (destructive) Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VEColor.Error, contentColor = Color.White), shape = RoundedCornerShape(8.dp)) { Text(primaryLabel) }
                else GradientButton(primaryLabel, onPrimary)
                if (secondaryLabel != null && onSecondary != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        border = BorderStroke(1.dp, border), colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground), shape = RoundedCornerShape(8.dp)) { Text(secondaryLabel) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, border), colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground), shape = RoundedCornerShape(8.dp)) { Text(dismissLabel) }
            }
        }
    }
}
