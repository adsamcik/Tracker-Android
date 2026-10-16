package com.adsamcik.tracker.dashboard.ui.compose

import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.stats.api.repository.HistorySource

/** The same saved-source name in live status, missing-data cards and recent history. */
internal val HistorySource.dashboardNameResource: Int
	get() = when (this) {
		HistorySource.LOCATION -> R.string.dashboard_history_source_location
		HistorySource.WIFI -> R.string.dashboard_history_source_wifi
		HistorySource.CELL -> R.string.dashboard_history_source_cell
		HistorySource.ACTIVITY -> R.string.dashboard_history_source_activity
		HistorySource.STEPS -> R.string.dashboard_history_source_steps
		HistorySource.PRESSURE -> R.string.dashboard_history_source_pressure
	}
