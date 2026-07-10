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
)

/** WiFi scan data for a single cycle. */
data class WifiSignal(
	val networks: List<WifiNetworkReading>,
)

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
