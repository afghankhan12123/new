package com.videoeditor.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.videoeditor.feature.editor.EditorScreen
import com.videoeditor.feature.export.ExportScreen
import com.videoeditor.feature.home.HomeScreen
import com.videoeditor.feature.mediaimport.MediaImportScreen
import com.videoeditor.feature.projects.ProjectsScreen
import com.videoeditor.feature.settings.SettingsScreen

@Composable
fun VideoEditorNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onOpenProject: (String) -> Unit,
    onStartNewProject: () -> Unit,
) {
    var pendingTool by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenProject = onOpenProject,
                onStartNewProject = { pendingTool = null; onStartNewProject() },
                onNavigateToProjects = { navController.navigate(Routes.PROJECTS) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onShortcut = { tool -> pendingTool = tool; navController.navigate(Routes.MEDIA_IMPORT) },
            )
        }
        composable(Routes.PROJECTS) {
            ProjectsScreen(
                onOpenProject = onOpenProject,
                onBack = { navController.popBackStack() },
                onNewProject = { pendingTool = null; navController.navigate(Routes.MEDIA_IMPORT) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.MEDIA_IMPORT) {
            MediaImportScreen(
                captureOnStart = pendingTool == "Camera",
                onMediaSelected = { projectId ->
                    navController.navigate(Routes.editor(projectId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onBack = { pendingTool = null; navController.popBackStack() },
            )
        }
        composable(Routes.EDITOR) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            EditorScreen(
                projectId = projectId,
                initialTool = pendingTool,
                onInitialToolHandled = { pendingTool = null },
                onBack = { navController.popBackStack() },
                onExport = { navController.navigate(Routes.export(projectId)) },
            )
        }
        composable(Routes.EXPORT) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            ExportScreen(
                projectId = projectId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
