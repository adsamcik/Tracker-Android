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
	@Suppress("UNUSED_PARAMETER") onNavigateToTracker: () -> Unit = {},
	onPlayMiniGame: (gameId: String) -> Unit = {},
	onViewMiniGameScores: () -> Unit = {},
) {
	val vm: GameViewModel = hiltViewModel()
	val explorationVm: ExplorationViewModel = hiltViewModel()
	val hub by vm.hubState.collectAsStateWithLifecycle()
	val exploration by explorationVm.explorationState.collectAsStateWithLifecycle()
	val achievements by explorationVm.achievementState.collectAsStateWithLifecycle()

	GameScreen(
		hub = hub,
		explorationState = exploration,
		achievementState = achievements,
		onOpenSettings = onOpenSettings,
		onViewAllAchievements = onNavigateToAchievements,
		onPlayMiniGame = onPlayMiniGame,
		onViewMiniGameScores = onViewMiniGameScores,
		onSelectLeaderboardMetric = vm::selectLeaderboardMetric,
		onRetryLeaderboard = vm::retryLeaderboard,
	)
}
