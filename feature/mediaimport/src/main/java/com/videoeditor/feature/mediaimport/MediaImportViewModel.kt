package com.videoeditor.feature.mediaimport

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videoeditor.core.common.di.IoDispatcher
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.media.MediaMetadataExtractor
import com.videoeditor.core.model.MediaRef
import com.videoeditor.editing.ProjectCreator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MediaImportUiState(
    val selectedMedia: List<MediaRef> = emptyList(),
    val isProcessing: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class MediaImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataExtractor: MediaMetadataExtractor,
    private val projectCreator: ProjectCreator,
    private val projectRepository: ProjectRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaImportUiState())
    val uiState: StateFlow<MediaImportUiState> = _uiState.asStateFlow()

    private fun ensureDurableUri(uri: Uri): Uri? =
        com.videoeditor.core.media.DurableMediaImport.retain(context, uri)

    /**
     * Handles the URIs returned by the Photo Picker. Extracts metadata for each
     * in the background, then makes them available for project creation.
     */
    fun onMediaPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            val refs = withContext(ioDispatcher) {
                uris.mapNotNull { rawUri ->
                    val uri = ensureDurableUri(rawUri) ?: return@mapNotNull null
                    metadataExtractor.extract(context, uri).getOrNull()
                }
            }
            _uiState.value = _uiState.value.copy(
                selectedMedia = (_uiState.value.selectedMedia + refs).distinctBy { it.uri },
                isProcessing = false,
                error = if (refs.isEmpty()) "No supported media could be imported." else null,
            )
        }
    }

    /**
     * Creates the project from the confirmed selection and returns its id. Only
     * called once the user confirms — opening the picker never creates a project.
     */
    fun createProject(onCreated: (String) -> Unit) {
        val media = _uiState.value.selectedMedia
        if (media.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Select at least one video or photo.")
            return
        }
        viewModelScope.launch {
            val project = withContext(ioDispatcher) {
                projectCreator.create(name = "New Project", mediaRefs = media)
            }
            val result = withContext(ioDispatcher) { projectRepository.save(project) }
            if (result.isSuccess) onCreated(project.id)
            else reportError("The project could not be saved. Free storage and try again.")
        }
    }

    fun reportError(message: String) { _uiState.value = _uiState.value.copy(error = message) }

    fun removeMedia(refId: String) {
        _uiState.value = _uiState.value.copy(
            selectedMedia = _uiState.value.selectedMedia.filterNot { it.id == refId }
        )
    }
}
