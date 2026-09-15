package com.adsamcik.tracker.points.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * Interface containing amount of points awarded
 */
@Entity(
		tableName = "points_awarded",
		indices = [
			Index(
					value = ["source", "effect_key"],
					unique = true,
			),
		],
)
data class PointsAwarded(
		@PrimaryKey(autoGenerate = true)
		val id: Int,
		val time: Long,
		val value: Points,
		val source: AwardSource,
		@ColumnInfo(name = "effect_key")
		val effectKey: String? = null,
		@ColumnInfo(name = "effect_revision")
		val effectRevision: Long? = null,
		@ColumnInfo(name = "effect_value_micros")
		val effectValueMicros: Long? = null,
) {
	@Ignore
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
		val GOAL: AwardSource = AwardSource("goal")
		val MINIGAME: AwardSource = AwardSource("minigame")
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
