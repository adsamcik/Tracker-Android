package com.adsamcik.tracker.game

import android.content.Context
import com.adsamcik.tracker.game.event.ExplorationDomainEventConsumer
import com.adsamcik.tracker.game.event.GameDomainEventConsumer
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.game.goals.NewDayGoalWorker
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import com.adsamcik.tracker.shared.utils.module.TrackerSessionChannel

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GameConsumerEntryPoint {
	fun gameDomainEventConsumer(): GameDomainEventConsumer
	fun explorationDomainEventConsumer(): ExplorationDomainEventConsumer
	fun trackerSessionChannel(): TrackerSessionChannel
	fun domainEventRepository(): DomainEventRepository
}

/**
 * Game module initializer.
 * Challenge events are now handled by [GameDomainEventConsumer] via domain events.
 */
@Suppress("unused")
class GameModuleInitializer : ModuleInitializer {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	override fun initialize(context: Context) {
		initializeGoals(context)
		initializeDomainEventConsumer(context)
	}

	private fun initializeGoals(context: Context) {
		val entryPoint = EntryPointAccessors.fromApplication(
			context,
			GameConsumerEntryPoint::class.java,
		)
		GoalTracker.initialize(context, entryPoint.trackerSessionChannel())
		NewDayGoalWorker.ensureScheduled(context)
	}

	private fun initializeDomainEventConsumer(context: Context) {
		val entryPoint = EntryPointAccessors.fromApplication(
			context,
			GameConsumerEntryPoint::class.java,
		)
		val consumer = entryPoint.gameDomainEventConsumer()
		val explorationConsumer = entryPoint.explorationDomainEventConsumer()
		val domainEventRepository = entryPoint.domainEventRepository()
		scope.launch {
			consumer.processUnconsumed()
			explorationConsumer.processUnconsumed()
			domainEventRepository.observeEvents(EpochMs(0L)).collectLatest {
				consumer.processUnconsumed()
				explorationConsumer.processUnconsumed()
			}
		}
	}
}
