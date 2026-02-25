package com.adsamcik.tracker.game

import android.content.Context
import com.adsamcik.tracker.game.event.GameDomainEventConsumer
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.game.goals.NewDayGoalWorker
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
interface GameConsumerEntryPoint {
	fun gameDomainEventConsumer(): GameDomainEventConsumer
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
		GoalTracker.initialize(context)
		NewDayGoalWorker.ensureScheduled(context)
	}

	private fun initializeDomainEventConsumer(context: Context) {
		val entryPoint = EntryPointAccessors.fromApplication(
			context,
			GameConsumerEntryPoint::class.java,
		)
		val consumer = entryPoint.gameDomainEventConsumer()
		scope.launch { consumer.processUnconsumed() }
	}
}
