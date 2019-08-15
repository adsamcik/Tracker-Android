package com.adsamcik.signalcollector.common.database.data

import androidx.room.ColumnInfo
import androidx.room.Ignore
import kotlin.math.max


data class Database2DLocationWeightedMinimal(
		@ColumnInfo(name = "lat")
		val latitude: Double,
		@ColumnInfo(name = "lon")
		val longitude: Double,
		val weight: Double
) {

	@Ignore
	var normalizedWeight: Double = weight
		private set

	fun normalize(maxValue: Double) {
		normalizedWeight = max(1 - (weight / maxValue), 0.0)
	}

}
