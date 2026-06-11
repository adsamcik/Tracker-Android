package com.adsamcik.tracker.activity.recognizer

import androidx.annotation.IntRange
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.model.Location

internal interface IActivityRecognizer {
	/**
	 * Represents how accurately can the algorithm detect activities.
	 * More accurate algorithms will be preferred.
	 *
	 * Algorithms that mostly recognize activity right should have positive value.
	 * While algorithms that sometimes recognize activity should have negative value.
	 */
	val precisionConfidence: Int

	fun resolve(
			session: TrackerSession,
			locationCollection: Collection<ActivityLocation>
	): ActivityRecognitionResult
}

internal abstract class ActivityRecognizer : IActivityRecognizer {

	protected data class ActivitySum(var count: Int = 0, var confidenceSum: Int = 0) {
		val confidence get() = if (count == 0) 0 else confidenceSum / count
	}
}

internal data class ActivityLocation(
	val location: Location,
	val activityInfo: ActivityInfo,
) {
	val latitude: Double get() = location.latitude
	val longitude: Double get() = location.longitude
	val altitude: Double? get() = location.altitude
	val time: Long get() = location.time
}

data class ActivityRecognitionResult(
		val recognizedActivity: NativeSessionActivity?,
		@IntRange(from = 0, to = 100)
		val confidence: Int
) {
	val requireRecognizedActivity: NativeSessionActivity
		get() = recognizedActivity ?: throw NullPointerException("Recognized activity was null")
}
