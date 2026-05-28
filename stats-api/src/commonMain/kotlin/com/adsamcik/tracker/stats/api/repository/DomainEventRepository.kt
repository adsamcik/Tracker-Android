package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.flow.Flow

/**
 * A persisted [DomainEvent] paired with its row id. Consumers ack via
 * [DomainEventRepository.markBatchConsumed] using the LAST returned event's
 * `(timestampMs, persistedId)` pair so the composite cursor advances precisely.
 */
data class UnconsumedEvent(
	val event: DomainEvent,
	val persistedId: Long,
)

interface DomainEventRepository {
	suspend fun persist(events: List<DomainEvent>)

	/**
	 * Observes a bounded latest-event window after [since].
	 *
	 * This stream is intended as a wake/refresh signal for consumers that should re-check
	 * recent activity. Authoritative consumption should use [getUnconsumedBatchWithIds]
	 * with [markBatchConsumed] so events are processed by composite cursor state.
	 */
	fun observeEvents(since: EpochMs): Flow<List<DomainEvent>>

	/**
	 * Legacy id-less unconsumed batch fetch. Kept for backward compatibility with code
	 * that doesn't need to ack via composite cursor. Prefer [getUnconsumedBatchWithIds]
	 * for new consumers — it returns persisted ids so [markBatchConsumed] can advance
	 * the cursor precisely past the last processed event.
	 */
	suspend fun getUnconsumedBatch(consumerId: String, limit: Int): List<DomainEvent>

	/**
	 * Fetch the next unconsumed batch paired with persisted row ids. Use with
	 * [markBatchConsumed] to advance the composite `(timestamp_ms, id)` cursor so
	 * events sharing a millisecond are never silently skipped.
	 */
	suspend fun getUnconsumedBatchWithIds(consumerId: String, limit: Int): List<UnconsumedEvent>

	/**
	 * Legacy timestamp-only ack. Equivalent to `markBatchConsumed(consumerId, ts, Long.MAX_VALUE)`
	 * for back-compat — but new consumers should call [markBatchConsumed] with the actual
	 * persisted id of the last event processed.
	 */
	suspend fun markConsumed(consumerId: String, upToTimestamp: EpochMs)

	/**
	 * Advance the composite cursor to `(upToTimestamp, upToEventId)` — inclusive bound.
	 * The next [getUnconsumedBatchWithIds] will return events strictly after this point
	 * using `(timestamp_ms > upToTimestamp) OR (timestamp_ms = upToTimestamp AND id > upToEventId)`.
	 */
	suspend fun markBatchConsumed(consumerId: String, upToTimestamp: EpochMs, upToEventId: Long)

	companion object {
		const val DEFAULT_UNCONSUMED_BATCH_SIZE: Int = 100
	}
}
