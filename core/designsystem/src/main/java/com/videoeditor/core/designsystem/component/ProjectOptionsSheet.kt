package com.videoeditor.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoeditor.core.designsystem.color.VEColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectOptionsSheet(name: String, metadata: String, favorite: Boolean, onAction: (String) -> Unit, onDismiss: () -> Unit,
    cover: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                cover()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val items = listOf(Triple("Rename", Icons.Default.Edit, false), Triple("Duplicate", Icons.Default.ContentCopy, false),
                Triple("Project Details", Icons.Default.Info, false), Triple(if (favorite) "Unfavorite" else "Favorite", Icons.Default.Star, false),
                Triple("Delete", Icons.Default.Delete, true))
            for ((label, icon, destructive) in items) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onAction(label) }.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val color = if (destructive) VEColor.Error else MaterialTheme.colorScheme.onSurface
                    Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp)); Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
