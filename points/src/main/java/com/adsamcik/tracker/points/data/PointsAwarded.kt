package com.adsamcik.tracker.points.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * Interface containing amount of points awarded
 */
@Entity(tableName = "points_awarded")
data class PointsAwarded(
		@PrimaryKey(autoGenerate = true)
		val id: Int,
		val time: Long,
		val value: Points,
		val source: AwardSource
) {
	constructor(
			time: Long,
			value: Points,
			source: AwardSource
	) : this(0, time, value, source)
}

/**
 * Source of awarded points
 */
data class AwardSource(val value: String) {
	companion object {
		val SESSION: AwardSource = AwardSource("session")
		val CHALLENGE: AwardSource = AwardSource("challenge")
	}
}

/**
 * Contains needed conversion for points data
 */
class PointsDataConverters {
	/**
	 * Converts from [AwardSource] to [String]
	 */
	@TypeConverter
	fun fromAwardSource(value: AwardSource): String = value.value

	/**
	 * Converts from [String] to [AwardSource]
	 */
	@TypeConverter
	fun toAwardSource(value: String): AwardSource = AwardSource(value)

	/**
	 * Converts from [Points] to [Double]
	 */
	@TypeConverter
	fun fromPoints(value: Points): Double = value.value

	/**
	 * Converts from [Double] to [Points]
	 */
	@TypeConverter
	fun toPoints(value: Double): Points = Points(value)
}
