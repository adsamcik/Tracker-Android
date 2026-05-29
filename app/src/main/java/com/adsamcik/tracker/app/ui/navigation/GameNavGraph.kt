package com.adsamcik.tracker.app.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

internal fun NavGraphBuilder.gameGraph(
	navController: NavHostController,
	onOpenSettings: () -> Unit,
) {
	composable<Game> {
		com.adsamcik.tracker.game.ui.compose.GameRoute(
			onOpenSettings = onOpenSettings,
			onNavigateToAchievements = {
				navController.navigate(Achievements) { launchSingleTop = true }
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

	composable<Achievements> {
		com.adsamcik.tracker.game.ui.compose.AchievementDetailRoute(
			onBack = { navController.popBackStack() },
		)
	}
}
