package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.flow.Flow

/**
 * A persisted [DomainEvent] paired with its row id. Consumers ack via
 * [DomainEventRepository.markBatchConsumed] using the LAST returned event's
 * [persistedId]. The timestamp is also recorded for retention safety.
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
	 * with [markBatchConsumed] so events are processed by persisted id.
	 */
	fun observeEvents(since: EpochMs): Flow<List<DomainEvent>>

	/**
	 * Fetch the next unconsumed batch in persisted-id order. Use with
	 * [markBatchConsumed] so events inserted later are delivered even when their
	 * event timestamps precede previously consumed events.
	 */
	suspend fun getUnconsumedBatchWithIds(consumerId: String, limit: Int): List<UnconsumedEvent>

	/**
	 * Advance the inclusive delivery cursor to [upToEventId]. [upToTimestamp] is retained
	 * for purge safety but does not participate in delivery ordering. The next
	 * [getUnconsumedBatchWithIds] returns events with a strictly larger persisted id.
	 */
	suspend fun markBatchConsumed(consumerId: String, upToTimestamp: EpochMs, upToEventId: Long)

	companion object {
		const val DEFAULT_UNCONSUMED_BATCH_SIZE: Int = 100
	}
}
