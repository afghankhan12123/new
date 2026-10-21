package com.videoeditor.feature.projects

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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ProjectFilter { All, Videos, Favorites, Trash }

data class ProjectsUiState(
    val projects: List<ProjectEntity> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(ProjectFilter.All)
    val filter: StateFlow<ProjectFilter> = _filter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val uiState: StateFlow<ProjectsUiState> = kotlinx.coroutines.flow.combine(_filter, _searchQuery) { f, query -> f to query }
        .flatMapLatest { (f, query) ->
            projectRepository.observeFiltered(query, f == ProjectFilter.Trash,
                f == ProjectFilter.Favorites, f == ProjectFilter.Videos)
        }
        .map { ProjectsUiState(projects = it, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProjectsUiState(),
        )

    suspend fun coverFor(id: String): Pair<com.videoeditor.core.model.MediaRef, Long>? {
        val doc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { projectRepository.loadDocument(id).let { (it as? com.videoeditor.core.common.result.Result.Success)?.value } } ?: return null
        val clip = doc.timeline.videoClips.firstOrNull() ?: return null
        return doc.mediaRef(clip.mediaRefId)?.let { it to clip.sourceStartMicros }
    }

    fun setFilter(f: ProjectFilter) {
        _filter.value = f
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun renameProject(id: String, newName: String) = viewModelScope.launch {
        projectRepository.rename(id, newName)
    }

    fun duplicateProject(id: String) = viewModelScope.launch {
        projectRepository.duplicate(id)
    }

    fun moveToTrash(id: String) = viewModelScope.launch {
        projectRepository.moveToTrash(id)
    }

    fun restoreFromTrash(id: String) = viewModelScope.launch {
        projectRepository.restoreFromTrash(id)
    }

    fun deletePermanently(id: String) = viewModelScope.launch {
        projectRepository.deletePermanently(id)
    }

    fun toggleFavorite(id: String) = viewModelScope.launch {
        projectRepository.toggleFavorite(id)
    }
}
