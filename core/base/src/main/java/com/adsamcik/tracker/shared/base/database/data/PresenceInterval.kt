package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Half-open canonical presence interval and the source of truth for tracked coverage accounting.
 * V1 records spatial support only; it does not classify stationary versus moving behaviour.
 */
@Entity(
	tableName = "presence_interval",
	indices = [
		Index(
			value = ["model_key", "session_id", "start_time_ms", "end_time_ms"],
			name = "idx_presence_interval_identity",
			unique = true,
		),
		Index(
			value = ["model_key", "end_time_ms", "start_time_ms"],
			name = "idx_presence_interval_overlap",
		),
	],
)
data class PresenceInterval(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,
	@ColumnInfo(name = "session_id")
	val sessionId: Long,
	@ColumnInfo(name = "model_key")
	val modelKey: String,
	@ColumnInfo(name = "estimator_version")
	val estimatorVersion: Int,
	@ColumnInfo(name = "calibration_version")
	val calibrationVersion: Int,
	@ColumnInfo(name = "config_hash")
	val configHash: String,
	@ColumnInfo(name = "start_time_ms")
	val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms")
	val endTimeMs: Long,
	@ColumnInfo(name = "start_elapsed_realtime_nanos")
	val startElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "end_elapsed_realtime_nanos")
	val endElapsedRealtimeNanos: Long?,
	/** Spatial support state: OBSERVED, INFERRED, or UNRESOLVED. Not a behaviour class. */
	@ColumnInfo(name = "resolution_state")
	val resolutionState: String,
	/** Behaviour state is independent of spatial support. V1 always stores UNKNOWN. */
	@ColumnInfo(name = "motion_state")
	val motionState: String?,
	val provenance: String,
	/** Why either the spatial or behavioural dimension remains unresolved. */
	@ColumnInfo(name = "unresolved_reason")
	val unresolvedReason: String?,
	@ColumnInfo(name = "center_lat_e7")
	val centerLatE7: Int?,
	@ColumnInfo(name = "center_lon_e7")
	val centerLonE7: Int?,
	@ColumnInfo(name = "cov_xx_m2")
	val covarianceXxM2: Double?,
	@ColumnInfo(name = "cov_xy_m2")
	val covarianceXyM2: Double?,
	@ColumnInfo(name = "cov_yy_m2")
	val covarianceYyM2: Double?,
	@ColumnInfo(name = "effective_r90_m")
	val effectiveR90M: Double?,
	@ColumnInfo(name = "posterior_format")
	val posteriorFormat: String?,
	@ColumnInfo(name = "posterior_payload")
	val posteriorPayload: ByteArray?,
	@ColumnInfo(name = "source_first_observation_id")
	val sourceFirstObservationId: Long?,
	@ColumnInfo(name = "source_last_observation_id")
	val sourceLastObservationId: Long?,
	@ColumnInfo(name = "created_at")
	val createdAt: Long,
)
