package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.progression.UnlockableFeature
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
	val miniGameEntries by vm.miniGameEntries.collectAsState()
	val exploration by explorationVm.explorationState.collectAsState()
	val achievements by explorationVm.achievementState.collectAsState()
	val profile by progressionVm.playerProfile.collectAsState()
	val streak by progressionVm.streak.collectAsState()
	val trophySummary by progressionVm.trophySummary.collectAsState()

	// Unlock event state
	var unlockEvent by remember { mutableStateOf<UnlockEvent?>(null) }
	LaunchedEffect(Unit) {
		progressionVm.unlockEvents.collect { event ->
			unlockEvent = event
		}
	}

	Box {
		GameScreen(
			pointsToday = points,
			steps = steps,
			challenges = challenges,
			miniGameEntries = miniGameEntries,
			explorationState = exploration,
			achievementState = achievements,
			playerProfile = profile,
			streak = streak,
			trophySummary = trophySummary,
			onNavigateToTrophyCase = onNavigateToTrophyCase,
		)

		// Unlock announcement overlay
		unlockEvent?.let { event ->
			UnlockAnnouncementBanner(
				featureName = event.unlockedFeatures.firstOrNull()
					?.let { unlockFeatureDisplayName(it) },
				newLevel = event.newLevel,
				modifier = Modifier.align(Alignment.TopCenter),
				onDismissed = { unlockEvent = null },
			)
		}
	}
}

private fun unlockFeatureDisplayName(feature: UnlockableFeature): String = when (feature) {
	UnlockableFeature.BASE_CHALLENGES -> "Challenges"
	UnlockableFeature.MEDALS -> "Medals"
	UnlockableFeature.STREAKS -> "Streaks"
	UnlockableFeature.TROPHY_CASE -> "Trophy Case"
	UnlockableFeature.PERSONAL_RECORDS -> "Personal Records"
	UnlockableFeature.OUTRUN_MINI_GAME -> "Outrun Mini-Game"
	UnlockableFeature.STREAK_FREEZE -> "Streak Freeze"
	UnlockableFeature.SPEED_CHALLENGE -> "Speed Challenge"
	UnlockableFeature.TERRITORY_MINI_GAME -> "Territory Mini-Game"
	UnlockableFeature.LIFETIME_STATS -> "Lifetime Stats"
	UnlockableFeature.CONSISTENCY_CHALLENGE -> "Consistency Challenge"
	UnlockableFeature.ZEN_WALK_MINI_GAME -> "Zen Walk Mini-Game"
	UnlockableFeature.WEEKLY_RANK -> "Weekly Rank"
}
