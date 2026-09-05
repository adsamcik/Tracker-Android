package com.adsamcik.tracker.game.event

import android.content.Context
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.game.progression.SessionXpAwardResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
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
import io.mockk.confirmVerified
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
	private val trackingStartupGate = TestTrackingStartupGate()
	private val context: Context = mockk(relaxed = true)
	private val consumer = GameDomainEventConsumer(
		domainEventRepository,
		achievementEvaluationScheduler,
		progressionRepository,
		trackingStartupGate,
		context,
	)

	init {
		coEvery {
			progressionRepository.awardSessionXp(any(), any())
		} returns SessionXpAwardResult.COMPLETED
	}

	private fun sessionEndedEvent(sessionId: Long, timestampMs: Long) =
		DomainEvent.SessionEnded(
			timestampMs = EpochMs(timestampMs),
			processorId = "test-processor",
			sessionId = sessionId,
			totalDistance = DistanceM(0f),
			totalSteps = StepCount(0),
			duration = DurationMs(0L),
		)

	private fun dailySummaryUpdatedEvent(timestampMs: Long) =
		DomainEvent.DailySummaryUpdated(
			timestampMs = EpochMs(timestampMs),
			processorId = "test-processor",
			dayEpoch = 20_000L,
			totalDistance = DistanceM(0f),
			totalSteps = StepCount(20_000),
			totalDuration = DurationMs(0L),
			tripCount = 1,
		)

	@Nested
	@DisplayName("processUnconsumed")
	inner class ProcessUnconsumed {

		@Test
		fun `raw daily summary goal event is acknowledged without entering session XP`() = runTest {
			val event = UnconsumedEvent(dailySummaryUpdatedEvent(1_000L), 1L)
			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					GameDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(listOf(event), emptyList())

			consumer.processUnconsumed()

			coVerify(exactly = 0) { progressionRepository.awardSessionXp(any(), any()) }
			coVerify(exactly = 1) {
				domainEventRepository.markBatchConsumed(
					GameDomainEventConsumer.CONSUMER_ID,
					EpochMs(1_000L),
					1L,
				)
			}
		}

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
			coEvery {
				progressionRepository.awardSessionXp(
					failed.event as DomainEvent.SessionEnded,
					any(),
				)
			} throws
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
				progressionRepository.awardSessionXp(
					afterFailure.event as DomainEvent.SessionEnded,
					any(),
				)
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
			coEvery {
				progressionRepository.awardSessionXp(
					event.event as DomainEvent.SessionEnded,
					any(),
				)
			} throws
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
			coEvery {
				progressionRepository.awardSessionXp(
					retried.event as DomainEvent.SessionEnded,
					any(),
				)
			} coAnswers {
				if (failRetry) {
					SessionXpAwardResult.RETRY_NEEDED
				} else {
					SessionXpAwardResult.COMPLETED
				}
			}

			consumer.processUnconsumed()
			failRetry = false
			consumer.processUnconsumed()

			coVerify(exactly = 2) {
				progressionRepository.awardSessionXp(
					retried.event as DomainEvent.SessionEnded,
					any(),
				)
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

		@Test
		fun `session loaded before generation replacement is neither awarded nor acknowledged`() =
			runTest {
				val event = UnconsumedEvent(sessionEndedEvent(1L, 1_000L), 1L)
				coEvery {
					domainEventRepository.getUnconsumedBatchWithIds(
						GameDomainEventConsumer.CONSUMER_ID,
						DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
					)
				} returns listOf(event)
				trackingStartupGate.afterNextOperation = {
					trackingStartupGate.retireAndReopen()
				}

				consumer.processUnconsumed()

				coVerify(exactly = 0) {
					progressionRepository.awardSessionXp(any(), any())
				}
				coVerify(exactly = 1) {
					domainEventRepository.getUnconsumedBatchWithIds(
						GameDomainEventConsumer.CONSUMER_ID,
						DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
					)
				}
				confirmVerified(domainEventRepository)
				trackingStartupGate.operationGenerations shouldBe listOf(1L)
			}
	}

	private class TestTrackingStartupGate : TrackingStartupGate {
		private var ready = true
		private var generation = 1L
		var afterNextOperation: (suspend () -> Unit)? = null
		val operationGenerations = mutableListOf<Long>()

		override val isReady: Boolean
			get() = ready

		override val currentGeneration: Long
			get() = generation

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			if (ready) {
				TrackingStartupResult.Ready(false, 0L)
			} else {
				TrackingStartupResult.Blocked(
					TrackingStartupStage.STORAGE,
					"TEST_GATE_CLOSED",
				)
			}

		override suspend fun <T> withReadyGenerationOperation(
			expectedGeneration: Long,
			operation: suspend () -> T,
		): T? {
			operationGenerations += expectedGeneration
			val result = if (isReadyGeneration(expectedGeneration)) {
				operation()
			} else {
				null
			}
			afterNextOperation?.also { afterNextOperation = null }?.invoke()
			return result
		}

		fun retireAndReopen() {
			ready = false
			generation += 1L
			ready = true
		}
	}
}
