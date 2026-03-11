package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached compressed route polyline for a tracking session or segment.
 *
 * Stores a Google Encoded Polyline string representing a simplified route,
 * allowing fast route rendering without re-querying individual location samples.
 * Created by RouteCompressor after a session/segment ends.
 */
@Entity(
	tableName = "route_cache",
	indices = [
		Index(value = ["session_id"]),
		Index(value = ["segment_id"]),
		Index(value = ["start_time"])
	]
)
data class RouteCacheEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/** Legacy session ID (nullable for sessionless segments). */
	@ColumnInfo(name = "session_id")
	val sessionId: Long?,

	/** Session segment ID (nullable for legacy sessions). */
	@ColumnInfo(name = "segment_id")
	val segmentId: Long?,

	/** Google Encoded Polyline string of the simplified route. */
	@ColumnInfo(name = "encoded_polyline")
	val encodedPolyline: String,

	/** Number of raw location points before simplification. */
	@ColumnInfo(name = "point_count")
	val pointCount: Int,

	/** Number of points after Douglas-Peucker simplification. */
	@ColumnInfo(name = "simplified_count")
	val simplifiedCount: Int,

	/** Timestamp of the first location point (epoch millis). */
	@ColumnInfo(name = "start_time")
	val startTime: Long,

	/** Timestamp of the last location point (epoch millis). */
	@ColumnInfo(name = "end_time")
	val endTime: Long,

	/** Total simplified path distance in meters (Haversine). */
	@ColumnInfo(name = "distance_meters")
	val distanceMeters: Double,

	@ColumnInfo(name = "created_at")
	val createdAt: Long,
)
