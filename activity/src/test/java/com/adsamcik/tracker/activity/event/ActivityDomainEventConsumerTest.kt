package com.adsamcik.tracker.activity.event

import android.content.Context
import androidx.work.WorkManager
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ActivityDomainEventConsumer")
class ActivityDomainEventConsumerTest {

	private val domainEventRepository: DomainEventRepository = mockk(relaxed = true)
	private val context: Context = mockk(relaxed = true)
	private val consumer = ActivityDomainEventConsumer(domainEventRepository, context)

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	private fun stubWorkManager() {
		mockkObject(WorkManager)
		every { WorkManager.getInstance(context) } returns mockk(relaxed = true)
	}

	@Nested
	@DisplayName("processUnconsumed")
	inner class ProcessUnconsumed {

		@Test
		fun `does nothing when no events`() = runTest {
			coEvery {
				domainEventRepository.getUnconsumed(ActivityDomainEventConsumer.CONSUMER_ID)
			} returns emptyList()

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.getUnconsumed(ActivityDomainEventConsumer.CONSUMER_ID)
			}
			confirmVerified(domainEventRepository)
		}

		@Test
		fun `handles SessionEnded and marks consumed`() = runTest {
			stubWorkManager()
			val timestamp = EpochMs(1_700_000_000_000L)
			val event = DomainEvent.SessionEnded(
				timestampMs = timestamp,
				processorId = "test-processor",
				sessionId = 42L,
				totalDistance = DistanceM(1500f),
				totalSteps = StepCount(3000),
				duration = DurationMs(600_000L),
			)

			coEvery {
				domainEventRepository.getUnconsumed(ActivityDomainEventConsumer.CONSUMER_ID)
			} returns listOf(event)

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markConsumed(
					ActivityDomainEventConsumer.CONSUMER_ID,
					timestamp,
				)
			}
		}

		@Test
		fun `ignores non-SessionEnded events and still marks consumed`() = runTest {
			val timestamp = EpochMs(1_700_000_000_000L)
			val event = DomainEvent.CellDiscovered(
				timestampMs = timestamp,
				processorId = "test-processor",
				cellToken = "cell-xyz",
				level = 7,
			)

			coEvery {
				domainEventRepository.getUnconsumed(ActivityDomainEventConsumer.CONSUMER_ID)
			} returns listOf(event)

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markConsumed(
					ActivityDomainEventConsumer.CONSUMER_ID,
					timestamp,
				)
			}
		}

		@Test
		fun `marks consumed with latest timestamp from multiple events`() = runTest {
			stubWorkManager()
			val earlyTimestamp = EpochMs(1_700_000_000_000L)
			val lateTimestamp = EpochMs(1_700_000_005_000L)
			val events = listOf(
				DomainEvent.CellDiscovered(
					timestampMs = lateTimestamp,
					processorId = "test-processor",
					cellToken = "cell-1",
					level = 3,
				),
				DomainEvent.SessionEnded(
					timestampMs = earlyTimestamp,
					processorId = "test-processor",
					sessionId = 10L,
					totalDistance = DistanceM(500f),
					totalSteps = StepCount(1000),
					duration = DurationMs(300_000L),
				),
			)

			coEvery {
				domainEventRepository.getUnconsumed(ActivityDomainEventConsumer.CONSUMER_ID)
			} returns events

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markConsumed(
					ActivityDomainEventConsumer.CONSUMER_ID,
					lateTimestamp,
				)
			}
		}
	}

	@Nested
	@DisplayName("companion")
	inner class Companion {

		@Test
		fun `consumer ID is activity-module`() {
			ActivityDomainEventConsumer.CONSUMER_ID shouldBe "activity-module"
		}
	}
}
