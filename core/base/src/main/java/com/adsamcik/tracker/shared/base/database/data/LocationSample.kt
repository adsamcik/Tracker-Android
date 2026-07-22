package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource

/**
 * Raw location sample captured during tracking.
 * Stores coordinates in E7 format (integer microdegrees) for space efficiency.
 * Supports nullable coordinates for coarse/enriched samples.
 */
@Entity(
	tableName = "location_sample",
	indices = [
		Index(value = ["time_ms"], name = "idx_location_sample_time"),
		Index(value = ["time_ms", "id"], name = "idx_location_sample_time_id"),
		Index(value = ["lat_e7", "lon_e7"], name = "idx_location_sample_coords"),
		Index(value = ["bucket_id"], name = "idx_location_sample_bucket"),
		Index(
			value = ["source_signal_id"],
			unique = true,
			name = "idx_location_sample_source_signal",
		),
		Index(value = ["source_event_id"], name = "idx_location_sample_source_event"),
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
	 * Datum-aware processed altitude. The associated [altitudeDatum] determines whether this is
	 * Android-model MSL, a relative barometric continuation, or historical/unknown evidence.
	 * This field is never populated with a raw WGS-84 ellipsoid altitude as an MSL fallback.
	 */
	@ColumnInfo(name = "alt_m")
	val altitudeM: Float?,

	/**
	 * Raw GPS altitude in meters above the WGS-84 ellipsoid, before any fusion
	 * or correction. Preserved for diagnostics and re-processing.
	 * Null if GPS altitude was unavailable at capture time.
	 */
	@ColumnInfo(name = "raw_gps_alt_m")
	val rawGpsAltitudeM: Float?,

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
	val createdAt: Long,

	/** Monotonic time when the provider callback reached the app. */
	@ColumnInfo(name = "received_elapsed_realtime_nanos", defaultValue = "0")
	val receivedElapsedRealtimeNanos: Long = 0L,

	/** Callback delivery delay relative to the provider fix time, when both clocks were available. */
	@ColumnInfo(name = "delivery_age_ms")
	val deliveryAgeMs: Long? = null,

	@ColumnInfo(name = "acquisition_mode", defaultValue = "'UNKNOWN'")
	val acquisitionMode: String = "UNKNOWN",

	@ColumnInfo(name = "request_priority", defaultValue = "'UNKNOWN'")
	val requestPriority: String = "UNKNOWN",

	@ColumnInfo(name = "permission_precision", defaultValue = "'UNKNOWN'")
	val permissionPrecision: String = "UNKNOWN",

	@ColumnInfo(name = "batch_index", defaultValue = "0")
	val batchIndex: Int = 0,

	@ColumnInfo(name = "batch_size", defaultValue = "1")
	val batchSize: Int = 1,

	@ColumnInfo(name = "is_mock", defaultValue = "0")
	val isMock: Boolean = false,

	/** Versions make raw observations replayable under future estimator/calibration revisions. */
	@ColumnInfo(name = "estimator_version", defaultValue = "1")
	val estimatorVersion: Int = 1,

	@ColumnInfo(name = "calibration_version", defaultValue = "0")
	val calibrationVersion: Int = 0,

	/** Exact latitude retained from the pre-E7 2024.1 schema. */
	@ColumnInfo(name = "legacy_lat")
	val legacyLat: Double? = null,

	/** Exact longitude retained from the pre-E7 2024.1 schema. */
	@ColumnInfo(name = "legacy_lon")
	val legacyLon: Double? = null,

	/** Exact altitude retained from the 2024.1 schema. */
	@ColumnInfo(name = "legacy_alt_m")
	val legacyAltM: Double? = null,

	/** Stable pending-signal identity used to make replay idempotent. */
	@ColumnInfo(name = "source_signal_id")
	val sourceSignalId: String? = null,

	/** Immutable provider-fix identity; this links an accepted sample to its raw observation. */
	@ColumnInfo(name = "source_event_id")
	val sourceEventId: String? = null,

	/** Conservative clock-domain identity for [elapsedRealtimeNanos]. */
	@ColumnInfo(name = "clock_domain_id")
	val clockDomainId: String? = null,

	/** Snapshot revision assigned atomically with this source write. */
	@ColumnInfo(name = "source_revision", defaultValue = "0")
	val sourceRevision: Long = 0L,

	/** Reference surface for [altitudeM]. Historical rows default to [AltitudeDatum.UNKNOWN_LEGACY]. */
	@ColumnInfo(name = "alt_datum", defaultValue = "'unknown_legacy'")
	val altitudeDatum: AltitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,

	/** Source actually used for [altitudeM]; not an inferred label based on nullable inputs. */
	@ColumnInfo(name = "alt_source", defaultValue = "'unknown_legacy'")
	val altitudeSource: AltitudeSource = AltitudeSource.UNKNOWN_LEGACY,

	/** Typed Android-model conversion result for this location cycle. */
	@ColumnInfo(name = "alt_conversion_status", defaultValue = "'unknown_legacy'")
	val altitudeConversionStatus: AltitudeConversionStatus = AltitudeConversionStatus.UNKNOWN_LEGACY,

	/** Datum of [rawGpsAltitudeM], WGS-84 for newly captured Android raw altitude evidence. */
	@ColumnInfo(name = "raw_gps_alt_datum", defaultValue = "'unknown_legacy'")
	val rawGpsAltitudeDatum: AltitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,

	@ColumnInfo(name = "alt_model_version", defaultValue = "0")
	val altitudeModelVersion: Int = 0,
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
