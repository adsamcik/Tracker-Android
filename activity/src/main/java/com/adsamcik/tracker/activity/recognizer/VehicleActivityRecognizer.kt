package com.adsamcik.tracker.activity.recognizer

import com.adsamcik.tracker.common.data.DetectedActivity
import com.adsamcik.tracker.common.data.NativeSessionActivity
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.data.DatabaseLocation

internal class VehicleActivityRecognizer : ActivityRecognizer() {
	override val precisionConfidence: Int = 75

	override fun resolve(
			session: TrackerSession,
			locationCollection: Collection<DatabaseLocation>
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

		if (unknown.count + bicycle.count > (unknown.count + onFoot.count + vehicle.count) / LOCATION_BICYCLE_DENOMINATOR) {
			return ActivityRecognitionResult(NativeSessionActivity.BICYCLE, bicycle.confidence)
		}

		if (unknown.count + vehicle.count > locationCollection.size * MINIMUM_PERCENTAGE_OF_TOTAL_VEHICLE) {
			return ActivityRecognitionResult(NativeSessionActivity.VEHICLE, vehicle.confidence)
		}

		return ActivityRecognitionResult(null, 0)
	}

	companion object {
		const val LOCATION_BICYCLE_DENOMINATOR = 2
		const val MINIMUM_PERCENTAGE_OF_TOTAL_VEHICLE = 0.2
	}

}

