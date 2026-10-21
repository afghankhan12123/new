package com.videoeditor.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.videoeditor.core.designsystem.gradient.VEGradient

/** Top-level destinations that show the bottom navigation bar. */
private val topLevelRoutes = setOf(Routes.HOME, Routes.PROJECTS, Routes.SETTINGS)

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in topLevelRoutes

    val editor = currentRoute == Routes.EDITOR || currentRoute == Routes.EXPORT
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val homeDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    androidx.compose.runtime.SideEffect {
        val activity = context as? android.app.Activity
        activity?.let {
            androidx.core.view.WindowCompat.getInsetsController(it.window, view).apply {
                isAppearanceLightStatusBars = !editor && !homeDark
                isAppearanceLightNavigationBars = !editor && !homeDark
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) it.window.isNavigationBarContrastEnforced = false
        }
    }
    Scaffold(
        containerColor = if (editor) com.videoeditor.core.designsystem.color.VEColor.DarkBackground else MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onHome = { navController.navigateTopLevel(Routes.HOME) },
                    onProjects = { navController.navigateTopLevel(Routes.PROJECTS) },
                    onCreate = { navController.navigate(Routes.MEDIA_IMPORT) },
                    onSettings = { navController.navigateTopLevel(Routes.SETTINGS) },
                )
            }
        },
    ) { innerPadding ->
        VideoEditorNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
            onOpenProject = { projectId -> navController.navigate(Routes.editor(projectId)) },
            onStartNewProject = { navController.navigate(Routes.MEDIA_IMPORT) },
        )
    }
}

private fun androidx.navigation.NavController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BottomNavBar(
    currentRoute: String?,
    onHome: () -> Unit,
    onProjects: () -> Unit,
    onCreate: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem("Home", Icons.Default.Home, selected = currentRoute == Routes.HOME, onClick = onHome)
        NavItem("Projects", Icons.Outlined.Folder, selected = currentRoute == Routes.PROJECTS, onClick = onProjects)
        // Center Create button with gradient circle.
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(VEGradient.createButton)
                    .clickable(onClick = onCreate),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Create",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            Text("Create", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NavItem("Settings", Icons.Outlined.Settings, selected = currentRoute == Routes.SETTINGS, onClick = onSettings)
    }
}

@Composable
private fun RowScope.NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) {
        com.videoeditor.core.designsystem.color.VEColor.PrimaryBlue
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
