package com.adsamcik.tracker.activity.recognizer

import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession

internal class VehicleActivityRecognizer : ActivityRecognizer() {
	override val precisionConfidence: Int = 75

	override fun resolve(
			session: TrackerSession,
			locationCollection: Collection<ActivityLocation>
	): ActivityRecognitionResult {
		val vehicle = ActivitySum()
		val bicycle = ActivitySum()
		val onFoot = ActivitySum()
		val still = ActivitySum()
		val unknown = ActivitySum()
		val other = ActivitySum()

		locationCollection.forEach {
			when (it.activityInfo.activity) {
				DetectedActivity.ON_FOOT, DetectedActivity.WALKING, DetectedActivity.RUNNING -> onFoot
				DetectedActivity.IN_VEHICLE -> vehicle
				DetectedActivity.ON_BICYCLE -> bicycle
				DetectedActivity.STILL -> still
				DetectedActivity.UNKNOWN, DetectedActivity.TILTING -> unknown
				else -> other
			}.apply {
				count++
				confidenceSum += it.activityInfo.confidence
			}
		}
		if (
			bicycle.count > 0 &&
			bicycle.count > (onFoot.count + vehicle.count) / LOCATION_BICYCLE_DENOMINATOR &&
			bicycle.confidence > 0
		) {
			return ActivityRecognitionResult(NativeSessionActivity.BICYCLE, bicycle.confidence)
		}

		if (
			vehicle.count > 0 &&
			vehicle.count > locationCollection.size * MINIMUM_PERCENTAGE_OF_TOTAL_VEHICLE &&
			vehicle.confidence > 0
		) {
			return ActivityRecognitionResult(NativeSessionActivity.LAND_VEHICLE, vehicle.confidence)
		}

		return ActivityRecognitionResult(null, 0)
	}

	companion object {
		private const val LOCATION_BICYCLE_DENOMINATOR = 2
		private const val MINIMUM_PERCENTAGE_OF_TOTAL_VEHICLE = 0.2
	}

}
