package com.adsamcik.tracker.game.event

import android.content.Context
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
import com.adsamcik.tracker.stats.api.scheduler.AchievementEvaluationScheduler
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GameDomainEventConsumer")
class GameDomainEventConsumerTest {
	private val domainEventRepository: DomainEventRepository = mockk(relaxed = true)
	private val achievementEvaluationScheduler: AchievementEvaluationScheduler = mockk(relaxed = true)
	private val progressionRepository: PlayerProgressionRepository = mockk(relaxed = true)
	private val context: Context = mockk(relaxed = true)
	private val consumer = GameDomainEventConsumer(
		domainEventRepository,
		achievementEvaluationScheduler,
		progressionRepository,
		context,
	)

	private fun sessionEndedEvent(sessionId: Long, timestampMs: Long) =
		DomainEvent.SessionEnded(
			timestampMs = EpochMs(timestampMs),
			processorId = "test-processor",
			sessionId = sessionId,
			totalDistance = DistanceM(0f),
			totalSteps = StepCount(0),
			duration = DurationMs(0L),
		)

	@Nested
	@DisplayName("processUnconsumed")
	inner class ProcessUnconsumed {

		@Test
		fun `stops at a handler failure and acknowledges only prior events`() = runTest {
			val first = UnconsumedEvent(sessionEndedEvent(1L, 1000L), 1L)
			val failed = UnconsumedEvent(sessionEndedEvent(2L, 2000L), 2L)
			val afterFailure = UnconsumedEvent(sessionEndedEvent(3L, 3000L), 3L)
			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					GameDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returns listOf(first, failed, afterFailure)
			coEvery { progressionRepository.awardSessionXp(failed.event as DomainEvent.SessionEnded) } throws
				IllegalStateException("XP unavailable")

			consumer.processUnconsumed()

			coVerifyOrder {
				domainEventRepository.markBatchConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					EpochMs(1000L),
					1L,
				)
			}
			coVerify(exactly = 0) {
				domainEventRepository.markBatchConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					EpochMs(2000L),
					2L,
				)
			}
			coVerify(exactly = 0) {
				domainEventRepository.markBatchConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					EpochMs(3000L),
					3L,
				)
			}
			coVerify(exactly = 0) {
				progressionRepository.awardSessionXp(afterFailure.event as DomainEvent.SessionEnded)
			}
		}

		@Test
		fun `propagates cancellation from a handler`() = runTest {
			val event = UnconsumedEvent(sessionEndedEvent(1L, 1000L), 1L)
			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					GameDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returns listOf(event)
			coEvery { progressionRepository.awardSessionXp(event.event as DomainEvent.SessionEnded) } throws
				CancellationException("cancelled")

			var caught = false
			try {
				consumer.processUnconsumed()
			} catch (_: CancellationException) {
				caught = true
			}

			caught shouldBe true
			coVerify(exactly = 0) {
				domainEventRepository.markBatchConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					EpochMs(1000L),
					1L,
				)
			}
		}

		@Test
		fun `acknowledges every successfully handled event`() = runTest {
			val first = UnconsumedEvent(sessionEndedEvent(1L, 1000L), 1L)
			val second = UnconsumedEvent(sessionEndedEvent(2L, 2000L), 2L)
			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					GameDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(listOf(first, second), emptyList())

			consumer.processUnconsumed()

			coVerifyOrder {
				domainEventRepository.markBatchConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					EpochMs(1000L),
					1L,
				)
				domainEventRepository.markBatchConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					EpochMs(2000L),
					2L,
				)
			}
		}

		@Test
		fun `retries and applies a previously failed event`() = runTest {
			val first = UnconsumedEvent(sessionEndedEvent(1L, 1000L), 1L)
			val retried = UnconsumedEvent(sessionEndedEvent(2L, 2000L), 2L)
			val afterRetry = UnconsumedEvent(sessionEndedEvent(3L, 3000L), 3L)
			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					GameDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(
				listOf(first, retried, afterRetry),
				listOf(retried, afterRetry),
				emptyList(),
			)
			var failRetry = true
			coEvery { progressionRepository.awardSessionXp(retried.event as DomainEvent.SessionEnded) } coAnswers {
				if (failRetry) throw IllegalStateException("XP unavailable")
			}

			consumer.processUnconsumed()
			failRetry = false
			consumer.processUnconsumed()

			coVerify(exactly = 2) {
				progressionRepository.awardSessionXp(retried.event as DomainEvent.SessionEnded)
			}
			coVerifyOrder {
				domainEventRepository.markBatchConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					EpochMs(1000L),
					1L,
				)
				domainEventRepository.markBatchConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					EpochMs(2000L),
					2L,
				)
				domainEventRepository.markBatchConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					EpochMs(3000L),
					3L,
				)
			}
		}
	}
}
