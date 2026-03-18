package com.adsamcik.tracker.game.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Entry point composable for the Trophy Case screen. Uses Hilt for dependency injection.
 */
@Composable
fun TrophyCaseRoute(
	onBack: () -> Unit = {},
) {
	val vm: TrophyCaseViewModel = hiltViewModel()
	val trophies by vm.trophies.collectAsStateWithLifecycle()
	val personalRecords by vm.personalRecords.collectAsStateWithLifecycle()
	val lifetimeStats by vm.lifetimeStats.collectAsStateWithLifecycle()
	val filter by vm.filter.collectAsStateWithLifecycle()
	val activeChallenges by vm.activeChallenges.collectAsStateWithLifecycle()
	var selectedChallenge by remember { mutableStateOf<ChallengeUi?>(null) }
	TrophyCaseScreen(
		activeChallenges = activeChallenges,
		trophies = trophies,
		personalRecords = personalRecords,
		lifetimeStats = lifetimeStats,
		currentFilter = filter,
		onFilterChanged = vm::setFilter,
		onChallengeClick = { selectedChallenge = it },
		onBack = onBack,
	)
	selectedChallenge?.let { challenge ->
		ChallengeDetailsDialog(
			challenge = challenge,
			onDismiss = { selectedChallenge = null },
			onViewTrophyCase = { selectedChallenge = null },
		)
	}
}
