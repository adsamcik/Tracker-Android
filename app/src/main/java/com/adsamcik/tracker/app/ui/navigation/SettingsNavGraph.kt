package com.adsamcik.tracker.app.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute

/**
 * Settings-related destinations: Settings, Debug, and Activity settings.
 */
internal fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    composable<Settings> { backStackEntry ->
        val settingsRoute = backStackEntry.toRoute<Settings>()
        com.adsamcik.tracker.app.settings.SettingsRoute(
            onNavigateBack = {
                if (!navController.popBackStack()) {
                    navController.navigate(settingsRoute.origin.toAppRoute()) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            },
            onNavigateToDebug = { navController.navigate(Debug) },
            onNavigateToActivities = {
                navController.navigate(ActivitySettings) { launchSingleTop = true }
            },
        )
    }

    composable<Debug> {
        com.adsamcik.tracker.app.debug.DebugRoute(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<ActivitySettings> {
        com.adsamcik.tracker.activity.ui.SessionActivityRoute(
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
