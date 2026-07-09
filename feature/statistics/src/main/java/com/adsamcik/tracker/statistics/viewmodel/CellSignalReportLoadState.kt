package com.adsamcik.tracker.statistics.viewmodel

import com.adsamcik.tracker.stats.api.repository.CellSignalReport

sealed interface CellSignalReportLoadState {
	data object Idle : CellSignalReportLoadState
	data object Loading : CellSignalReportLoadState
	data object Empty : CellSignalReportLoadState
	data class Success(val report: CellSignalReport) : CellSignalReportLoadState
	data class Error(val message: String) : CellSignalReportLoadState
}
