package com.videoeditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.videoeditor.app.navigation.MainScreen
import com.videoeditor.core.datastore.UserPreferencesRepository
import com.videoeditor.core.designsystem.theme.ThemeMode
import com.videoeditor.core.designsystem.theme.VideoEditorTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesRepository: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs by preferencesRepository.preferences.collectAsState(
                initial = com.videoeditor.core.datastore.UserPreferences()
            )
            val themeMode = ThemeMode.valueOf(prefs.themeMode)

            VideoEditorTheme(themeMode = themeMode) {
                MainScreen()
            }
        }
    }
}
