package com.adsamcik.tracker.game.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.adsamcik.tracker.feature.dashboard.api.navigation.Dashboard
import com.adsamcik.tracker.feature.game.api.navigation.Achievements
import com.adsamcik.tracker.feature.game.api.navigation.Game
import com.adsamcik.tracker.feature.game.api.navigation.MiniGameScores
import com.adsamcik.tracker.feature.game.api.navigation.MiniGameSession

fun NavGraphBuilder.gameGraph(
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
			onPlayMiniGame = { gameId ->
				navController.navigate(MiniGameSession(gameId)) { launchSingleTop = true }
			},
			onViewMiniGameScores = {
				navController.navigate(MiniGameScores) { launchSingleTop = true }
			},
		)
	}

	composable<Achievements> {
		com.adsamcik.tracker.game.ui.compose.AchievementDetailRoute(
			onBack = { navController.popBackStack() },
		)
	}

	composable<MiniGameSession> { entry ->
		// SavedStateHandle on the entry's ViewModel is auto-populated from
		// the typed nav arguments, so the MiniGameSessionViewModel can read
		// "gameId" without any glue here.
		@Suppress("UNUSED_VARIABLE")
		val args = entry.toRoute<MiniGameSession>()
		com.adsamcik.tracker.game.ui.compose.MiniGameSessionRoute(
			onClose = { navController.popBackStack() },
		)
	}

	composable<MiniGameScores> {
		com.adsamcik.tracker.game.ui.compose.MiniGameScoresRoute(
			onBack = { navController.popBackStack() },
		)
	}
}



