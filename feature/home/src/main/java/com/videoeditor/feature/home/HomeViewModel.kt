package com.videoeditor.feature.home

import com.videoeditor.core.common.result.getOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videoeditor.core.database.entity.ProjectEntity
import com.videoeditor.core.database.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val projects: List<ProjectEntity> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HomeUiState> = _searchQuery.flatMapLatest { query ->
        val projects = if (query.isBlank()) projectRepository.observeRecent()
            else projectRepository.observeSearch(query.trim())
        projects.map { HomeUiState(projects = it, searchQuery = query, isLoading = false) }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    suspend fun coverFor(id: String): ProjectCover? {
        val project = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { projectRepository.loadDocument(id).getOrNull() } ?: return null
        return projectCover(project)
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun renameProject(id: String, newName: String) {
        viewModelScope.launch { projectRepository.rename(id, newName) }
    }

    fun duplicateProject(id: String) {
        viewModelScope.launch { projectRepository.duplicate(id) }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch { projectRepository.moveToTrash(id) }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch { projectRepository.toggleFavorite(id) }
    }
}

/** Source-derived cover identity; changing the first clip's trim invalidates the frame key. */
data class ProjectCover(val uri: String, val sourceMicros: Long, val key: String, val isVideo: Boolean)

internal fun projectCover(project: com.videoeditor.core.model.Project): ProjectCover? {
    val clip = project.timeline.videoClips.firstOrNull() ?: return null
    val media = project.mediaRef(clip.mediaRefId) ?: return null
    val time = clip.sourceStartMicros + clip.sourceDurationMicros / 4
    return ProjectCover(media.uri, time, "${media.uri}|${clip.sourceStartMicros}|${clip.sourceEndMicros}|${project.updatedAtMillis}", media.type == com.videoeditor.core.model.MediaType.Video)
}
