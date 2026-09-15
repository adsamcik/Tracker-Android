package com.adsamcik.tracker.tracker.data.collection

import android.location.Location
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.LocationProviderObservation
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.altitude.AltitudeProcessor
import com.adsamcik.tracker.tracker.altitude.AltitudeProcessorState
import java.util.UUID

internal data class LocationCanonicalCurationPoint(
	val provider: String,
	val timeMs: Long,
	val elapsedRealtimeNanos: Long,
	val latitude: Double,
	val longitude: Double,
	val accuracyM: Float?,
	val altitudeM: Double?,
	val verticalAccuracyM: Float?,
	val speedMps: Float?,
	val speedAccuracyMps: Float?,
	val bearingDegrees: Float?,
	val bearingAccuracyDegrees: Float?,
) {
	init {
		require(provider.isNotBlank())
		require(timeMs >= 0L && elapsedRealtimeNanos > 0L)
		require(latitude.isFinite() && latitude in -90.0..90.0)
		require(longitude.isFinite() && longitude in -180.0..180.0)
		require(accuracyM == null || accuracyM.isFinite() && accuracyM >= 0f)
		require(altitudeM == null || altitudeM.isFinite())
		require(verticalAccuracyM == null ||
			verticalAccuracyM.isFinite() && verticalAccuracyM >= 0f)
		require(speedMps == null || speedMps.isFinite() && speedMps >= 0f)
		require(speedAccuracyMps == null ||
			speedAccuracyMps.isFinite() && speedAccuracyMps >= 0f)
		require(bearingDegrees == null ||
			bearingDegrees.isFinite() && bearingDegrees >= 0f && bearingDegrees < 360f)
		require(bearingAccuracyDegrees == null ||
			bearingAccuracyDegrees.isFinite() && bearingAccuracyDegrees >= 0f)
	}
}

internal data class LocationCanonicalCurationState(
	val lastAccepted: LocationCanonicalCurationPoint?,
	val pendingReacquisition: LocationCanonicalCurationPoint?,
	val lastSmoothedSpeedMps: Float,
	val altitudeProcessorState: AltitudeProcessorState,
) {
	init {
		require(lastSmoothedSpeedMps.isFinite() && lastSmoothedSpeedMps >= 0f)
	}

	companion object {
		val EMPTY = LocationCanonicalCurationState(
			lastAccepted = null,
			pendingReacquisition = null,
			lastSmoothedSpeedMps = 0f,
			altitudeProcessorState = AltitudeProcessor.initialState(),
		)
	}
}

internal class LocationCanonicalCurationOutcome(
	initialState: LocationCanonicalCurationState,
) {
	var decisionReason: String? = null
	var stateAfter: LocationCanonicalCurationState = initialState
}

internal data class LocationCanonicalCurationContext(
	val requiredAccuracyMeters: Float,
	val policyTier: PolicyTier,
	val policyName: String,
	val curationVersion: Int,
	val altitudeModelVersion: Int,
	val altitudeEstimatorVersion: Int,
	val altitudeCalibrationVersion: Int,
	val stateBefore: LocationCanonicalCurationState = LocationCanonicalCurationState.EMPTY,
	val outcome: LocationCanonicalCurationOutcome =
		LocationCanonicalCurationOutcome(stateBefore),
) {
	init {
		require(requiredAccuracyMeters.isFinite() && requiredAccuracyMeters >= 0f)
		require(policyName.isNotBlank())
		require(curationVersion > 0)
		require(altitudeModelVersion >= 0)
		require(altitudeEstimatorVersion >= 0)
		require(altitudeCalibrationVersion >= 0)
	}
}

/**
 * Typed envelope for one collection cycle's worth of sensor data.
 * Immutable — constructed from [TrackingCycleBuilder] after all producers finish.
 */
