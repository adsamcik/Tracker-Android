package com.adsamcik.tracker.game

import com.adsamcik.tracker.game.event.ExplorationDomainEventConsumer
import com.adsamcik.tracker.game.event.GameDomainEventConsumer
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.testing.TestDispatchersProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GameModuleInitializerTest {
	@Test
	fun `new event emission does not cancel accepted consumer handoff`() = runTest {
		val consumer = mockk<GameDomainEventConsumer>()
		val explorationConsumer = mockk<ExplorationDomainEventConsumer>()
		val events = MutableSharedFlow<List<DomainEvent>>(extraBufferCapacity = 1)
		val repository = mockk<DomainEventRepository>()
		val gate = mockk<TrackingStartupGate>()
		val releaseHandoff = CompletableDeferred<Unit>()
		val completed = mutableListOf<String>()
		var drains = 0
		every { repository.observeEvents(EpochMs(0L)) } returns events
		every { gate.currentGeneration } returns 1L
		coEvery { gate.reconcile(any()) } returns TrackingStartupResult.Blocked(
			TrackingStartupStage.STORAGE,
			"TEST_GATE_CLOSED",
		)
		coEvery { consumer.processUnconsumed() } coAnswers {
			drains += 1
			if (drains == 2) {
				releaseHandoff.await()
			}
			completed += "game-$drains"
		}
		coEvery { explorationConsumer.processUnconsumed() } coAnswers {
			completed += "exploration-$drains"
		}
		mockkObject(GoalTracker)
		every { GoalTracker.initialize(any(), any(), any()) } returns Unit
		try {
			GameModuleInitializer(
				context = mockk(),
				appScope = backgroundScope,
				dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
				consumer = consumer,
				explorationConsumer = explorationConsumer,
				trackerStateReader = mockk(),
				domainEventRepository = repository,
				goalsSettingsRepository = mockk(),
				miniGameScoreDao = mockk(),
				gameRepository = mockk(),
				trackingStartupGate = gate,
			).initialize()
			runCurrent()
			events.emit(emptyList())
			runCurrent()
			events.emit(emptyList())
			runCurrent()
			assertEquals(listOf("game-1", "exploration-1"), completed)

			releaseHandoff.complete(Unit)
			runCurrent()
			assertEquals(
				listOf("game-1", "exploration-1", "game-2", "exploration-2", "game-3", "exploration-3"),
				completed,
			)
		} finally {
			unmockkObject(GoalTracker)
		}
	}
}
