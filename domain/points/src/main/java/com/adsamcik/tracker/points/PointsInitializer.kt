package com.adsamcik.tracker.points

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.points.event.PointsDomainEventConsumer
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Initializes domain event consumer for points.
 * Points are now awarded via [PointsDomainEventConsumer] on SessionEnded events.
 */
class PointsInitializer @Inject constructor(
	@ApplicationScope private val appScope: CoroutineScope,
	private val dispatchers: DispatchersProvider,
	private val consumer: PointsDomainEventConsumer,
	private val domainEventRepository: DomainEventRepository,
) : ModuleInitializer {
	override val priority: Int = 40

	override fun initialize() {
		appScope.launch(dispatchers.default) {
			consumer.processUnconsumed()
			domainEventRepository.observeEvents(EpochMs(0L)).collectLatest {
				consumer.processUnconsumed()
			}
		}
	}
}
