package com.adsamcik.signalcollector.activity.recognizer

import com.adsamcik.signalcollector.activity.NativeSessionActivity
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import com.google.android.gms.location.DetectedActivity

class VehicleActivityRecognizer : ActivityRecognizer {
	override val precisionConfidence: Int = 75

	override fun resolve(session: TrackerSession, locationCollection: Collection<DatabaseLocation>): ActivityRecognitionResult {
		val vehicle = ActivitySum()
		val bicycle = ActivitySum()
		val onFoot = ActivitySum()
		val still = ActivitySum()
		val other = ActivitySum()

		locationCollection.forEach {
			when (it.activityInfo.activity) {
				DetectedActivity.ON_FOOT, DetectedActivity.WALKING, DetectedActivity.RUNNING -> onFoot
				DetectedActivity.IN_VEHICLE -> vehicle
				DetectedActivity.ON_BICYCLE -> bicycle
				DetectedActivity.STILL -> still
				else -> other
			}.apply {
				count++
				confidenceSum += it.activityInfo.confidence
			}
		}

		if (bicycle.count > (onFoot.count + vehicle.count) / LOCATION_BICYCLE_DENOMINATOR) {
			return ActivityRecognitionResult(NativeSessionActivity.BICYCLE, bicycle.confidence)
		}

		if (vehicle.count * MINIMUM_PERCENTAGE_OF_TOTAL_VEHICLE > locationCollection.size) {
			return ActivityRecognitionResult(NativeSessionActivity.VEHICLE, vehicle.confidence)
		}

		return ActivityRecognitionResult(null, 0)
	}

	data class ActivitySum(var count: Int = 0, var confidenceSum: Int = 0) {
		val confidence get() = if (count == 0) 0 else confidenceSum / count
	}

	companion object {
		const val LOCATION_BICYCLE_DENOMINATOR = 2
		const val MINIMUM_PERCENTAGE_OF_TOTAL_VEHICLE = 0.2
	}

}