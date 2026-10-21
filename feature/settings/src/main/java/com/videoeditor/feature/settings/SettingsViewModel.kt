package com.videoeditor.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videoeditor.core.datastore.UserPreferences
import com.videoeditor.core.datastore.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    private val timelineMedia: com.videoeditor.core.media.timeline.TimelineMediaRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences(),
        )

    fun clearThumbnailCache() { timelineMedia.clearCaches() }
    fun clearTemporaryFiles() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        // Never delete project documents, captured sources or completed exports.
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        context.cacheDir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun setThemeMode(mode: String) = viewModelScope.launch {
        preferencesRepository.setThemeMode(mode)
    }

    fun setDefaultResolution(value: String) = viewModelScope.launch {
        preferencesRepository.setDefaultResolution(value)
    }

    fun setDefaultFrameRate(value: String) = viewModelScope.launch {
        preferencesRepository.setDefaultFrameRate(value)
    }

    fun setAutosaveEnabled(value: Boolean) = viewModelScope.launch {
        preferencesRepository.setAutosaveEnabled(value)
    }

    fun setSaveToPhotos(value: Boolean) = viewModelScope.launch {
        preferencesRepository.setSaveToPhotos(value)
    }
}
