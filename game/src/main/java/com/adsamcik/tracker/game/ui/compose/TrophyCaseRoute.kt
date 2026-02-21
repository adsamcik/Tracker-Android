package com.adsamcik.tracker.game.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Entry point composable for the Trophy Case screen. Uses Hilt for dependency injection.
 */
@Composable
fun TrophyCaseRoute(
	onBack: () -> Unit = {},
) {
	val vm: TrophyCaseViewModel = hiltViewModel()
	val trophies by vm.trophies.collectAsState()
	val personalRecords by vm.personalRecords.collectAsState()
	val lifetimeStats by vm.lifetimeStats.collectAsState()
	val filter by vm.filter.collectAsState()
	TrophyCaseScreen(
		trophies = trophies,
		personalRecords = personalRecords,
		lifetimeStats = lifetimeStats,
		currentFilter = filter,
		onFilterChanged = vm::setFilter,
		onBack = onBack,
	)
}
