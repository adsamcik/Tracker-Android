package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
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
	 * @param latitude Raw latitude in degrees, null if no GPS fix
	 * @param longitude Raw longitude in degrees, null if no GPS fix
	 * @param accuracy Horizontal accuracy in meters
	 * @param speed Speed in m/s, null if unavailable
	 * @param altitude Altitude in meters, null if unavailable
	 * @param distanceDelta Distance from previous location in meters
	 * @param activityTypeCode Google Play Services activity int code
	 * @param activityConfidence Activity confidence 0-100
	 * @param stepDelta Steps since last signal
	 * @param totalStepsSinceBoot Total steps since device boot
	 */
	fun buildSignal(
		timestampMs: Long,
		latitude: Double? = null,
		longitude: Double? = null,
		accuracy: Float? = null,
		speed: Float? = null,
		altitude: Float? = null,
		distanceDelta: Float? = null,
		activityTypeCode: Int? = null,
		activityConfidence: Int? = null,
		stepDelta: Int? = null,
		totalStepsSinceBoot: Long? = null,
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
				distanceDelta = distanceDelta?.let { DistanceM.coerced(it) },
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
			)
		} else {
			null
		}

		return TrackingSignal(
			timestampMs = EpochMs(timestampMs),
			location = locationSignal,
			activity = activitySignal,
			steps = stepSignal,
		)
	}
}
