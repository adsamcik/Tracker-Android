package com.adsamcik.signalcollector.activity.recognizer

import com.adsamcik.signalcollector.activity.NativeSessionActivity
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import com.google.android.gms.location.DetectedActivity

class OnFootActivityRecognizer : ActivityRecognizer {
	override val precisionConfidence: Int = 75

	override fun resolve(session: TrackerSession, locationCollection: Collection<DatabaseLocation>): ActivityRecognitionResult {
		val run = ActivitySum()
		val walk = ActivitySum()
		val onFoot = ActivitySum()
		val still = ActivitySum()
		val other = ActivitySum()

		locationCollection.forEach {
			when (it.activityInfo.activity) {
				DetectedActivity.ON_FOOT -> onFoot
				DetectedActivity.WALKING -> walk
				DetectedActivity.RUNNING -> run
				DetectedActivity.STILL -> still
				else -> other
			}.apply {
				count++
				confidenceSum += it.activityInfo.confidence
			}
		}

		if (other.count > onFoot.count + walk.count + run.count) {
			return ActivityRecognitionResult(null, 0)
		}

		if (walk.count / WALK_DENOMINATOR > run.count) {
			return ActivityRecognitionResult(NativeSessionActivity.WALK, walk.confidence)
		}

		return ActivityRecognitionResult(NativeSessionActivity.RUN, run.confidence)
	}

	data class ActivitySum(var count: Int = 0, var confidenceSum: Int = 0) {
		val confidence get() = if (count == 0) 0 else confidenceSum / count
	}

	companion object {
		const val WALK_DENOMINATOR = 4
	}

}