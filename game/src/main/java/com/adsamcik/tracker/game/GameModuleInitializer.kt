package com.adsamcik.tracker.game

import android.content.Context
import com.adsamcik.tracker.game.event.ExplorationDomainEventConsumer
import com.adsamcik.tracker.game.event.GameDomainEventConsumer
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import com.adsamcik.tracker.shared.utils.module.TrackerSessionChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Game module initializer.
 * Challenge events are now handled by [GameDomainEventConsumer] via domain events.
 */
class GameModuleInitializer @Inject constructor(
	@ApplicationContext private val context: Context,
	@ApplicationScope private val appScope: CoroutineScope,
	private val dispatchers: DispatchersProvider,
	private val consumer: GameDomainEventConsumer,
	private val explorationConsumer: ExplorationDomainEventConsumer,
	private val trackerSessionChannel: TrackerSessionChannel,
	private val domainEventRepository: DomainEventRepository,
) : ModuleInitializer {
	override val priority: Int = 30

	override fun initialize() {
		initializeGoals()
		appScope.launch(dispatchers.default) {
			consumer.processUnconsumed()
			explorationConsumer.processUnconsumed()
			domainEventRepository.observeEvents(EpochMs(0L)).collectLatest {
				consumer.processUnconsumed()
				explorationConsumer.processUnconsumed()
			}
		}
	}

	private fun initializeGoals() {
		GoalTracker.initialize(context, trackerSessionChannel)
	}
}
