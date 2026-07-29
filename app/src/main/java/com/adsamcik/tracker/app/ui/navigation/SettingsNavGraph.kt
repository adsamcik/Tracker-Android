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
            initialScreen = when (settingsRoute.section) {
                SettingsSection.ROOT -> com.adsamcik.tracker.app.settings.SettingsScreen.Root
                SettingsSection.DATA -> com.adsamcik.tracker.app.settings.SettingsScreen.Data
            },
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
            onNavigateToAbout = {
                navController.navigate(About) { launchSingleTop = true }
            },
            onNavigateToNotificationManagement = {
                navController.navigate(NotificationManagement) { launchSingleTop = true }
            },
        )
    }

    composable<About> {
        com.adsamcik.tracker.app.settings.about.AboutScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<Debug> {
        com.adsamcik.tracker.app.debug.DebugRoute(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<ActivitySettings> {
        val viewModel: com.adsamcik.tracker.activity.ui.SessionActivityViewModel =
            androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
        com.adsamcik.tracker.activity.ui.SessionActivityRoute(
            viewModel = viewModel,
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable<NotificationManagement> {
        com.adsamcik.tracker.feature.tracker.notification.NotificationManagementRoute(
            onBack = { navController.popBackStack() },
        )
    }
}
