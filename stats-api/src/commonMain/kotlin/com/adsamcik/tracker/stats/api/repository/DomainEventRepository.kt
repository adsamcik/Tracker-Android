package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.flow.Flow

interface DomainEventRepository {
	suspend fun persist(events: List<DomainEvent>)

	/**
	 * Observes a bounded latest-event window after [since].
	 *
	 * This stream is intended as a wake/refresh signal for consumers that should re-check
	 * recent activity. Authoritative consumption should use [getUnconsumedBatch] with
	 * [markConsumed] so events are processed by cursor/batch state instead of this latest window.
	 */
	fun observeEvents(since: EpochMs): Flow<List<DomainEvent>>
	suspend fun getUnconsumedBatch(consumerId: String, limit: Int): List<DomainEvent>
	suspend fun markConsumed(consumerId: String, upToTimestamp: EpochMs)

	companion object {
		const val DEFAULT_UNCONSUMED_BATCH_SIZE: Int = 100
	}
}
