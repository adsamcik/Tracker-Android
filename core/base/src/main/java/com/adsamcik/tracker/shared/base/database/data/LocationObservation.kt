package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only provider observation captured before accepted-sample filtering.
 *
 * This table is the replay/calibration contract. Existing consumers continue to read
 * `location_sample`, whose rows represent fixes accepted by the tracking pipeline.
 */
@Entity(
	tableName = "location_observation",
	indices = [
		Index(value = ["fix_time_ms", "id"], name = "idx_location_observation_fix_time"),
		Index(
			value = ["received_elapsed_realtime_nanos", "batch_index"],
			name = "idx_location_observation_delivery",
		),
	],
)
data class LocationObservation(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,
	@ColumnInfo(name = "fix_time_ms")
	val fixTimeMs: Long,
	@ColumnInfo(name = "fix_elapsed_realtime_nanos")
	val fixElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "received_at_ms")
	val receivedAtMs: Long,
	@ColumnInfo(name = "received_elapsed_realtime_nanos")
	val receivedElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "delivery_age_ms")
	val deliveryAgeMs: Long?,
	@ColumnInfo(name = "lat_e7")
	val latE7: Int?,
	@ColumnInfo(name = "lon_e7")
	val lonE7: Int?,
	@ColumnInfo(name = "raw_alt_m")
	val rawAltitudeM: Float?,
	@ColumnInfo(name = "h_acc_m")
	val hAccM: Float?,
	@ColumnInfo(name = "v_acc_m")
	val vAccM: Float?,
	@ColumnInfo(name = "speed_mps")
	val speedMps: Float?,
	@ColumnInfo(name = "speed_accuracy_mps")
	val speedAccuracyMps: Float?,
	val provider: String,
	@ColumnInfo(name = "acquisition_mode")
	val acquisitionMode: String,
	@ColumnInfo(name = "request_priority")
	val requestPriority: String,
	@ColumnInfo(name = "permission_precision")
	val permissionPrecision: String,
	@ColumnInfo(name = "batch_index")
	val batchIndex: Int,
	@ColumnInfo(name = "batch_size")
	val batchSize: Int,
	@ColumnInfo(name = "is_mock")
	val isMock: Boolean,
	/** Provider-ingress outcome, including valid, stale, invalid-coordinate, and migrated evidence. */
	@ColumnInfo(name = "ingress_disposition")
	val ingressDisposition: String,
	@ColumnInfo(name = "estimator_version")
	val estimatorVersion: Int,
	@ColumnInfo(name = "calibration_version")
	val calibrationVersion: Int,
	@ColumnInfo(name = "created_at")
	val createdAt: Long,
)
