package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.flow.Flow

interface DomainEventRepository {
	suspend fun persist(events: List<DomainEvent>)
	fun observeEvents(since: EpochMs): Flow<List<DomainEvent>>
	suspend fun getUnconsumed(consumerId: String): List<DomainEvent>
	suspend fun markConsumed(consumerId: String, upToTimestamp: EpochMs)
}
