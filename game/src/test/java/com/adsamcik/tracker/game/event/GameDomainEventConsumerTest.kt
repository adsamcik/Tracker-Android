package com.adsamcik.tracker.game.event

import android.content.Context
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.scheduler.AchievementEvaluationScheduler
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

@DisplayName("GameDomainEventConsumer")
class GameDomainEventConsumerTest {

	private val domainEventRepository: DomainEventRepository = mockk(relaxed = true)
	private val achievementEvaluationScheduler: AchievementEvaluationScheduler = mockk(relaxed = true)
	private val context: Context = mockk(relaxed = true)
	private val mockPreferences: Preferences = mockk(relaxed = true)
	private val consumer = GameDomainEventConsumer(
		domainEventRepository,
		achievementEvaluationScheduler,
		context,
		mockPreferences,
	)

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	private fun stubSessionEndedInfrastructure() {
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
					GameDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returns emptyList()

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.getUnconsumedBatch(
					GameDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			}
			confirmVerified(domainEventRepository)
		}

		@Test
		fun `handles SessionEnded event and marks consumed`() = runTest {
			stubSessionEndedInfrastructure()
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
					GameDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(listOf(event), emptyList())

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					timestamp,
				)
			}
			io.mockk.verify(exactly = 1) { achievementEvaluationScheduler.scheduleEvaluation() }
		}

		@Test
		fun `ignores irrelevant event types and still marks consumed`() = runTest {
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
					GameDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(listOf(event), emptyList())

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					timestamp,
				)
			}
		}

		@Test
		fun `marks consumed with latest timestamp from multiple events`() = runTest {
			stubSessionEndedInfrastructure()
			val earlyTimestamp = EpochMs(1_700_000_000_000L)
			val lateTimestamp = EpochMs(1_700_000_005_000L)
			val events = listOf(
				DomainEvent.CellDiscovered(
					timestampMs = earlyTimestamp,
					processorId = "test-processor",
					cellToken = "cell-1",
					level = 3,
					centerLatE7 = 0,
					centerLonE7 = 0,
					quality = 0,
					seasonBit = 1,
				),
				DomainEvent.SessionEnded(
					timestampMs = lateTimestamp,
					processorId = "test-processor",
					sessionId = 10L,
					totalDistance = DistanceM(500f),
					totalSteps = StepCount(1000),
					duration = DurationMs(300_000L),
				),
			)

			coEvery {
				domainEventRepository.getUnconsumedBatch(
					GameDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(events, emptyList())

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					lateTimestamp,
				)
			}
		}

		@Test
		fun `drains multiple batches and marks each batch separately`() = runTest {
			stubSessionEndedInfrastructure()
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
					GameDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(firstBatch, secondBatch, emptyList())

			consumer.processUnconsumed()

			coVerifyOrder {
				domainEventRepository.markConsumed(GameDomainEventConsumer.CONSUMER_ID, firstTimestamp)
				domainEventRepository.markConsumed(GameDomainEventConsumer.CONSUMER_ID, secondTimestamp)
			}
		}
	}

	@Nested
	@DisplayName("companion")
	inner class Companion {

		@Test
		fun `consumer ID is game-module`() {
			GameDomainEventConsumer.CONSUMER_ID shouldBe "game-module"
		}
	}
}
