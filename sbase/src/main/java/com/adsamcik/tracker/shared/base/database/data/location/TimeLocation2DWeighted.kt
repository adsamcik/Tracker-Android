package com.adsamcik.tracker.shared.base.database.data.location

import androidx.room.ColumnInfo
import androidx.room.Ignore
import kotlin.math.max

/**
 * Utility class for getting 2D location from database
 */
data class TimeLocation2DWeighted(
		val time: Long,
		@ColumnInfo(name = "lat")
		val latitude: Double,
		@ColumnInfo(name = "lon")
		val longitude: Double,
		val weight: Double
) {

	@Ignore
	var normalizedWeight: Double = weight
		private set

	/**
	 * Calculates and saves normalized weight.
	 * Needs to be called manually because maxValue has to be supplied.
	 * Cached in normalizedWeight variable.
	 */
	fun normalize(maxValue: Double) {
		normalizedWeight = max(1 - (weight / maxValue), 0.0)
	}

}
