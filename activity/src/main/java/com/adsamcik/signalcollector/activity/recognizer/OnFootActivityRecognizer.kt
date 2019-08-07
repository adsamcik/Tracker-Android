package com.adsamcik.signalcollector.activity.recognizer

import com.adsamcik.signalcollector.common.data.DetectedActivity
import com.adsamcik.signalcollector.common.data.NativeSessionActivity
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import kotlin.math.roundToInt

internal class OnFootActivityRecognizer : ActivityRecognizer() {
	override val precisionConfidence: Int = 75

	override fun resolve(session: TrackerSession, locationCollection: Collection<DatabaseLocation>): ActivityRecognitionResult {
		val run = ActivitySum()
		val walk = ActivitySum()
		val onFoot = ActivitySum()
		val still = ActivitySum()
		val unknown = ActivitySum()
		val other = ActivitySum()

		locationCollection.forEach {
			when (it.activityInfo.activity) {
				DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> walk
				DetectedActivity.RUNNING -> run
				DetectedActivity.STILL -> still
				DetectedActivity.TILTING, DetectedActivity.UNKNOWN -> unknown
				else -> other
			}.apply {
				count++
				confidenceSum += it.activityInfo.confidence
			}
		}

		if (other.count + unknown.count > onFoot.count + walk.count + run.count + unknown.count) {
			return ActivityRecognitionResult(null, 0)
		}

		//just a little run should make it enough to consider it running session
		if (run.confidenceSum > walk.confidenceSum / WALK_DENOMINATOR) {
			val confidence = (run.count.toDouble() / locationCollection.size.toDouble()) * run.confidence
			return ActivityRecognitionResult(NativeSessionActivity.RUN, confidence.roundToInt())
		}

		return ActivityRecognitionResult(NativeSessionActivity.WALK, walk.confidence)
	}

	companion object {
		const val WALK_DENOMINATOR = 4
	}

}