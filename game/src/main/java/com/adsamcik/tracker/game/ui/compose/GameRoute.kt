package com.adsamcik.tracker.game.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel

@Composable
fun GameRoute(
	onNavigateToAchievements: () -> Unit = {},
	onOpenSettings: () -> Unit = {},
	onNavigateToTracker: () -> Unit = {},
	onPlayMiniGame: (gameId: String) -> Unit = {},
	onViewMiniGameScores: () -> Unit = {},
) {
	val vm: GameViewModel = hiltViewModel()
	val explorationVm: ExplorationViewModel = hiltViewModel()
	val points by vm.pointsToday.collectAsStateWithLifecycle()
	val steps by vm.stepsSummary.collectAsStateWithLifecycle()
	val miniGameEntries by vm.miniGameEntries.collectAsStateWithLifecycle()
	val exploration by explorationVm.explorationState.collectAsStateWithLifecycle()
	val achievements by explorationVm.achievementState.collectAsStateWithLifecycle()

	GameScreen(
		pointsToday = points,
		steps = steps,
		miniGameEntries = miniGameEntries,
		explorationState = exploration,
		achievementState = achievements,
		onOpenSettings = onOpenSettings,
		onNavigateToTracker = onNavigateToTracker,
		onViewAllAchievements = onNavigateToAchievements,
		onPlayMiniGame = onPlayMiniGame,
		onViewMiniGameScores = onViewMiniGameScores,
	)
}
