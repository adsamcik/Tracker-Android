package com.adsamcik.tracker.game.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel

/**
 * Entry point composable for the Game tab. Uses Hilt for dependency injection.
 */
@Composable
fun GameRoute(
	onNavigateToTrophyCase: () -> Unit = {},
) {
	val vm: GameViewModel = hiltViewModel()
	val explorationVm: ExplorationViewModel = hiltViewModel()
	val progressionVm: ProgressionViewModel = hiltViewModel()
	val points by vm.pointsToday.collectAsState()
	val steps by vm.stepsSummary.collectAsState()
	val challenges by vm.challenges.collectAsState()
	val exploration by explorationVm.explorationState.collectAsState()
	val achievements by explorationVm.achievementState.collectAsState()
	val profile by progressionVm.playerProfile.collectAsState()
	val streak by progressionVm.streak.collectAsState()
	val trophySummary by progressionVm.trophySummary.collectAsState()
	GameScreen(
		pointsToday = points,
		steps = steps,
		challenges = challenges,
		explorationState = exploration,
		achievementState = achievements,
		playerProfile = profile,
		streak = streak,
		trophySummary = trophySummary,
		onNavigateToTrophyCase = onNavigateToTrophyCase,
	)
}
