package com.adsamcik.signalcollector.activity.recognizer

import androidx.annotation.IntRange
import com.adsamcik.signalcollector.common.data.NativeSessionActivity
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation

interface ActivityRecognizer {
	/**
	 * Represents how accurately can the algorithm detect activities.
	 * More accurate algorithms will be preferred.
	 *
	 * Algorithms that mostly recognize activity right should have positive value.
	 * While algorithms that sometimes recognize activity should have negative value.
	 */
	val precisionConfidence: Int

	fun resolve(session: TrackerSession, locationCollection: Collection<DatabaseLocation>): ActivityRecognitionResult
}

data class ActivityRecognitionResult(val recognizedActivity: NativeSessionActivity?, @IntRange(from = 0, to = 100) val confidence: Int) {
	val requireRecognizedActivity: NativeSessionActivity
		get() = recognizedActivity ?: throw NullPointerException("Recognized activity was null")
}