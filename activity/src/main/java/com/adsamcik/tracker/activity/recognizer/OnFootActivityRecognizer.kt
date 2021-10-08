package com.adsamcik.tracker.activity.recognizer

import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import kotlin.math.roundToInt

internal class OnFootActivityRecognizer : ActivityRecognizer() {
	override val precisionConfidence: Int = 75

	@Suppress("ComplexMethod")
	override fun resolve(
			session: TrackerSession,
			locationCollection: Collection<DatabaseLocation>
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

		if (other.count > onFoot.count + walk.count + run.count) {
			return ActivityRecognitionResult(null, 0)
		}

		// check if large enough portion consisted of running
		if (run.confidenceSum > walk.confidenceSum / WALK_DENOMINATOR) {
			val confidence = (run.count.toDouble() / locationCollection.size.toDouble()) * run.confidence
			return ActivityRecognitionResult(NativeSessionActivity.RUN, confidence.roundToInt())
		}

		return ActivityRecognitionResult(NativeSessionActivity.WALK, walk.confidence)
	}

	companion object {
		const val WALK_DENOMINATOR = 3
	}

}

