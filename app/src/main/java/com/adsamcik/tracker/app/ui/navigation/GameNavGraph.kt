package com.adsamcik.tracker.app.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/**
 * Game-related destinations: Game screen and Trophy case.
 */
internal fun NavGraphBuilder.gameGraph(
    navController: NavHostController,
    openChallengePickerRequest: Long,
    onOpenSettings: () -> Unit,
) {
    composable<Game> {
        com.adsamcik.tracker.game.ui.compose.GameRoute(
            openChallengePickerRequest = openChallengePickerRequest,
            onOpenSettings = onOpenSettings,
            onNavigateToTrophyCase = {
                navController.navigate(TrophyCase) {
                    launchSingleTop = true
                }
            },
            onNavigateToTracker = {
                navController.navigate(Dashboard) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        )
    }

    composable<TrophyCase> {
        com.adsamcik.tracker.game.ui.compose.TrophyCaseRoute(
            onBack = { navController.popBackStack() },
        )
    }
}
