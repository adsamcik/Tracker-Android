package com.adsamcik.tracker.statistics.data

import com.adsamcik.tracker.shared.model.LocationSample

data class ElevationData(
		val raw: LocationSample,
		val altitude: Double,
		val totalChange: Double,
		val changePerSecond: Double
)
