package com.example.assistant.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.assistant.R
import com.example.assistant.ui.screens.home.HomeRoute
import com.example.assistant.ui.screens.notes.NoteEditRoute
import com.example.assistant.ui.screens.notes.NotesRoute
import com.example.assistant.ui.screens.settings.SettingsRoute
import com.example.assistant.ui.screens.task_edit.TaskEditRoute

/** Navigation routes. */
object Routes {
    const val TASKS = "tasks"
    const val NOTES = "notes"
    const val SETTINGS = "settings"

    const val ARG_TASK_ID = "taskId"
    const val TASK_EDIT = "task_edit?$ARG_TASK_ID={$ARG_TASK_ID}"
    fun taskEdit(id: Long? = null) = "task_edit?$ARG_TASK_ID=${id ?: -1L}"

    const val ARG_NOTE_ID = "noteId"
    const val NOTE_EDIT = "note_edit?$ARG_NOTE_ID={$ARG_NOTE_ID}"
    fun noteEdit(id: Long? = null) = "note_edit?$ARG_NOTE_ID=${id ?: -1L}"
}

private data class TopLevelDestination(val route: String, @StringRes val label: Int, val icon: ImageVector)

/**
 * Bottom-bar destinations. Stats and Chat are added in later stages
 * together with their screens.
 */
private val topLevelDestinations = listOf(
    TopLevelDestination(Routes.TASKS, R.string.nav_tasks, Icons.Outlined.CheckCircle),
    TopLevelDestination(Routes.NOTES, R.string.nav_notes, Icons.Outlined.StickyNote2),
    TopLevelDestination(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
)

/** Root navigation graph with the bottom navigation bar. */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = topLevelDestinations.any { it.route == currentDestination?.route }

    Scaffold(
        // Each screen draws its own top bar and handles insets itself.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevelDestinations.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(stringResource(dest.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TASKS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.TASKS) {
                HomeRoute(
                    onAddTask = { navController.navigate(Routes.taskEdit()) },
                    onEditTask = { id -> navController.navigate(Routes.taskEdit(id)) },
                    onOpenSettings = {
                        navController.navigate(Routes.SETTINGS) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(
                Routes.TASK_EDIT,
                arguments = listOf(navArgument(Routes.ARG_TASK_ID) { type = NavType.LongType; defaultValue = -1L }),
            ) {
                TaskEditRoute(onBack = { navController.popBackStack() })
            }
            composable(Routes.NOTES) {
                NotesRoute(
                    onAddNote = { navController.navigate(Routes.noteEdit()) },
                    onOpenNote = { id -> navController.navigate(Routes.noteEdit(id)) },
                )
            }
            composable(
                Routes.NOTE_EDIT,
                arguments = listOf(navArgument(Routes.ARG_NOTE_ID) { type = NavType.LongType; defaultValue = -1L }),
            ) {
                NoteEditRoute(onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsRoute()
            }
        }
    }
}
