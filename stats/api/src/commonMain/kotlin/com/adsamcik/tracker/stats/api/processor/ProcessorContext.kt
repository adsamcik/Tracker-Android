package com.adsamcik.tracker.stats.api.processor

import com.adsamcik.tracker.stats.api.value.EpochMs

/**
 * Context provided to a processor when it starts.
 *
 * @property startTimestamp When tracking started
 * @property isResuming Whether this is resuming from a crash
 * @property sessionId Room-generated TrackerSession ID (used by consumers to query session data)
 */
data class ProcessorContext(
	val startTimestamp: EpochMs,
	val isResuming: Boolean = false,
	val sessionId: Long = 0L,
)
