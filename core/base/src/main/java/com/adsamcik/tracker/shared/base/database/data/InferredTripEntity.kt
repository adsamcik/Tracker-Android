package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An enriched trip derived from a SessionSegment with place matching
 * and leg breakdown.
 */
@Entity(
	tableName = "inferred_trip",
	foreignKeys = [
		ForeignKey(
			entity = FrequentPlaceEntity::class,
			parentColumns = ["id"],
			childColumns = ["departure_place_id"],
			onDelete = ForeignKey.SET_NULL
		),
		ForeignKey(
			entity = FrequentPlaceEntity::class,
			parentColumns = ["id"],
			childColumns = ["arrival_place_id"],
			onDelete = ForeignKey.SET_NULL
		)
	],
	indices = [
		Index(
			value = ["start_time_ms", "end_time_ms"],
			name = "idx_inferred_trip_time_range"
		),
		Index(value = ["departure_place_id"], name = "idx_inferred_trip_departure"),
		Index(value = ["arrival_place_id"], name = "idx_inferred_trip_arrival"),
		Index(value = ["segment_id"])
	]
)
data class InferredTripEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	@ColumnInfo(name = "segment_id")
	val segmentId: Long,

	@ColumnInfo(name = "start_time_ms")
	val startTimeMs: Long,

	@ColumnInfo(name = "end_time_ms")
	val endTimeMs: Long,

	@ColumnInfo(name = "distance_m")
	val distanceM: Float,

	@ColumnInfo(name = "steps")
	val steps: Int?,

	@ColumnInfo(name = "primary_activity")
	val primaryActivity: Int?,

	@ColumnInfo(name = "transport_mode")
	val transportMode: String,

	@ColumnInfo(name = "departure_place_id")
	val departurePlaceId: Long?,

	@ColumnInfo(name = "arrival_place_id")
	val arrivalPlaceId: Long?,

	@ColumnInfo(name = "source")
	val source: String,

	@ColumnInfo(name = "inference_version")
	val inferenceVersion: String?,

	@ColumnInfo(name = "leg_count")
	val legCount: Int,

	@ColumnInfo(name = "created_at")
	val createdAt: Long
)
