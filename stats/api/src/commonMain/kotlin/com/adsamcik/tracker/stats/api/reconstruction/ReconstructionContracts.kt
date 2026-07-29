package com.adsamcik.tracker.stats.api.reconstruction

/**
 * Immutable location evidence supplied to a historical reconstruction algorithm.
 *
 * Raw provider motion fields stay separate from any live-track estimate. A caller should provide
 * both elapsed and epoch time when available; [clockDomainId] and [bootClockDomainId] define the
 * boundaries within which elapsed time may be compared.
 */
data class LocationReconstructionObservation(
	val sourceId: String,
	val epochMs: Long,
	val elapsedRealtimeNanos: Long? = null,
	val clockDomainId: String? = null,
	val bootClockDomainId: String? = null,
	val latitudeE7: Int,
	val longitudeE7: Int,
	val horizontalAccuracyM: Double? = null,
	val platformSpeedMps: Double? = null,
	val platformSpeedAccuracyMps: Double? = null,
	val bearingDeg: Double? = null,
	val bearingAccuracyDeg: Double? = null,
	val provider: String = "unknown",
	val acquisitionMode: String = "UNKNOWN",
	val requestPriority: String = "UNKNOWN",
	val permissionPrecision: String = "UNKNOWN",
	val batchIndex: Int = 0,
	val batchSize: Int = 1,
	val callbackId: String? = null,
	val sourceAgeMs: Long? = null,
	val timeUncertaintyMs: Long? = null,
	val isMock: Boolean = false,
	val ingressDisposition: String = "DELIVERED_VALID",
	val stepDelta: Int? = null,
	val activity: String? = null,
) {
	init {
		require(sourceId.isNotBlank())
		require(latitudeE7 in -900_000_000..900_000_000)
		require(longitudeE7 in -1_800_000_000..1_800_000_000)
		require(batchSize > 0 && batchIndex in 0 until batchSize)
		require(horizontalAccuracyM == null || horizontalAccuracyM.isFinite() && horizontalAccuracyM >= 0.0)
		require(platformSpeedMps == null || platformSpeedMps.isFinite() && platformSpeedMps >= 0.0)
		require(
			platformSpeedAccuracyMps == null ||
				platformSpeedAccuracyMps.isFinite() && platformSpeedAccuracyMps >= 0.0
		)
		require(bearingDeg == null || bearingDeg.isFinite())
		require(bearingAccuracyDeg == null || bearingAccuracyDeg.isFinite() && bearingAccuracyDeg >= 0.0)
	}
}

enum class ObservationHealth {
	HEALTHY,
	DEGRADED,
	STALE,
	REJECTED,
}

enum class ObservationQualityReason {
	MOCK_PROVIDER,
	INVALID_INGRESS,
	APPROXIMATE_PERMISSION,
	MISSING_ACCURACY,
	POOR_ACCURACY,
	STALE_SOURCE,
	BATCH_CORRELATION,
	DUPLICATE_OR_FROZEN,
	NON_MONOTONIC_TIME,
	MOTION_INFEASIBLE,
	UNRELIABLE_BEARING,
}

data class WeightedLocationObservation(
	val observation: LocationReconstructionObservation,
	/** Calibrated one-sigma horizontal standard deviation. */
	val horizontalSigmaM: Double,
	/** Multiplicative information weight in the closed interval 0..1. */
	val informationWeight: Double,
	val stationaryProbability: Double,
	val health: ObservationHealth,
	val reasons: Set<ObservationQualityReason> = emptySet(),
) {
	init {
		require(horizontalSigmaM.isFinite() && horizontalSigmaM > 0.0)
		require(informationWeight in 0.0..1.0)
		require(stationaryProbability in 0.0..1.0)
	}
}

enum class TrajectoryEstimateKind {
	FILTERED,
	SMOOTHED,
}

data class TrajectoryStateEstimate(
	val sourceId: String,
	val epochMs: Long,
	val elapsedRealtimeNanos: Long?,
	val clockDomainId: String? = null,
	val bootClockDomainId: String? = null,
	val latitudeE7: Int,
	val longitudeE7: Int,
	val velocityEastMps: Double,
	val velocityNorthMps: Double,
	val positionCovarianceEastEastM2: Double,
	val positionCovarianceEastNorthM2: Double,
	val positionCovarianceNorthNorthM2: Double,
	val stationaryProbability: Double,
	val kind: TrajectoryEstimateKind,
	val observationHealth: ObservationHealth,
	val observationWeight: Double,
) {
	val speedMps: Double
		get() = kotlin.math.sqrt(
			velocityEastMps * velocityEastMps + velocityNorthMps * velocityNorthMps,
		)
}

data class TrajectoryReconstructionResult(
	val algorithmVersion: String,
	val configurationVersion: String,
	val filtered: List<TrajectoryStateEstimate>,
	val smoothed: List<TrajectoryStateEstimate>,
	val rejectedSourceIds: Set<String>,
)
