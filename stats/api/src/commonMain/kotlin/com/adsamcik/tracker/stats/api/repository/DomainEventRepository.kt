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
	 * Legacy id-less unconsumed batch fetch. Same-millisecond rows beyond [limit] are
	 * lost when paired with [markConsumed] because that ack writes [Long.MAX_VALUE]
	 * for `last_processed_id` and advances past every id at that timestamp.
	 *
	 * Use [getUnconsumedBatchWithIds] + [markBatchConsumed] for new consumers.
	 *
	 * **Migration note:** the IDE quick-fix maps this call to
	 * `getUnconsumedBatchWithIds(consumerId, limit).map { it.event }` which preserves
	 * the `List<DomainEvent>` return type. Callers that also need to ack via row id
	 * should migrate the full fetch + ack pair together:
	 * `getUnconsumedBatchWithIds` → process → `markBatchConsumed`.
	 */
	@Deprecated(
		message = "Same-ms event-skip risk when paired with timestamp-only ack. " +
			"Use getUnconsumedBatchWithIds + markBatchConsumed for precise composite-cursor consumption.",
		replaceWith = ReplaceWith("getUnconsumedBatchWithIds(consumerId, limit).map { it.event }"),
		level = DeprecationLevel.ERROR,
	)
	suspend fun getUnconsumedBatch(consumerId: String, limit: Int): List<DomainEvent>

	/**
	 * Fetch the next unconsumed batch paired with persisted row ids. Use with
	 * [markBatchConsumed] to advance the composite `(timestamp_ms, id)` cursor so
	 * events sharing a millisecond are never silently skipped.
	 */
	suspend fun getUnconsumedBatchWithIds(consumerId: String, limit: Int): List<UnconsumedEvent>

	/**
	 * Legacy timestamp-only ack. Pairs unsafely with [getUnconsumedBatch]: when a
	 * bounded batch leaves same-millisecond rows on the table, this ack writes
	 * [Long.MAX_VALUE] for `last_processed_id` and skips every leftover row at that
	 * timestamp.
	 *
	 * Use [markBatchConsumed] with the actual persisted id of the last event processed.
	 */
	@Deprecated(
		message = "Same-ms event-skip risk: ack writes Long.MAX_VALUE id sentinel, " +
			"skipping every same-ms event beyond the batch limit. " +
			"No safe drop-in replacement exists — migrate the full fetch+ack pair: " +
			"getUnconsumedBatchWithIds then markBatchConsumed with the last event's actual id.",
		level = DeprecationLevel.ERROR,
	)
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
