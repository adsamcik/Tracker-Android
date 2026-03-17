package com.adsamcik.tracker.statistics.viewmodel

import com.adsamcik.tracker.stats.api.repository.WifiObservationStatsSummary

sealed interface WifiStatsLoadState {
	data object Idle : WifiStatsLoadState
	data object Loading : WifiStatsLoadState
	data object Empty : WifiStatsLoadState
	data class Success(val summary: WifiObservationStatsSummary) : WifiStatsLoadState
	data class Error(val message: String) : WifiStatsLoadState
}
