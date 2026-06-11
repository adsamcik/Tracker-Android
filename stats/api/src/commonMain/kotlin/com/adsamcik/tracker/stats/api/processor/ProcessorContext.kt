package com.adsamcik.tracker.stats.api.processor

import com.adsamcik.tracker.stats.api.value.EpochMs

/**
 * Context provided to a processor when it starts.
 *
 * @property checkpoint Saved state from a previous run (null on fresh start)
 * @property startTimestamp When tracking started
 * @property isResuming Whether this is resuming from a crash
 * @property sessionId Room-generated TrackerSession ID (used by consumers to query session data)
 */
data class ProcessorContext(
	val checkpoint: ByteArray? = null,
	val startTimestamp: EpochMs,
	val isResuming: Boolean = false,
	val sessionId: Long = 0L,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is ProcessorContext) return false
		return startTimestamp == other.startTimestamp &&
			isResuming == other.isResuming &&
			sessionId == other.sessionId &&
			checkpoint.contentEquals(other.checkpoint)
	}

	override fun hashCode(): Int {
		var result = checkpoint?.contentHashCode() ?: 0
		result = 31 * result + startTimestamp.hashCode()
		result = 31 * result + isResuming.hashCode()
		result = 31 * result + sessionId.hashCode()
		return result
	}
}
