package com.adsamcik.tracker.stats.api

/**
 * Classification of how a trip was detected/created.
 */
enum class TripSource {
	/** User explicitly started a tracking session */
	USER_CREATED,
	/** Detected in real-time by SessionSegmentDetector */
	REALTIME_DETECTION,
	/** Created by batch/offline inference */
	BATCH_INFERENCE
}
