package com.adsamcik.tracker.points

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.points.event.PointsDomainEventConsumer
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PointsConsumerEntryPoint {
	fun pointsDomainEventConsumer(): PointsDomainEventConsumer
	fun domainEventRepository(): DomainEventRepository
}

/**
 * Initializes domain event consumer for points.
 * Points are now awarded via [PointsDomainEventConsumer] on SessionEnded events.
 */
class PointsInitializer : ModuleInitializer {
	private val scope = CoroutineScope(SupervisorJob() + DefaultDispatchersProvider.default)

	override fun initialize(context: Context) {
		initializeDomainEventConsumer(context)
	}

	private fun initializeDomainEventConsumer(context: Context) {
		val entryPoint = EntryPointAccessors.fromApplication(
			context,
			PointsConsumerEntryPoint::class.java,
		)
		val consumer = entryPoint.pointsDomainEventConsumer()
		val domainEventRepository = entryPoint.domainEventRepository()
		scope.launch {
			consumer.processUnconsumed()
			domainEventRepository.observeEvents(EpochMs(0L)).collectLatest {
				consumer.processUnconsumed()
			}
		}
	}
}
