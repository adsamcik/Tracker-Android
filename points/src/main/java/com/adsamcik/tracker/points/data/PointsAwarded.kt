package com.adsamcik.tracker.points.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.ZonedDateTime

/**
 * Interface containing amount of points awarded
 */
@Entity(tableName = "points_awarded")
data class PointsAwarded(
		@PrimaryKey
		val id: Int,
		val time: ZonedDateTime,
		val value: Points,
		val source: AwardSource
) {
	constructor(
			time: ZonedDateTime,
			value: Points,
			source: AwardSource
	) : this(0, time, value, source)
}

/**
 * Source of awarded points
 */
enum class AwardSource {
	Unknown,
	Session,
	Challenge
}
