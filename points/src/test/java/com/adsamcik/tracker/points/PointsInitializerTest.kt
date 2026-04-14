package com.adsamcik.tracker.points

import com.adsamcik.tracker.points.event.PointsDomainEventConsumer
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("PointsInitializer")
class PointsInitializerTest {

	private val consumer: PointsDomainEventConsumer = mockk(relaxed = true)
	private val domainEventRepository: DomainEventRepository = mockk(relaxed = true)

	@Nested
	inner class Priority {
		@Test
		fun `priority is 40`() = runTest {
			val dispatcher = UnconfinedTestDispatcher(testScheduler)
			val dispatchers = testDispatchers(dispatcher)
			val initializer = PointsInitializer(
				appScope = backgroundScope,
				dispatchers = dispatchers,
				consumer = consumer,
				domainEventRepository = domainEventRepository,
			)
			initializer.priority shouldBe 40
		}
	}

	@Nested
	inner class Initialize {
		@Test
		fun `calls processUnconsumed on initialization`() = runTest {
			val dispatcher = UnconfinedTestDispatcher(testScheduler)
			val dispatchers = testDispatchers(dispatcher)
			val eventFlow = MutableSharedFlow<List<DomainEvent>>()
			every { domainEventRepository.observeEvents(EpochMs(0L)) } returns eventFlow

			val initializer = PointsInitializer(
				appScope = backgroundScope,
				dispatchers = dispatchers,
				consumer = consumer,
				domainEventRepository = domainEventRepository,
			)

			initializer.initialize()
			advanceUntilIdle()

			coVerify(atLeast = 1) { consumer.processUnconsumed() }
		}

		@Test
		fun `calls processUnconsumed again when events are emitted`() = runTest {
			val dispatcher = UnconfinedTestDispatcher(testScheduler)
			val dispatchers = testDispatchers(dispatcher)
			val eventFlow = MutableSharedFlow<List<DomainEvent>>()
			every { domainEventRepository.observeEvents(EpochMs(0L)) } returns eventFlow

			val initializer = PointsInitializer(
				appScope = backgroundScope,
				dispatchers = dispatchers,
				consumer = consumer,
				domainEventRepository = domainEventRepository,
			)

			initializer.initialize()
			advanceUntilIdle()

			eventFlow.emit(emptyList())
			advanceUntilIdle()

			coVerify(atLeast = 2) { consumer.processUnconsumed() }
		}

		@Test
		fun `observes events from epoch zero`() = runTest {
			val dispatcher = UnconfinedTestDispatcher(testScheduler)
			val dispatchers = testDispatchers(dispatcher)
			val eventFlow = MutableSharedFlow<List<DomainEvent>>()
			every { domainEventRepository.observeEvents(EpochMs(0L)) } returns eventFlow

			val initializer = PointsInitializer(
				appScope = backgroundScope,
				dispatchers = dispatchers,
				consumer = consumer,
				domainEventRepository = domainEventRepository,
			)

			initializer.initialize()
			advanceUntilIdle()

			coVerify { domainEventRepository.observeEvents(EpochMs(0L)) }
		}
	}

	private fun testDispatchers(dispatcher: CoroutineDispatcher) =
		object : DispatchersProvider {
			override val io: CoroutineDispatcher = dispatcher
			override val default: CoroutineDispatcher = dispatcher
			override val main: CoroutineDispatcher = dispatcher
			override val unconfined: CoroutineDispatcher = dispatcher
		}
}
