package com.adsamcik.tracker.points.event

import android.content.Context
import androidx.work.WorkManager
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
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
	private val appDatabase = mockk<com.adsamcik.tracker.shared.base.database.AppDatabase>(relaxed = true)
	private val pointsDatabase = mockk<com.adsamcik.tracker.points.database.PointsDatabase>(relaxed = true)
	private val consumer = PointsDomainEventConsumer(domainEventRepository, context,
		javax.inject.Provider { appDatabase }, javax.inject.Provider { pointsDatabase },
		object : com.adsamcik.tracker.shared.base.startup.TrackingStartupGate {
			override val isReady = true
			override val currentGeneration = 1L
			override suspend fun reconcile(retryFailedStorage: Boolean) =
				com.adsamcik.tracker.shared.base.startup.TrackingStartupResult.Ready(false, 0)
			override suspend fun <T> withReadyGenerationOperation(expectedGeneration: Long, operation: suspend () -> T): T = operation()
		})

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	private fun stubWorkManager() {
		coEvery { appDatabase.sourceEvidenceStateDao().get() } returns
			com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState()
		mockkObject(WorkManager)
		every { WorkManager.getInstance(context) } returns mockk(relaxed = true)
	}

	@Nested
	@DisplayName("processUnconsumed")
	inner class ProcessUnconsumed {

		@Test
		fun `does nothing when no events`() = runTest {
			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					PointsDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returns emptyList()

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.getUnconsumedBatchWithIds(
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
			val persistedId = 7L
			val event = DomainEvent.SessionEnded(
				timestampMs = timestamp,
				processorId = "test-processor",
				sessionId = 42L,
				totalDistance = DistanceM(1500f),
				totalSteps = StepCount(3000),
				duration = DurationMs(600_000L),
			)

			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					PointsDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(
				listOf(UnconsumedEvent(event, persistedId)),
				emptyList(),
			)

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markBatchConsumed(
					PointsDomainEventConsumer.CONSUMER_ID,
					timestamp,
					persistedId,
				)
			}
		}

		@Test
		fun `skips SessionEnded with invalid sessionId`() = runTest {
			val timestamp = EpochMs(1_700_000_000_000L)
			val persistedId = 8L
			val event = DomainEvent.SessionEnded(
				timestampMs = timestamp,
				processorId = "test-processor",
				sessionId = 0L,
				totalDistance = DistanceM(0f),
				totalSteps = StepCount(0),
				duration = DurationMs(0L),
			)

			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					PointsDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(
				listOf(UnconsumedEvent(event, persistedId)),
				emptyList(),
			)

			// sessionId <= 0 causes early return in onSessionEnded (no WorkManager call),
			// but markBatchConsumed is still called after event loop completes
			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markBatchConsumed(
					PointsDomainEventConsumer.CONSUMER_ID,
					timestamp,
					persistedId,
				)
			}
		}

		@Test
		fun `ignores non-SessionEnded events and still marks consumed`() = runTest {
			val timestamp = EpochMs(1_700_000_000_000L)
			val persistedId = 9L
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
				domainEventRepository.getUnconsumedBatchWithIds(
					PointsDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(
				listOf(UnconsumedEvent(event, persistedId)),
				emptyList(),
			)

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markBatchConsumed(
					PointsDomainEventConsumer.CONSUMER_ID,
					timestamp,
					persistedId,
				)
			}
		}

		@Test
		fun `drains multiple batches and marks each batch separately`() = runTest {
			stubWorkManager()
			val firstTimestamp = EpochMs(1_700_000_000_000L)
			val secondTimestamp = EpochMs(1_700_000_005_000L)
			val firstBatch = listOf(
				UnconsumedEvent(
					DomainEvent.SessionEnded(
						timestampMs = firstTimestamp,
						processorId = "test-processor",
						sessionId = 11L,
						totalDistance = DistanceM(500f),
						totalSteps = StepCount(1000),
						duration = DurationMs(300_000L),
					),
					persistedId = 11L,
				),
			)
			val secondBatch = listOf(
				UnconsumedEvent(
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
					persistedId = 12L,
				),
			)

			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					PointsDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(firstBatch, secondBatch, emptyList())

			consumer.processUnconsumed()

			coVerifyOrder {
				domainEventRepository.markBatchConsumed(PointsDomainEventConsumer.CONSUMER_ID, firstTimestamp, 11L)
				domainEventRepository.markBatchConsumed(PointsDomainEventConsumer.CONSUMER_ID, secondTimestamp, 12L)
			}
		}
	}

	@Test
    fun `restored points receipts requeue with the current job fence and clear drops old identities`() = runTest {
        var state = com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState(
            collectedDataEpoch = 7, restoredDataEpochs = "0")
        coEvery { appDatabase.sourceEvidenceStateDao().get() } coAnswers { state }
        val old = com.adsamcik.tracker.points.data.PointsAwarded(id = 1, time = 10,
            value = com.adsamcik.tracker.points.data.Points(1.0), source = com.adsamcik.tracker.points.data.AwardSource.SESSION,
            effectKey = "recording:0:42", effectRevision = 1, effectValueMicros = 1_000_000)
        val unrelated = old.copy(id = 2, effectKey = "recording:4:43")
        coEvery { pointsDatabase.pointsAwardedDao().sessionEffectPage(0, 100) } returns listOf(old, unrelated)
        mockkObject(WorkManager)
        val manager = mockk<WorkManager>(relaxed = true)
        every { WorkManager.getInstance(context) } returns manager
        val enqueued = mutableListOf<androidx.work.OneTimeWorkRequest>()
        every { manager.enqueueUniqueWork(any(), any(), capture(enqueued)) } returns mockk(relaxed = true)
        consumer.reconcileSessionEffects()
        enqueued.size shouldBe 1
        enqueued.single().workSpec.input.getLong("id", -1) shouldBe 42L
        enqueued.single().workSpec.input.getLong(com.adsamcik.tracker.points.work.PointsWorker.ARG_EPOCH, -1) shouldBe 7L
        state = state.copy(collectedDataEpoch = 8, restoredDataEpochs = "")
        consumer.reconcileSessionEffects()
        enqueued.size shouldBe 1
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
