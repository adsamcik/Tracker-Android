package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single leg of a multi-leg trip. Each leg has a uniform transport mode.
 */
@Entity(
	tableName = "trip_leg",
	foreignKeys = [
		ForeignKey(
			entity = InferredTripEntity::class,
			parentColumns = ["id"],
			childColumns = ["trip_id"],
			onDelete = ForeignKey.CASCADE
		)
	],
	indices = [
		Index(
			value = ["trip_id", "sequence_index"],
			name = "idx_trip_leg_trip_seq",
			unique = true
		)
	]
)
data class TripLegEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	@ColumnInfo(name = "trip_id")
	val tripId: Long,

	@ColumnInfo(name = "sequence_index")
	val sequenceIndex: Int,

	@ColumnInfo(name = "start_time_ms")
	val startTimeMs: Long,

	@ColumnInfo(name = "end_time_ms")
	val endTimeMs: Long,

	@ColumnInfo(name = "distance_m")
	val distanceM: Float,

	@ColumnInfo(name = "transport_mode")
	val transportMode: String,

	@ColumnInfo(name = "created_at")
	val createdAt: Long
)
