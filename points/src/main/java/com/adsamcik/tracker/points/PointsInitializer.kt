package com.adsamcik.tracker.points

import android.content.Context
import com.adsamcik.tracker.points.event.PointsDomainEventConsumer
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PointsConsumerEntryPoint {
	fun pointsDomainEventConsumer(): PointsDomainEventConsumer
}

/**
 * Initializes domain event consumer for points.
 * Points are now awarded via [PointsDomainEventConsumer] on SessionEnded events.
 */
class PointsInitializer : ModuleInitializer {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	override fun initialize(context: Context) {
		initializeDomainEventConsumer(context)
	}

	private fun initializeDomainEventConsumer(context: Context) {
		val entryPoint = EntryPointAccessors.fromApplication(
			context,
			PointsConsumerEntryPoint::class.java,
		)
		val consumer = entryPoint.pointsDomainEventConsumer()
		scope.launch { consumer.processUnconsumed() }
	}
}
