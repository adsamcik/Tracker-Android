package com.adsamcik.tracker.activity.recognizer

import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import kotlin.math.roundToInt

internal class OnFootActivityRecognizer : ActivityRecognizer() {
	override val precisionConfidence: Int = 75

	override fun resolve(
			session: TrackerSession,
			locationCollection: Collection<ActivityLocation>
	): ActivityRecognitionResult {
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

		val onFootSignalCount = onFoot.count + walk.count + run.count
		if (onFootSignalCount == 0 || other.count > onFootSignalCount) {
			return ActivityRecognitionResult(null, 0)
		}
		// check if large enough portion consisted of running
		if (run.confidenceSum > walk.confidenceSum / WALK_DENOMINATOR) {
			val confidence = (run.count.toDouble() / locationCollection.size.toDouble()) * run.confidence
			return if (confidence > 0.0) {
				ActivityRecognitionResult(NativeSessionActivity.RUNNING, confidence.roundToInt())
			} else {
				ActivityRecognitionResult(null, 0)
			}
		}

		return if (walk.confidence > 0) {
			ActivityRecognitionResult(NativeSessionActivity.WALKING, walk.confidence)
		} else {
			ActivityRecognitionResult(null, 0)
		}
	}

	companion object {
		const val WALK_DENOMINATOR = 3
	}

}
