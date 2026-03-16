package com.adsamcik.tracker.points.event

import android.content.Context
import androidx.work.WorkManager
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PointsDomainEventConsumer")
class PointsDomainEventConsumerTest {

	private val domainEventRepository: DomainEventRepository = mockk(relaxed = true)
	private val context: Context = mockk(relaxed = true)
	private val consumer = PointsDomainEventConsumer(domainEventRepository, context)

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	private fun stubWorkManager() {
		mockkObject(WorkManager)
		every { WorkManager.getInstance(context) } returns mockk(relaxed = true)

		mockkObject(Logger)
		every { Logger.log(any()) } returns Unit
	}

	@Nested
	@DisplayName("processUnconsumed")
	inner class ProcessUnconsumed {

		@Test
		fun `does nothing when no events`() = runTest {
			coEvery {
				domainEventRepository.getUnconsumedBatch(
					PointsDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returns emptyList()

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.getUnconsumedBatch(
					PointsDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
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
				domainEventRepository.getUnconsumedBatch(
					PointsDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(listOf(event), emptyList())

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markConsumed(
					PointsDomainEventConsumer.CONSUMER_ID,
					timestamp,
				)
			}
		}

		@Test
		fun `skips SessionEnded with invalid sessionId`() = runTest {
			val timestamp = EpochMs(1_700_000_000_000L)
			val event = DomainEvent.SessionEnded(
				timestampMs = timestamp,
				processorId = "test-processor",
				sessionId = 0L,
				totalDistance = DistanceM(0f),
				totalSteps = StepCount(0),
				duration = DurationMs(0L),
			)

			coEvery {
				domainEventRepository.getUnconsumedBatch(
					PointsDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(listOf(event), emptyList())

			// sessionId <= 0 causes early return in onSessionEnded (no WorkManager call),
			// but markConsumed is still called after event loop completes
			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markConsumed(
					PointsDomainEventConsumer.CONSUMER_ID,
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
				cellToken = "cell-abc",
				level = 5,
				centerLatE7 = 0,
				centerLonE7 = 0,
				quality = 0,
				seasonBit = 1,
			)

			coEvery {
				domainEventRepository.getUnconsumedBatch(
					PointsDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(listOf(event), emptyList())

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markConsumed(
					PointsDomainEventConsumer.CONSUMER_ID,
					timestamp,
				)
			}
		}

		@Test
		fun `drains multiple batches and marks each batch separately`() = runTest {
			stubWorkManager()
			val firstTimestamp = EpochMs(1_700_000_000_000L)
			val secondTimestamp = EpochMs(1_700_000_005_000L)
			val firstBatch = listOf(
				DomainEvent.SessionEnded(
					timestampMs = firstTimestamp,
					processorId = "test-processor",
					sessionId = 11L,
					totalDistance = DistanceM(500f),
					totalSteps = StepCount(1000),
					duration = DurationMs(300_000L),
				),
			)
			val secondBatch = listOf(
				DomainEvent.CellDiscovered(
					timestampMs = secondTimestamp,
					processorId = "test-processor",
					cellToken = "cell-2",
					level = 3,
					centerLatE7 = 0,
					centerLonE7 = 0,
					quality = 0,
					seasonBit = 1,
				),
			)

			coEvery {
				domainEventRepository.getUnconsumedBatch(
					PointsDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(firstBatch, secondBatch, emptyList())

			consumer.processUnconsumed()

			coVerifyOrder {
				domainEventRepository.markConsumed(PointsDomainEventConsumer.CONSUMER_ID, firstTimestamp)
				domainEventRepository.markConsumed(PointsDomainEventConsumer.CONSUMER_ID, secondTimestamp)
			}
		}
	}

	@Nested
	@DisplayName("companion")
	inner class Companion {

		@Test
		fun `consumer ID is points-module`() {
			PointsDomainEventConsumer.CONSUMER_ID shouldBe "points-module"
		}
	}
}
