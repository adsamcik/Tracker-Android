package com.adsamcik.tracker.statistics.data

import com.adsamcik.tracker.common.database.data.DatabaseLocation

data class ElevationData(
		val raw: DatabaseLocation,
		val altitude: Double,
		val totalChange: Double,
		val changePerSecond: Double
)
