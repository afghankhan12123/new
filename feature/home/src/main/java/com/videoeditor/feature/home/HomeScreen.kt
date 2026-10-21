package com.videoeditor.feature.home

import coil.request.videoFrameMicros
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoeditor.core.database.entity.ProjectEntity
import com.videoeditor.core.designsystem.color.VEColor
import com.videoeditor.core.designsystem.gradient.VEGradient
import com.videoeditor.core.designsystem.spacing.VERadius
import com.videoeditor.core.designsystem.spacing.VESpacing

@Composable
fun HomeScreen(
    onOpenProject: (String) -> Unit,
    onStartNewProject: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onShortcut: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    var options by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ProjectEntity?>(null) }
    var action by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    options?.let { project ->
        if (action == null) {
            com.videoeditor.core.designsystem.component.ProjectOptionsSheet(project.name,
                "${formatDuration(project.durationMicros)} · ${project.width} × ${project.height}", project.isFavorite,
                onAction = { item -> when(item) {
                    "Rename" -> { name = project.name; action = item }
                    "Project Details" -> action = item
                    "Duplicate" -> { viewModel.duplicateProject(project.id); options = null }
                    "Favorite", "Unfavorite" -> { viewModel.toggleFavorite(project.id); options = null }
                    else -> action = "Delete Project?"
                } }, onDismiss = { options = null; action = null }, cover = {
                    val source by androidx.compose.runtime.produceState<ProjectCover?>(null, project.id, project.updatedAtMillis) { value = viewModel.coverFor(project.id) }
                    source?.let { cover -> coil.compose.AsyncImage(model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(cover.uri).videoFrameMicros(cover.sourceMicros).apply { if (cover.isVideo) decoderFactory(coil.decode.VideoFrameDecoder.Factory()) }.build(), contentDescription = "Project cover", modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                })
        } else if (action == "Delete Project?") com.videoeditor.core.designsystem.component.DecisionDialog(
            title = "Delete Project?", message = "Move this project to Trash? Your original media will be retained.",
            primaryLabel = "Delete", onPrimary = { viewModel.deleteProject(project.id); options = null; action = null },
            onDismiss = { options = null; action = null }, destructive = true,
            icon = Icons.Default.Delete)
        else androidx.compose.material3.AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { options = null; action = null },
            title = { Text(action ?: project.name) },
            text = { Column {
                when(action) {
                    "Rename" -> androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Project name") })
                    "Project Details" -> Text("${project.width} × ${project.height}\n${formatDuration(project.durationMicros)}\n${project.clipCount} visual clips · ${project.audioCount} audio clips · ${project.textCount} titles")
                    "Delete Project?" -> Text("Move this project to Trash? Your original media will be retained.")
                    else -> listOf("Rename", "Duplicate", "Project Details", if (project.isFavorite) "Unfavorite" else "Favorite", "Delete").forEach { item ->
                        androidx.compose.material3.TextButton(onClick = {
                            when(item) {
                                "Rename" -> { name = project.name; action = item }
                                "Project Details" -> action = item
                                "Duplicate" -> { viewModel.duplicateProject(project.id); options = null }
                                "Favorite", "Unfavorite" -> { viewModel.toggleFavorite(project.id); options = null }
                                else -> action = "Delete Project?"
                            }
                        }) { Text(item) }
                    }
                }
            } }, confirmButton = { androidx.compose.material3.TextButton(onClick = {
                when(action) {
                    "Rename" -> if (name.isNotBlank()) viewModel.renameProject(project.id, name.trim())
                    "Delete Project?" -> viewModel.deleteProject(project.id)
                }
                options = null; action = null
            }, enabled = action != "Rename" || name.isNotBlank()) { Text(if (action == "Delete Project?") "Delete" else "Done") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { options = null; action = null }) { Text("Cancel") } })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = VESpacing.Md, vertical = VESpacing.Md),
        verticalArrangement = Arrangement.spacedBy(VESpacing.Sm),
    ) {
        item {
            Column {
                HomeHeader()
                Spacer(Modifier.height(VESpacing.Md))
                SearchField(searchQuery, viewModel::onSearchQueryChange)
                Spacer(Modifier.height(VESpacing.Md))
                NewProjectCard(onStartNewProject)
                Spacer(Modifier.height(VESpacing.Md))
                QuickShortcuts(onShortcut)
                Spacer(Modifier.height(VESpacing.Lg))
                RecentProjectsHeader(onNavigateToProjects)
            }
        }
        when {
            uiState.isLoading -> item { Box(Modifier.fillMaxWidth().padding(VESpacing.Xxl), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            uiState.projects.isEmpty() && searchQuery.isNotBlank() -> item { Text("No projects found. Try a different keyword.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = VESpacing.Xxl)) }
            uiState.projects.isEmpty() -> item { EmptyProjectsState(onStartNewProject) }
            else -> items(uiState.projects, key = { it.id }) { project ->
                ProjectCard(project, { viewModel.coverFor(project.id) }, { onOpenProject(project.id) }, { options = project })
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text(
            text = "Video Editor",
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Create. Edit. Share amazing videos.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(VERadius.Button))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = VESpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Search",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(VESpacing.Sm))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        "Search your projects...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun NewProjectCard(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 112.dp).clip(RoundedCornerShape(VERadius.Card))
        .background(VEColor.PrimaryBlueLight).testTag("new-project").clickable(onClick = onClick)
        .padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("New Project", style = MaterialTheme.typography.headlineSmall, color = Color.White,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Choose videos and photos", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .8f))
        }
        Box(Modifier.size(48.dp).background(Color.White.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun QuickShortcuts(onShortcut: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VESpacing.Xs),
    ) {
        ShortcutButton("Camera", Icons.Outlined.CameraAlt, onClick = { onShortcut("Camera") })
        ShortcutButton("Music", Icons.Default.MusicNote, onClick = { onShortcut("Music") })
        ShortcutButton("Text", Icons.Default.Title, onClick = { onShortcut("Text") })
        ShortcutButton("Aspect Ratio", Icons.Default.CropFree, onClick = { onShortcut("Aspect Ratio") })
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ShortcutButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 80.dp)
            .clip(RoundedCornerShape(VERadius.Button))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = VESpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(VERadius.Button))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.height(VESpacing.Xs))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentProjectsHeader(onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Recent Projects",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "See All",
            style = MaterialTheme.typography.labelLarge,
            color = VEColor.PrimaryBlue,
            modifier = Modifier.clickable(onClick = onSeeAll),
        )
    }
}

@Composable
private fun ProjectCard(project: ProjectEntity, loadCover: suspend () -> ProjectCover?, onClick: () -> Unit, onOptions: () -> Unit) {
    val cover by androidx.compose.runtime.produceState<ProjectCover?>(null, project.id, project.updatedAtMillis) {
        value = loadCover()
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("project-card-${project.id}")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(VERadius.Button),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
    ) {
        Row(
            modifier = Modifier.padding(end = VESpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 112.dp, height = 76.dp)
                    .clip(RoundedCornerShape(VERadius.Control))
                    .background(VEGradient.primary),
            ) {
                cover?.let { source ->
                    coil.compose.AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(source.uri)
                            .videoFrameMicros(source.sourceMicros)
                            .apply { if (source.isVideo) decoderFactory(coil.decode.VideoFrameDecoder.Factory()) }
                            .memoryCacheKey(source.key)
                            .diskCacheKey(source.key)
                            .build(),
                        contentDescription = "Project cover",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(modifier = Modifier.width(VESpacing.Sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "${formatDuration(project.durationMicros)}  ·  ${project.height}p",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    "Edited ${relativeTime(project.updatedAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onOptions) {
                Icon(
                    Icons.Default.MoreHoriz,
                    contentDescription = "Options",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun EmptyProjectsState(onStartNewProject: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = VESpacing.Xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No projects yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(VESpacing.Xs))
        Text(
            "Start creating amazing videos.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDuration(micros: Long): String {
    val totalSeconds = micros / 1_000_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun relativeTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes == 1L -> "1 minute ago"
        minutes < 60 -> "$minutes minutes ago"
        minutes < 120 -> "1 hour ago"
        minutes < 1440 -> "${minutes / 60} hours ago"
        minutes < 2880 -> "1 day ago"
        else -> "${minutes / 1440} days ago"
    }
}
