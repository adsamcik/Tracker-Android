package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.CellSignal
import com.adsamcik.tracker.stats.api.signal.CellTowerReading
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.PolicySignal
import com.adsamcik.tracker.stats.api.signal.PressureSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.signal.WifiNetworkReading
import com.adsamcik.tracker.stats.api.signal.WifiSignal
import com.adsamcik.tracker.stats.api.threshold.ActivityTypeMapping
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount

/**
 * Converts raw tracker data into a unified [TrackingSignal].
 * Single creation point for all signal data, ensuring consistent value class wrapping.
 */
object SignalAdapter {

	/**
	 * Build a TrackingSignal from raw tracker sensor data.
	 *
	 * @param timestampMs Collection cycle timestamp
	 * @param elapsedRealtimeNanos Monotonic clock for sensor correlation
	 * @param latitude Raw latitude in degrees, null if no GPS fix
	 * @param longitude Raw longitude in degrees, null if no GPS fix
	 * @param accuracy Horizontal accuracy in meters
	 * @param speed Speed in m/s, null if unavailable
	 * @param altitude Fused altitude in meters, null if unavailable
	 * @param rawGpsAltitude Raw GPS altitude before fusion, null if unavailable
	 * @param verticalAccuracy Vertical accuracy in meters, null if unavailable
	 * @param speedAccuracy Speed accuracy in m/s, null if unavailable
	 * @param distanceDelta Distance from previous location in meters
	 * @param provider Location provider name
	 * @param activityTypeCode Google Play Services activity int code
	 * @param activityConfidence Activity confidence 0-100
	 * @param stepDelta Steps since last signal
	 * @param totalStepsSinceBoot Total steps since device boot
	 * @param stepSensorValueStart Raw sensor value at interval start
	 * @param stepSensorValueEnd Raw sensor value at interval end
	 * @param stepSensorReset Whether step sensor reset was detected
	 * @param cellTowers Cell tower readings, null if unavailable
	 * @param wifiNetworks WiFi network readings, null if unavailable
	 * @param pressureHpa Barometric pressure in hPa, null if unavailable
	 * @param pressureAltitudeM Barometric altitude in meters, null if unavailable
	 * @param policyTier Current tracking policy tier, null if unavailable
	 * @param policyName Current tracking policy name, null if unavailable
	 */
	fun buildSignal(
		timestampMs: Long,
		elapsedRealtimeNanos: Long = 0L,
		latitude: Double? = null,
		longitude: Double? = null,
		accuracy: Float? = null,
		speed: Float? = null,
		altitude: Float? = null,
		rawGpsAltitude: Float? = null,
		verticalAccuracy: Float? = null,
		speedAccuracy: Float? = null,
		distanceDelta: Float? = null,
		provider: String = "fused",
		activityTypeCode: Int? = null,
		activityConfidence: Int? = null,
		activityFresh: Boolean = true,
		stepDelta: Int? = null,
		totalStepsSinceBoot: Long? = null,
		stepSensorValueStart: Int = 0,
		stepSensorValueEnd: Int = 0,
		stepSensorReset: Boolean = false,
		cellTowers: List<CellTowerReading>? = null,
		wifiNetworks: List<WifiNetworkReading>? = null,
		pressureHpa: Float? = null,
		wifiTimestampMs: Long? = null,
		wifiLatitude: Double? = null,
		wifiLongitude: Double? = null,
		wifiCoordinateProvenance: com.adsamcik.tracker.stats.api.signal.ObservationCoordinateProvenance = com.adsamcik.tracker.stats.api.signal.ObservationCoordinateProvenance.UNKNOWN,
		pressureAltitudeM: Float? = null,
		policyTier: PolicyTier? = null,
		policyName: String? = null,
	): TrackingSignal {
		val locationSignal = if (latitude != null && longitude != null && accuracy != null) {
			LocationSignal(
				coordinate = CoordinateE7(
					lat = LatE7.fromDegrees(latitude),
					lon = LonE7.fromDegrees(longitude),
				),
				horizontalAccuracyM = accuracy,
				speed = speed?.let { SpeedMps.coerced(it) },
				altitudeM = altitude,
				rawGpsAltitudeM = rawGpsAltitude,
				verticalAccuracyM = verticalAccuracy,
				speedAccuracyMps = speedAccuracy,
				distanceDelta = distanceDelta?.let { DistanceM.coerced(it) },
				provider = provider,
			)
		} else {
			null
		}

		val activitySignal = if (activityTypeCode != null && activityConfidence != null) {
			ActivitySignal(
				type = ActivityTypeMapping.fromPlayServicesCode(activityTypeCode),
				confidence = ActivityConfidence.coerced(activityConfidence),
			)
		} else {
			null
		}

		val stepSignal = if (stepDelta != null && stepDelta > 0) {
			StepSignal(
				stepDelta = StepCount.coerced(stepDelta),
				totalStepsSinceBoot = totalStepsSinceBoot ?: 0L,
				sensorValueStart = stepSensorValueStart,
				sensorValueEnd = stepSensorValueEnd,
				sensorReset = stepSensorReset,
			)
		} else {
			null
		}

		val cellSignal = if (!cellTowers.isNullOrEmpty()) {
			CellSignal(towers = cellTowers)
		} else {
			null
		}

		val wifiCoordinate = if (wifiLatitude != null && wifiLongitude != null) {
			CoordinateE7(
				lat = LatE7.fromDegrees(wifiLatitude),
				lon = LonE7.fromDegrees(wifiLongitude),
			)
		} else {
			null
		}
		val wifiSignal = if (!wifiNetworks.isNullOrEmpty()) {
			WifiSignal(
				networks = wifiNetworks,
				timestampMs = wifiTimestampMs?.let(::EpochMs),
				coordinate = wifiCoordinate,
				coordinateProvenance = wifiCoordinateProvenance,
			)
		} else {
			null
		}

		val pressureSignal = if (pressureHpa != null && pressureAltitudeM != null) {
			PressureSignal(pressureHpa = pressureHpa, altitudeM = pressureAltitudeM)
		} else {
			null
		}

		val policySignal = if (policyTier != null) {
			PolicySignal(tier = policyTier, policyName = policyName)
		} else {
			null
		}

		return TrackingSignal(
			timestampMs = EpochMs(timestampMs),
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			location = locationSignal,
			activity = activitySignal,
			activityFresh = activityFresh,
			steps = stepSignal,
			cells = cellSignal,
			wifi = wifiSignal,
			pressure = pressureSignal,
			policy = policySignal,
		)
	}
}
