package com.adsamcik.tracker.game

import com.adsamcik.tracker.game.event.ExplorationDomainEventConsumer
import com.adsamcik.tracker.game.event.GameDomainEventConsumer
import com.adsamcik.tracker.game.repository.DefaultGameRepository
import com.adsamcik.tracker.game.session.GameFinalizationReconciler
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Game module initializer.
 * Achievement events (and other domain events emitted by the tracker pipeline)
 * are handled by [GameDomainEventConsumer]; this initializer wires up unconsumed-
 * event catchup plus a live observer that re-processes whenever new domain
 * events are persisted.
 */
class GameModuleInitializer @Inject constructor(
	@ApplicationScope private val appScope: CoroutineScope,
	private val dispatchers: DispatchersProvider,
	private val consumer: GameDomainEventConsumer,
	private val explorationConsumer: ExplorationDomainEventConsumer,
	private val domainEventRepository: DomainEventRepository,
	private val miniGameScoreDao: MiniGameScoreDao,
	private val gameRepository: DefaultGameRepository,
	private val trackingStartupGate: TrackingStartupGate,
) : ModuleInitializer {
	override val priority: Int = 30

	override fun initialize() {
		appScope.launch(dispatchers.io) {
			GameFinalizationReconciler(
				loadScores = miniGameScoreDao::getRecentForReconciliation,
				trackingStartupGate = trackingStartupGate,
				ensureRewardInsideAcceptedGeneration =
					gameRepository::ensureMiniGameRewardInsideAcceptedGeneration,
			).reconcile()
		}
		appScope.launch(dispatchers.default) {
			consumer.processUnconsumed()
			explorationConsumer.processUnconsumed()
			// Each consumer drains durable work. A newer invalidation must not cancel an accepted
			// transaction between its commit/acknowledgement and its mark-after-commit handoff.
			domainEventRepository.observeEvents(EpochMs(0L)).collect {
				consumer.processUnconsumed()
				explorationConsumer.processUnconsumed()
			}
		}
	}
}
