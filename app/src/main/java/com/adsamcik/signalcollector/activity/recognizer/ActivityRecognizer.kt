package com.adsamcik.signalcollector.activity.recognizer

import androidx.annotation.IntRange
import com.adsamcik.signalcollector.activity.SessionActivity
import com.adsamcik.signalcollector.common.data.TrackerSession

interface ActivityRecognizer {
	/**
	 * Represents how accurately can the algorithm detect activities.
	 * More accurate algorithms will be preferred.
	 *
	 * Algorithms that mostly recognize activity right should have positive value.
	 * While algorithms that sometimes recognize activity should have negative value.
	 */
	val precisionConfidence: Int

	fun resolve(session: TrackerSession): SessionActivity
}

data class ActivityRecognitionResult(val activityId: Int, @IntRange(from = 0, to = 100) val confidence: Int)