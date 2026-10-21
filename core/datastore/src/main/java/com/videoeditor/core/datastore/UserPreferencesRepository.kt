package com.videoeditor.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val preferences: Flow<UserPreferences> = dataStore.data.map { prefs ->
        UserPreferences(
            themeMode = prefs[KEY_THEME] ?: "System",
            defaultResolution = prefs[KEY_DEFAULT_RESOLUTION] ?: "RES_1080P",
            defaultFrameRate = prefs[KEY_DEFAULT_FRAME_RATE] ?: "FPS_30",
            defaultQuality = prefs[KEY_DEFAULT_QUALITY] ?: "Standard",
            autosaveEnabled = prefs[KEY_AUTOSAVE] ?: true,
            saveToPhotos = prefs[KEY_SAVE_TO_PHOTOS] ?: true,
        )
    }

    suspend fun setThemeMode(mode: String) = edit(KEY_THEME, mode)

    suspend fun setDefaultResolution(value: String) = edit(KEY_DEFAULT_RESOLUTION, value)

    suspend fun setDefaultFrameRate(value: String) = edit(KEY_DEFAULT_FRAME_RATE, value)

    suspend fun setDefaultQuality(value: String) = edit(KEY_DEFAULT_QUALITY, value)

    suspend fun setAutosaveEnabled(value: Boolean) = edit(KEY_AUTOSAVE, value)

    suspend fun setSaveToPhotos(value: Boolean) = edit(KEY_SAVE_TO_PHOTOS, value)

    private suspend fun edit(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    private suspend fun edit(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_DEFAULT_RESOLUTION = stringPreferencesKey("default_resolution")
        private val KEY_DEFAULT_FRAME_RATE = stringPreferencesKey("default_frame_rate")
        private val KEY_DEFAULT_QUALITY = stringPreferencesKey("default_quality")
        private val KEY_AUTOSAVE = booleanPreferencesKey("autosave_enabled")
        private val KEY_SAVE_TO_PHOTOS = booleanPreferencesKey("save_to_photos")
    }
}
