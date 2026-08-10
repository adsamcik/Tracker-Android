package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Minimal normalized input retained by the replayable location projection.
 *
 * Keeping one row per provider fix avoids rewriting an ever-growing session BLOB for every new
 * callback. The ordering index supports both the normal append path and deterministic late-fix
 * replay without duplicating the much wider canonical location-observation row.
 */
@Entity(
	tableName = "location_projection_observation",
	indices = [
		Index(
			value = ["logical_tracking_id", "elapsed_realtime_nanos", "wall_time_ms", "event_id"],
			name = "idx_location_projection_observation_order",
		),
		Index(value = ["wall_time_ms"], name = "idx_location_projection_observation_retention"),
		Index(value = ["admission_ordinal"], name = "idx_location_projection_observation_ordinal"),
	],
)
data class LocationProjectionObservationEntity(
	@PrimaryKey
	@ColumnInfo(name = "event_id")
	val eventId: String,
	@ColumnInfo(name = "logical_tracking_id")
	val logicalTrackingId: String,
	@ColumnInfo(name = "admission_ordinal")
	val admissionOrdinal: Long,
	@ColumnInfo(name = "elapsed_realtime_nanos")
	val elapsedRealtimeNanos: Long,
	@ColumnInfo(name = "wall_time_ms")
	val wallTimeMs: Long,
	@ColumnInfo(name = "latitude_degrees")
	val latitudeDegrees: Double,
	@ColumnInfo(name = "longitude_degrees")
	val longitudeDegrees: Double,
	@ColumnInfo(name = "horizontal_accuracy_meters")
	val horizontalAccuracyMeters: Float,
	@ColumnInfo(name = "altitude_meters")
	val altitudeMeters: Double?,
	@ColumnInfo(name = "vertical_accuracy_meters")
	val verticalAccuracyMeters: Float?,
	@ColumnInfo(name = "speed_meters_per_second")
	val speedMetersPerSecond: Float?,
)

/** Latest semantic revision for a normalized location-projection input. */
@Entity(
	tableName = "location_projection_point",
	foreignKeys = [
		ForeignKey(
			entity = LocationProjectionObservationEntity::class,
			parentColumns = ["event_id"],
			childColumns = ["event_id"],
			onDelete = ForeignKey.CASCADE,
			onUpdate = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["logical_tracking_id"], name = "idx_location_projection_point_tracking"),
	],
)
data class LocationProjectionPointEntity(
	@PrimaryKey
	@ColumnInfo(name = "event_id")
	val eventId: String,
	@ColumnInfo(name = "logical_tracking_id")
	val logicalTrackingId: String,
	val revision: Int,
	val accepted: Boolean,
	val rejection: String?,
	@ColumnInfo(name = "latitude_degrees")
	val latitudeDegrees: Double,
	@ColumnInfo(name = "longitude_degrees")
	val longitudeDegrees: Double,
	@ColumnInfo(name = "segment_distance_meters")
	val segmentDistanceMeters: Double,
	@ColumnInfo(name = "cumulative_distance_meters")
	val cumulativeDistanceMeters: Double,
	@ColumnInfo(name = "estimated_speed_meters_per_second")
	val estimatedSpeedMetersPerSecond: Double?,
	@ColumnInfo(name = "raw_wgs84_altitude_meters")
	val rawWgs84AltitudeMeters: Double?,
	@ColumnInfo(name = "vertical_accuracy_meters")
	val verticalAccuracyMeters: Float?,
	@ColumnInfo(name = "elapsed_realtime_nanos")
	val elapsedRealtimeNanos: Long,
)
