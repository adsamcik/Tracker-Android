package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw location sample captured during tracking.
 * Stores coordinates in E7 format (integer microdegrees) for space efficiency.
 * Supports nullable coordinates for coarse/enriched samples.
 */
@Entity(
	tableName = "location_sample",
	indices = [
		Index(value = ["time_ms"], name = "idx_location_sample_time"),
		Index(value = ["lat_e7", "lon_e7"], name = "idx_location_sample_coords"),
		Index(value = ["bucket_id"], name = "idx_location_sample_bucket")
	]
)
data class LocationSample(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/**
	 * Timestamp in milliseconds since Unix epoch (wall clock time).
	 */
	@ColumnInfo(name = "time_ms")
	val timeMs: Long,

	/**
	 * Elapsed real time in nanoseconds since boot (for correlating with sensors).
	 */
	@ColumnInfo(name = "elapsed_realtime_nanos")
	val elapsedRealtimeNanos: Long,

	/**
	 * Latitude in E7 format (degrees * 1e7). Null if location unknown at capture time.
	 */
	@ColumnInfo(name = "lat_e7")
	val latE7: Int?,

	/**
	 * Longitude in E7 format (degrees * 1e7). Null if location unknown at capture time.
	 */
	@ColumnInfo(name = "lon_e7")
	val lonE7: Int?,

	/**
	 * Altitude in meters above Mean Sea Level (MSL).
	 * Corrected via geoid model when available, otherwise raw ellipsoid altitude.
	 * Null if unavailable or failed vertical accuracy gating.
	 */
	@ColumnInfo(name = "alt_m")
	val altitudeM: Float?,

	/**
	 * Horizontal accuracy radius in meters (68% confidence). Null if unavailable.
	 */
	@ColumnInfo(name = "h_acc_m")
	val hAccM: Float?,

	/**
	 * Vertical accuracy in meters (68% confidence). Null if unavailable.
	 */
	@ColumnInfo(name = "v_acc_m")
	val vAccM: Float?,

	/**
	 * Speed over ground in meters per second. Null if unavailable.
	 */
	@ColumnInfo(name = "speed_mps")
	val speedMps: Float?,

	/**
	 * Speed accuracy in meters per second (68% confidence). Null if unavailable.
	 */
	@ColumnInfo(name = "speed_accuracy_mps")
	val speedAccuracyMps: Float?,

	/**
	 * Location provider name (e.g., "fused", "gps", "network").
	 */
	val provider: String,

	/**
	 * Sample quality classification (HIGH, MEDIUM, LOW, COARSE).
	 */
	val quality: SampleQuality,

	/**
	 * Motion state at capture time (MOVING, STILL, UNKNOWN). Null if not determined.
	 */
	@ColumnInfo(name = "motion_state")
	val motionState: MotionState?,

	/**
	 * Tracking policy active when this sample was captured (e.g., "PASSIVE_LOW", "ACTIVE_ELEVATED").
	 * Null if captured outside policy framework.
	 */
	val policy: String?,

	/**
	 * Optional bucket ID for spatial/temporal aggregation. Used by aging/compression.
	 */
	@ColumnInfo(name = "bucket_id")
	val bucketId: Long?,

	/**
	 * Row creation timestamp (for auditing/debugging).
	 */
	@ColumnInfo(name = "created_at")
	val createdAt: Long
)

/**
 * Sample quality classification based on accuracy and provider.
 */
enum class SampleQuality {
	/** GPS with good accuracy (<10m) */
	HIGH,
	/** GPS with moderate accuracy (10-50m) or fused with good signals */
	MEDIUM,
	/** GPS with poor accuracy (>50m) or fused with weak signals */
	LOW,
	/** Coarse location (network/cell only, typically >100m accuracy) */
	COARSE
}

/**
 * Motion state classification (from activity recognition or motion sensors).
 */
enum class MotionState {
	/** Device is moving (walking, running, in vehicle, etc.) */
	MOVING,
	/** Device is stationary */
	STILL,
	/** Motion state could not be determined */
	UNKNOWN
}
