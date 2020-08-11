package com.adsamcik.tracker.statistics.data

import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType

/**
 * Object containing all necessary information for construction of UI objects for statistic.
 */
data class Stat(
		val nameRes: Int,
		val iconRes: Int,
		val displayType: StatisticDisplayType,
		val data: Any
)
