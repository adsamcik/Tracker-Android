package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One immutable execution of a historical trajectory algorithm against a coherent source revision.
 */
@Entity(
	tableName = "trajectory_reconstruction_run",
	indices = [
		Index(value = ["source_start_ms", "source_end_ms"], name = "idx_reconstruction_run_range"),
		Index(
			value = [
				"source_clock_domain_id",
				"source_start_elapsed_realtime_nanos",
				"source_end_elapsed_realtime_nanos",
			],
			name = "idx_reconstruction_run_clock_range",
		),
		Index(value = ["status", "created_at_ms"], name = "idx_reconstruction_run_status"),
	],
)
data class TrajectoryReconstructionRunEntity(
	@PrimaryKey
	@ColumnInfo(name = "run_id")
	val runId: String,
	@ColumnInfo(name = "source_start_ms")
	val sourceStartMs: Long,
	@ColumnInfo(name = "source_end_ms")
	val sourceEndMs: Long,
	@ColumnInfo(name = "source_clock_domain_id")
	val sourceClockDomainId: String? = null,
	@ColumnInfo(name = "source_boot_clock_domain_id")
	val sourceBootClockDomainId: String? = null,
	@ColumnInfo(name = "source_start_elapsed_realtime_nanos")
	val sourceStartElapsedRealtimeNanos: Long? = null,
	@ColumnInfo(name = "source_end_elapsed_realtime_nanos")
	val sourceEndElapsedRealtimeNanos: Long? = null,
	@ColumnInfo(name = "source_revision")
	val sourceRevision: Long,
	@ColumnInfo(name = "algorithm_version")
	val algorithmVersion: String,
	@ColumnInfo(name = "configuration_version")
	val configurationVersion: String,
	@ColumnInfo(name = "permission_branch")
	val permissionBranch: String,
	val status: String,
	@ColumnInfo(name = "created_at_ms")
	val createdAtMs: Long,
	@ColumnInfo(name = "completed_at_ms")
	val completedAtMs: Long? = null,
	@ColumnInfo(name = "supersedes_run_id")
	val supersedesRunId: String? = null,
	@ColumnInfo(name = "failure_reason")
	val failureReason: String? = null,
)

@Entity(
	tableName = "trajectory_state",
	foreignKeys = [
		ForeignKey(
			entity = TrajectoryReconstructionRunEntity::class,
			parentColumns = ["run_id"],
			childColumns = ["run_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(
			value = ["run_id", "estimate_kind", "state_index"],
			unique = true,
			name = "idx_trajectory_state_order",
		),
		Index(value = ["run_id", "time_ms"], name = "idx_trajectory_state_time"),
		Index(value = ["source_event_id"], name = "idx_trajectory_state_source_event"),
	],
)
data class TrajectoryStateEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,
	@ColumnInfo(name = "run_id")
	val runId: String,
	@ColumnInfo(name = "state_index")
	val stateIndex: Int,
	@ColumnInfo(name = "estimate_kind")
	val estimateKind: String,
	@ColumnInfo(name = "source_event_id")
	val sourceEventId: String?,
	@ColumnInfo(name = "time_ms")
	val timeMs: Long,
	@ColumnInfo(name = "elapsed_realtime_nanos")
	val elapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "clock_domain_id")
	val clockDomainId: String?,
	@ColumnInfo(name = "boot_clock_domain_id")
	val bootClockDomainId: String?,
	@ColumnInfo(name = "lat_e7")
	val latE7: Int,
	@ColumnInfo(name = "lon_e7")
	val lonE7: Int,
	@ColumnInfo(name = "velocity_east_mps")
	val velocityEastMps: Double,
	@ColumnInfo(name = "velocity_north_mps")
	val velocityNorthMps: Double,
	@ColumnInfo(name = "covariance_ee_m2")
	val covarianceEastEastM2: Double,
	@ColumnInfo(name = "covariance_en_m2")
	val covarianceEastNorthM2: Double,
	@ColumnInfo(name = "covariance_nn_m2")
	val covarianceNorthNorthM2: Double,
	@ColumnInfo(name = "stationary_probability")
	val stationaryProbability: Double,
	@ColumnInfo(name = "observation_weight")
	val observationWeight: Double,
	@ColumnInfo(name = "observation_health")
	val observationHealth: String,
)

@Entity(
	tableName = "trajectory_source_link",
	foreignKeys = [
		ForeignKey(
			entity = TrajectoryReconstructionRunEntity::class,
			parentColumns = ["run_id"],
			childColumns = ["run_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["run_id", "state_index"], name = "idx_trajectory_source_state"),
		Index(value = ["observation_id"], name = "idx_trajectory_source_observation"),
		Index(value = ["step_interval_id"], name = "idx_trajectory_source_step"),
		Index(value = ["activity_snapshot_id"], name = "idx_trajectory_source_activity"),
	],
	primaryKeys = ["run_id", "state_index", "observation_id"],
)
data class TrajectorySourceLinkEntity(
	@ColumnInfo(name = "run_id")
	val runId: String,
	@ColumnInfo(name = "state_index")
	val stateIndex: Int,
	@ColumnInfo(name = "observation_id")
	val observationId: Long,
	@ColumnInfo(name = "source_event_id")
	val sourceEventId: String?,
	@ColumnInfo(name = "source_signal_id")
	val sourceSignalId: String?,
	@ColumnInfo(name = "step_interval_id")
	val stepIntervalId: Long?,
	@ColumnInfo(name = "activity_snapshot_id")
	val activitySnapshotId: Long?,
	val weight: Double,
	val health: String,
	@ColumnInfo(name = "reason_codes")
	val reasonCodes: String?,
)

@Entity(
	tableName = "visit_interval",
	foreignKeys = [
		ForeignKey(
			entity = TrajectoryReconstructionRunEntity::class,
			parentColumns = ["run_id"],
			childColumns = ["run_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index(value = ["run_id", "start_time_ms"], name = "idx_visit_interval_run_time")],
)
data class VisitIntervalEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,
	@ColumnInfo(name = "run_id")
	val runId: String,
	@ColumnInfo(name = "start_time_ms")
	val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms")
	val endTimeMs: Long,
	@ColumnInfo(name = "start_elapsed_realtime_nanos")
	val startElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "end_elapsed_realtime_nanos")
	val endElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "clock_domain_id")
	val clockDomainId: String?,
	@ColumnInfo(name = "boot_clock_domain_id")
	val bootClockDomainId: String?,
	@ColumnInfo(name = "arrival_uncertainty_ms")
	val arrivalUncertaintyMs: Long,
	@ColumnInfo(name = "departure_uncertainty_ms")
	val departureUncertaintyMs: Long,
	@ColumnInfo(name = "centroid_lat_e7")
	val centroidLatE7: Int,
	@ColumnInfo(name = "centroid_lon_e7")
	val centroidLonE7: Int,
	@ColumnInfo(name = "covariance_ee_m2")
	val covarianceEastEastM2: Double,
	@ColumnInfo(name = "covariance_en_m2")
	val covarianceEastNorthM2: Double,
	@ColumnInfo(name = "covariance_nn_m2")
	val covarianceNorthNorthM2: Double,
	val probability: Double,
)
