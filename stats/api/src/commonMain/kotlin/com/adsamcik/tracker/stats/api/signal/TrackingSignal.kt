package com.adsamcik.tracker.stats.api.signal

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.value.*

/**
 * Unified per-cycle signal from the tracker service.
 * Each tracking cycle produces exactly one TrackingSignal.
 * Nullable fields indicate the sensor was unavailable that cycle.
 */
data class TrackingSignal(
	val timestampMs: EpochMs,
	val elapsedRealtimeNanos: Long = 0L,
	/** Provider observation before quality/consumer processing; persisted for replay and calibration. */
	val locationObservation: LocationObservationSignal? = null,
	val location: LocationSignal? = null,
	val activity: ActivitySignal? = null,
	val activityFresh: Boolean = true,
	val steps: StepSignal? = null,
	val cells: CellSignal? = null,
	val wifi: WifiSignal? = null,
	val pressure: PressureSignal? = null,
	val policy: PolicySignal? = null,
)

/** GPS location data for a single cycle. */
data class LocationSignal(
	val coordinate: CoordinateE7,
	val horizontalAccuracyM: Float,
	val speed: SpeedMps?,
	val altitudeM: Float? = null,
	val rawGpsAltitudeM: Float? = null,
	val verticalAccuracyM: Float? = null,
	val speedAccuracyMps: Float? = null,
	val distanceDelta: DistanceM? = null,
	val provider: String = "fused",
	/** Monotonic callback-receipt time; distinct from the provider fix time on [TrackingSignal]. */
	val receivedElapsedRealtimeNanos: Long = 0L,
	/** Stable acquisition backend name. Unknown values remain forward-compatible strings. */
	val acquisitionMode: String = "UNKNOWN",
	/** Request priority active for this fix (PASSIVE, BALANCED, HIGH_ACCURACY, ...). */
	val requestPriority: String = "UNKNOWN",
	/** Permission precision in effect when delivered (APPROXIMATE or PRECISE). */
	val permissionPrecision: String = "UNKNOWN",
	/** Original position and size of a batched provider callback. */
	val batchIndex: Int = 0,
	val batchSize: Int = 1,
	/** Whether Android identified this as a mock/test-provider fix. */
	val isMock: Boolean = false,
)

/**
 * Lossless-enough provider fix used for calibration/replay. Unlike [LocationSignal], accuracy is
 * nullable and the observation may have been rejected by the accepted-sample pipeline.
 */
data class LocationObservationSignal(
	/** Null when a provider delivered NaN, infinity, or an out-of-range coordinate. */
	val coordinate: CoordinateE7?,
	val horizontalAccuracyM: Float? = null,
	val altitudeM: Float? = null,
	val verticalAccuracyM: Float? = null,
	val speedMps: Float? = null,
	val speedAccuracyMps: Float? = null,
	val provider: String = "unknown",
	val receivedAtMs: Long = 0L,
	val receivedElapsedRealtimeNanos: Long = 0L,
	val acquisitionMode: String = "UNKNOWN",
	val requestPriority: String = "UNKNOWN",
	val permissionPrecision: String = "UNKNOWN",
	val batchIndex: Int = 0,
	val batchSize: Int = 1,
	val isMock: Boolean = false,
	val ingressDisposition: String = "DELIVERED_VALID",
)

/** Activity recognition data for a single cycle. */
data class ActivitySignal(
	val type: DetectedActivityType,
	val confidence: ActivityConfidence,
)

/** Step counter data for a single cycle. */
data class StepSignal(
	val stepDelta: StepCount,
	val totalStepsSinceBoot: Long,
	val sensorValueStart: Int = 0,
	val sensorValueEnd: Int = 0,
	val sensorReset: Boolean = false,
)

/** Cell tower scan data for a single cycle. */
data class CellSignal(
	val towers: List<CellTowerReading>,
)

/** Individual cell tower reading (platform-independent). */
data class CellTowerReading(
	val cellId: Long,
	val mcc: String,
	val mnc: String,
	val networkType: Int,
	val signalStrength: Int,
	val areaCode: Int = 0,
)

/** WiFi scan data for a single cycle. */
data class WifiSignal(
	val networks: List<WifiNetworkReading>,
	val timestampMs: EpochMs? = null,
	val coordinate: CoordinateE7? = null,
	val coordinateProvenance: ObservationCoordinateProvenance = ObservationCoordinateProvenance.UNKNOWN,
)

enum class ObservationCoordinateProvenance {
	UNKNOWN,
	DIRECT,
	INTERPOLATED,
}

/** Individual WiFi network reading (platform-independent). */
data class WifiNetworkReading(
	val bssid: String,
	val ssid: String,
	val capabilities: String,
	val frequency: Int,
	val level: Int,
)

/** Barometric pressure reading for a single cycle. */
data class PressureSignal(
	val pressureHpa: Float,
	val altitudeM: Float,
)

/** Active tracking policy at the time of the cycle. */
data class PolicySignal(
	val tier: PolicyTier,
	val policyName: String? = null,
)
