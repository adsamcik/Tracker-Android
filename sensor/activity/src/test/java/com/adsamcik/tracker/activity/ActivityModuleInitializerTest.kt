package com.adsamcik.tracker.activity

import android.content.Context
import com.adsamcik.tracker.activity.event.ActivityDomainEventConsumer
import com.adsamcik.tracker.activity.receiver.ActivityCallbackRetryOwner
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ActivityModuleInitializer")
class ActivityModuleInitializerTest {
	private val context: Context = mockk(relaxed = true) {
		every { getString(any()) } returns "activity"
	}
	private val activityDao: ActivityDao = mockk(relaxed = true)
	private val consumer: ActivityDomainEventConsumer = mockk(relaxed = true)
	private val domainEventRepository: DomainEventRepository = mockk(relaxed = true)
	private val callbackRetryOwner: ActivityCallbackRetryOwner = mockk(relaxed = true)

	@Nested
	inner class Priority {
		@Test
		fun `priority is 10`() = runTest {
			val initializer = initializer(MutableSharedFlow(), UnconfinedTestDispatcher(testScheduler))

			initializer.priority shouldBe 10
		}
	}

	@Nested
	inner class Initialize {
		@Test
		fun `drains backlog on initialization`() = runTest {
			val eventFlow = MutableSharedFlow<List<DomainEvent>>()
			val initializer = initializer(eventFlow, UnconfinedTestDispatcher(testScheduler))

			initializer.initialize()
			advanceUntilIdle()

			coVerify(atLeast = 1) { consumer.processUnconsumed() }
		}

		@Test
		fun `observes live domain events and drains backlog again`() = runTest {
			val eventFlow = MutableSharedFlow<List<DomainEvent>>()
			val initializer = initializer(eventFlow, UnconfinedTestDispatcher(testScheduler))

			initializer.initialize()
			advanceUntilIdle()
			eventFlow.emit(emptyList())
			advanceUntilIdle()

			coVerify(atLeast = 2) { consumer.processUnconsumed() }
			coVerify { domainEventRepository.observeEvents(EpochMs(0L)) }
		}
	}

	private fun TestScope.initializer(
		eventFlow: MutableSharedFlow<List<DomainEvent>>,
		dispatcher: CoroutineDispatcher,
	): ActivityModuleInitializer {
		coEvery { activityDao.insert(any<Collection<SessionActivity>>()) } returns emptyList()
		every { domainEventRepository.observeEvents(EpochMs(0L)) } returns eventFlow
		return ActivityModuleInitializer(
			context = context,
			appScope = backgroundScope,
			dispatchers = testDispatchers(dispatcher),
			activityDao = activityDao,
			consumer = consumer,
			domainEventRepository = domainEventRepository,
			callbackRetryOwner = callbackRetryOwner,
		)
	}

	private fun testDispatchers(dispatcher: CoroutineDispatcher) =
		object : DispatchersProvider {
			override val io: CoroutineDispatcher = dispatcher
			override val default: CoroutineDispatcher = dispatcher
			override val main: CoroutineDispatcher = dispatcher
			override val unconfined: CoroutineDispatcher = dispatcher
		}
}
