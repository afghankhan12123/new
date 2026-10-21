package com.videoeditor.feature.projects

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoeditor.core.database.entity.ProjectEntity
import com.videoeditor.core.designsystem.gradient.VEGradient
import com.videoeditor.core.designsystem.spacing.VERadius
import com.videoeditor.core.designsystem.spacing.VESpacing

@Composable
fun ProjectsScreen(
    onOpenProject: (String) -> Unit,
    onBack: () -> Unit,
    onNewProject: () -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var options by remember { mutableStateOf<ProjectEntity?>(null) }
    var action by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
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
                    val source by androidx.compose.runtime.produceState<Pair<com.videoeditor.core.model.MediaRef, Long>?>(null, project.id, project.updatedAtMillis) { value = viewModel.coverFor(project.id) }
                    source?.let { (ref, time) -> coil.compose.AsyncImage(model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(ref.uri).videoFrameMicros(time).apply { if (ref.type == com.videoeditor.core.model.MediaType.Video) decoderFactory(coil.decode.VideoFrameDecoder.Factory()) }.build(), contentDescription = "Project cover", modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                })
        } else if (action == "Delete Project?") com.videoeditor.core.designsystem.component.DecisionDialog(
            title = "Delete Project?", message = "Delete this project ${if (project.inTrash) "permanently" else "to Trash"}? Your original media will be retained.",
            primaryLabel = "Delete", onPrimary = { if (project.inTrash) viewModel.deletePermanently(project.id) else viewModel.moveToTrash(project.id); options = null; action = null },
            onDismiss = { options = null; action = null }, destructive = true,
            icon = Icons.Default.Delete)
        else androidx.compose.material3.AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { options = null; action = null },
            title = { Text(action ?: project.name) },
            text = { Column {
                when (action) {
                    "Rename" -> androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Project name") })
                    "Project Details" -> Text("${project.width} × ${project.height}\n${formatDuration(project.durationMicros)}\n${project.clipCount} visual clips · ${project.audioCount} audio clips · ${project.textCount} titles\nProject document: ${project.storageBytes} bytes")
                    "Delete Project?" -> Text("Delete this project ${if (project.inTrash) "permanently" else "to Trash"}? Your original media will be retained.")
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
            } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                when(action) {
                    "Rename" -> if (name.isNotBlank()) viewModel.renameProject(project.id, name.trim())
                    "Delete Project?" -> if (project.inTrash) viewModel.deletePermanently(project.id) else viewModel.moveToTrash(project.id)
                }
                options = null; action = null
            }, enabled = action != "Rename" || name.isNotBlank()) { Text(if (action == "Delete Project?") "Delete" else "Done") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { options = null; action = null }) { Text("Cancel") } })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ProjectsTopBar(onBack = onBack)
        SearchField(
            query = query,
            onQueryChange = viewModel::onSearchQueryChange,
        )
        FilterChips(current = filter, onSelect = viewModel::setFilter)

        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxWidth().padding(VESpacing.Xxl),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.projects.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(Modifier.padding(VESpacing.Xl), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (query.isNotBlank()) Icons.Default.Search else Icons.Default.FolderOpen,
                    contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(VESpacing.Lg))
                Text(
                    if (query.isNotBlank()) "No projects found" else if (filter == ProjectFilter.Trash) "Trash is empty" else "No projects yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(VESpacing.Sm))
                Text(if (query.isNotBlank()) "Try another keyword or check your spelling." else if (filter == ProjectFilter.Trash) "Deleted projects appear here until you restore or remove them." else "Start creating amazing videos.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (query.isBlank() && filter != ProjectFilter.Trash) {
                    Spacer(Modifier.height(VESpacing.Xl))
                    com.videoeditor.core.designsystem.component.GradientButton("New Project", onClick = onNewProject)
                }
                }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = VESpacing.Md),
                verticalArrangement = Arrangement.spacedBy(VESpacing.Sm),
                contentPadding = PaddingValues(top = VESpacing.Sm, bottom = VESpacing.Xxl),
            ) {
                items(uiState.projects, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        loadCover = { viewModel.coverFor(project.id) },
                        inTrash = filter == ProjectFilter.Trash,
                        onClick = { focusManager.clearFocus(); onOpenProject(project.id) },
                        onRestore = { viewModel.restoreFromTrash(project.id) },
                        onDelete = { focusManager.clearFocus(); options = project; action = "Delete Project?" },
                        onMoveToTrash = { focusManager.clearFocus(); options = project },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectsTopBar(onBack: () -> Unit) {
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
            "All Projects",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VESpacing.Md)
            .height(48.dp)
            .clip(RoundedCornerShape(VERadius.Button))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = VESpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Search,
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
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
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
        if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Clear search") }
    }
}

@Composable
private fun FilterChips(current: ProjectFilter, onSelect: (ProjectFilter) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VESpacing.Md, vertical = VESpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(VESpacing.Xs),
    ) {
        items(ProjectFilter.entries.toList()) { f ->
            val selected = f == current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(VERadius.Control))
                    .background(
                        if (selected) com.videoeditor.core.designsystem.color.VEColor.PrimaryBlue
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(f) }
                    .padding(horizontal = VESpacing.Md, vertical = VESpacing.Xs),
            ) {
                Text(
                    f.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) androidx.compose.ui.graphics.Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectEntity,
    loadCover: suspend () -> Pair<com.videoeditor.core.model.MediaRef, Long>?,
    inTrash: Boolean,
    onClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onMoveToTrash: () -> Unit,
) {
    val cover by androidx.compose.runtime.produceState<Pair<com.videoeditor.core.model.MediaRef, Long>?>(null, project.id, project.updatedAtMillis) { value = loadCover() }
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(VERadius.Card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(VESpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 56.dp)
                    .clip(RoundedCornerShape(VERadius.Control))
                    .background(VEGradient.primary),
            ) {
                cover?.let { (ref, time) ->
                    coil.compose.AsyncImage(model = coil.request.ImageRequest.Builder(context).data(ref.uri)
                        .apply { if (ref.type == com.videoeditor.core.model.MediaType.Video) {
                            decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                            videoFrameMicros(time)
                        } }.build(), contentDescription = "Project cover",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(modifier = Modifier.width(VESpacing.Sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    "${formatDuration(project.durationMicros)}  ·  ${project.height}p",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (inTrash) {
                Text(
                    "Restore",
                    style = MaterialTheme.typography.labelLarge,
                    color = com.videoeditor.core.designsystem.color.VEColor.PrimaryBlue,
                    modifier = Modifier.clickable(onClick = onRestore),
                )
                Spacer(modifier = Modifier.width(VESpacing.Sm))
                Text(
                    "Delete",
                    style = MaterialTheme.typography.labelLarge,
                    color = com.videoeditor.core.designsystem.color.VEColor.Error,
                    modifier = Modifier.clickable(onClick = onDelete),
                )
            } else {
                IconButton(onClick = onMoveToTrash) {
                    Icon(
                        Icons.Default.MoreHoriz,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatDuration(micros: Long): String {
    val totalSeconds = micros / 1_000_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
