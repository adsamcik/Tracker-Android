package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.progression.UnlockableFeature
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel

/**
 * Entry point composable for the Game tab. Uses Hilt for dependency injection.
 */
@Composable
fun GameRoute(
	openChallengePickerRequest: Long = 0L,
	onNavigateToTrophyCase: () -> Unit = {},
	onOpenSettings: () -> Unit = {},
	onNavigateToTracker: () -> Unit = {},
) {
	val vm: GameViewModel = hiltViewModel()
	val explorationVm: ExplorationViewModel = hiltViewModel()
	val progressionVm: ProgressionViewModel = hiltViewModel()
	val points by vm.pointsToday.collectAsStateWithLifecycle()
	val steps by vm.stepsSummary.collectAsStateWithLifecycle()
	val challenges by vm.challenges.collectAsStateWithLifecycle()
	val miniGameEntries by vm.miniGameEntries.collectAsStateWithLifecycle()
	val exploration by explorationVm.explorationState.collectAsStateWithLifecycle()
	val achievements by explorationVm.achievementState.collectAsStateWithLifecycle()
	val heroLevelState by progressionVm.heroLevelState.collectAsStateWithLifecycle()
	val trophySummary by progressionVm.trophySummary.collectAsStateWithLifecycle()
	val leaderboardState by vm.leaderboardState.collectAsStateWithLifecycle()

	// Unlock event state
	var unlockEvent by remember { mutableStateOf<UnlockEvent?>(null) }
	var selectedChallenge by remember { mutableStateOf<ChallengeUi?>(null) }
	var showChallengePicker by rememberSaveable { mutableStateOf(false) }
	var handledChallengePickerRequest by rememberSaveable { mutableStateOf(0L) }
	LaunchedEffect(Unit) {
		progressionVm.unlockEvents.collect { event ->
			unlockEvent = event
		}
	}
	LaunchedEffect(openChallengePickerRequest) {
		if (openChallengePickerRequest != 0L && openChallengePickerRequest != handledChallengePickerRequest) {
			showChallengePicker = true
			handledChallengePickerRequest = openChallengePickerRequest
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
			heroLevelState = heroLevelState,
			trophySummary = trophySummary,
			leaderboardState = leaderboardState,
			isLoadingChallenges = challenges == null,
			onOpenSettings = onOpenSettings,
			onNavigateToTrophyCase = onNavigateToTrophyCase,
			onChallengeClick = { challenge -> selectedChallenge = challenge },
			onStartStreakClick = { showChallengePicker = true },
			onNavigateToTracker = onNavigateToTracker,
			onLeaderboardMetricSelected = { vm.selectLeaderboardMetric(it) },
		)

		selectedChallenge?.let { challenge ->
			ChallengeDetailsDialog(
				challenge = challenge,
				onDismiss = { selectedChallenge = null },
				onViewTrophyCase = {
					selectedChallenge = null
					onNavigateToTrophyCase()
				},
				modifier = Modifier.align(Alignment.Center),
			)
		}

		if (showChallengePicker) {
			ChallengePickerDialog(
				challenges = challenges.orEmpty(),
				onDismiss = { showChallengePicker = false },
				onChallengeSelected = { challenge ->
					showChallengePicker = false
					selectedChallenge = challenge
				},
				onOpenTrophyCase = {
					showChallengePicker = false
					onNavigateToTrophyCase()
				},
				modifier = Modifier.align(Alignment.Center),
			)
		}

		// Unlock announcement overlay
		unlockEvent?.let { event ->
			UnlockAnnouncementBanner(
				featureName = event.unlockedFeatures.firstOrNull()
					?.let { stringResource(unlockFeatureDisplayName(it)) },
				newLevel = event.newLevel,
				modifier = Modifier.align(Alignment.TopCenter),
				onDismissed = { unlockEvent = null },
			)
		}
	}
}

private fun unlockFeatureDisplayName(feature: UnlockableFeature): Int = when (feature) {
	UnlockableFeature.BASE_CHALLENGES -> R.string.unlock_feature_challenges
	UnlockableFeature.MEDALS -> R.string.unlock_feature_medals
	UnlockableFeature.STREAKS -> R.string.unlock_feature_streaks
	UnlockableFeature.TROPHY_CASE -> R.string.unlock_feature_trophy_case
	UnlockableFeature.PERSONAL_RECORDS -> R.string.unlock_feature_personal_records
	UnlockableFeature.OUTRUN_MINI_GAME -> R.string.unlock_feature_outrun
	UnlockableFeature.STREAK_FREEZE -> R.string.unlock_feature_streak_freeze
	UnlockableFeature.SPEED_CHALLENGE -> R.string.unlock_feature_speed_challenge
	UnlockableFeature.TERRITORY_MINI_GAME -> R.string.unlock_feature_territory
	UnlockableFeature.LIFETIME_STATS -> R.string.unlock_feature_lifetime_stats
	UnlockableFeature.CONSISTENCY_CHALLENGE -> R.string.unlock_feature_consistency
	UnlockableFeature.ZEN_WALK_MINI_GAME -> R.string.unlock_feature_zen_walk
	UnlockableFeature.WEEKLY_RANK -> R.string.unlock_feature_weekly_rank
}
