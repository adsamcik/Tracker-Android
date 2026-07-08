package com.adsamcik.tracker.game

import android.content.Context
import com.adsamcik.tracker.game.event.ExplorationDomainEventConsumer
import com.adsamcik.tracker.game.event.GameDomainEventConsumer
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import com.adsamcik.tracker.shared.utils.module.TrackerSessionChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.scheduler.AchievementEvaluationScheduler
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Game module initializer.
 * Achievement events (and other domain events emitted by the tracker pipeline)
 * are handled by [GameDomainEventConsumer]; this initializer wires up unconsumed-
 * event catchup plus a live observer that re-processes whenever new domain
 * events are persisted.
 */
class GameModuleInitializer @Inject constructor(
	@ApplicationContext private val context: Context,
	@ApplicationScope private val appScope: CoroutineScope,
	private val dispatchers: DispatchersProvider,
	private val consumer: GameDomainEventConsumer,
	private val explorationConsumer: ExplorationDomainEventConsumer,
	private val trackerSessionChannel: TrackerSessionChannel,
	private val domainEventRepository: DomainEventRepository,
	private val progressionRepository: PlayerProgressionRepository,
	private val achievementScheduler: AchievementEvaluationScheduler,
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
		GoalTracker.initialize(context, trackerSessionChannel, progressionRepository, achievementScheduler)
	}
}