internal data class TrackingCycle(
	val timestampMs: Long,
	val elapsedRealtimeNanos: Long,
	val activity: ActivityInfo? = null,
	val activityFresh: Boolean = false,
	val activitySourceElapsedRealtimeNanos: Long? = null,
	val activitySourceSequence: Long? = null,
	val location: LocationData? = null,
	/** Exact historical policy/curation contract for protected Location WAL replay. */
	val locationCanonicalCuration: LocationCanonicalCurationContext? = null,
	/** Lossless provider deliveries captured before trigger and pipeline rejection. */
	val locationObservations: List<LocationProviderObservation> = emptyList(),
	val cellScan: CellScanData? = null,
	val cellScanFresh: Boolean = false,
	val wifiScan: WifiScanData? = null,
	val stepDelta: Int? = null,
	val totalStepsSinceBoot: Long? = null,
	val stepSensorValueStart: Int = 0,
	val stepSensorValueEnd: Int = 0,
	val stepSensorReset: Boolean = false,
	val stepWindowStartElapsedRealtimeNanos: Long? = null,
	val stepWindowEndElapsedRealtimeNanos: Long? = null,
	val stepSourceFirstSequence: Long? = null,
	val stepSourceLastSequence: Long? = null,
	val pressure: PressureReading? = null,
	val rawGpsAltitude: Double? = null,
	/**
	 * Stable identity assigned while this collection unit is still owned by the
	 * cycle queue. It is propagated to [com.adsamcik.tracker.stats.api.signal.TrackingSignal]
	 * and becomes the pending-signal idempotency key.
	 */
	val persistenceSignalId: String = UUID.randomUUID().toString(),
)

internal fun Location.toCanonicalCurationPoint(): LocationCanonicalCurationPoint =
	LocationCanonicalCurationPoint(
		provider = provider ?: "unknown",
		timeMs = time,
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		latitude = latitude,
		longitude = longitude,
		accuracyM = accuracy.takeIf { hasAccuracy() },
		altitudeM = altitude.takeIf { hasAltitude() },
		verticalAccuracyM = verticalAccuracyMeters.takeIf { hasVerticalAccuracy() },
		speedMps = speed.takeIf { hasSpeed() },
		speedAccuracyMps = speedAccuracyMetersPerSecond.takeIf { hasSpeedAccuracy() },
		bearingDegrees = bearing.takeIf { hasBearing() },
		bearingAccuracyDegrees = bearingAccuracyDegrees.takeIf { hasBearingAccuracy() },
	)

internal fun LocationCanonicalCurationPoint.toPlatformLocation(): Location =
	Location(provider).also { location ->
		location.time = timeMs
		location.elapsedRealtimeNanos = elapsedRealtimeNanos
		location.latitude = latitude
		location.longitude = longitude
		accuracyM?.let { location.accuracy = it }
		altitudeM?.let { location.altitude = it }
		verticalAccuracyM?.let { location.verticalAccuracyMeters = it }
		speedMps?.let { location.speed = it }
		speedAccuracyMps?.let { location.speedAccuracyMetersPerSecond = it }
		bearingDegrees?.let { location.bearing = it }
		bearingAccuracyDegrees?.let { location.bearingAccuracyDegrees = it }
	}

/**
 * Whether a producer supplied new data that still needs to pass through the tracking pipeline.
 *
 * This is intentionally stricter than checking whether a cached snapshot is present. Cell and
 * activity producers keep their latest value in the cycle for context, but only fresh snapshots
 * are persisted. Wi-Fi producers already de-duplicate cached scans before attaching them.
 */
internal fun TrackingCycle.hasPersistableProducerPayload(): Boolean =
	(activityFresh && activity != null) ||
		(cellScanFresh && cellScan != null) ||
		wifiScan != null ||
		(stepDelta != null && (stepDelta > 0 || stepSensorReset)) ||
		pressure != null

/** True for a provider callback containing only rejected raw fixes. */
internal fun TrackingCycle.isLocationObservationOnly(): Boolean =
	locationObservations.isNotEmpty() &&
		location == null &&
		activity == null &&
		cellScan == null &&
		wifiScan == null &&
		stepDelta == null &&
		pressure == null
