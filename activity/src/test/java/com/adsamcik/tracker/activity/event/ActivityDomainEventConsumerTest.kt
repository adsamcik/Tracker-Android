package com.adsamcik.tracker.activity.event

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
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
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ActivityDomainEventConsumer")
class ActivityDomainEventConsumerTest {

	private val domainEventRepository: DomainEventRepository = mockk(relaxed = true)
	private val context: Context = mockk(relaxed = true)
	private lateinit var workManager: WorkManager
	private val consumer = ActivityDomainEventConsumer(domainEventRepository, context)

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	private fun stubWorkManager() {
		mockkObject(WorkManager)
		workManager = mockk(relaxed = true)
		every { WorkManager.getInstance(context) } returns workManager
	}

	@Nested
	@DisplayName("processUnconsumed")
	inner class ProcessUnconsumed {

		@Test
		fun `does nothing when no events`() = runTest {
			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					ActivityDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returns emptyList()

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.getUnconsumedBatchWithIds(
					ActivityDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			}
			confirmVerified(domainEventRepository)
		}

		@Test
		fun `handles SessionEnded and marks consumed`() = runTest {
			stubWorkManager()
			val timestamp = EpochMs(1_700_000_000_000L)
			val persistedId = 20L
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
					ActivityDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(
				listOf(UnconsumedEvent(event, persistedId)),
				emptyList(),
			)

			consumer.processUnconsumed()

			verify {
				workManager.enqueueUniqueWork(
					ActivityDomainEventConsumer.uniqueWorkName(42L),
					ExistingWorkPolicy.KEEP,
					any<OneTimeWorkRequest>(),
				)
			}
			coVerify(exactly = 1) {
				domainEventRepository.markBatchConsumed(
					ActivityDomainEventConsumer.CONSUMER_ID,
					timestamp,
					persistedId,
				)
			}
		}

		@Test
		fun `ignores non-SessionEnded events and still marks consumed`() = runTest {
			val timestamp = EpochMs(1_700_000_000_000L)
			val persistedId = 21L
			val event = DomainEvent.CellDiscovered(
				timestampMs = timestamp,
				processorId = "test-processor",
				cellToken = "cell-xyz",
				level = 7,
				centerLatE7 = 0,
				centerLonE7 = 0,
				quality = 0,
				seasonBit = 1,
			)

			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					ActivityDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(
				listOf(UnconsumedEvent(event, persistedId)),
				emptyList(),
			)

			consumer.processUnconsumed()

			coVerify(exactly = 1) {
				domainEventRepository.markBatchConsumed(
					ActivityDomainEventConsumer.CONSUMER_ID,
					timestamp,
					persistedId,
				)
			}
		}

		@Test
		fun `marks consumed with latest timestamp from multiple events`() = runTest {
			stubWorkManager()
			val earlyTimestamp = EpochMs(1_700_000_000_000L)
			val lateTimestamp = EpochMs(1_700_000_005_000L)
			// Batch is ordered ascending by (timestamp_ms, id) as returned by the DAO.
			val batch = listOf(
				UnconsumedEvent(
					DomainEvent.SessionEnded(
						timestampMs = earlyTimestamp,
						processorId = "test-processor",
						sessionId = 10L,
						totalDistance = DistanceM(500f),
						totalSteps = StepCount(1000),
						duration = DurationMs(300_000L),
					),
					persistedId = 10L,
				),
				UnconsumedEvent(
					DomainEvent.CellDiscovered(
						timestampMs = lateTimestamp,
						processorId = "test-processor",
						cellToken = "cell-1",
						level = 3,
						centerLatE7 = 0,
						centerLonE7 = 0,
						quality = 0,
						seasonBit = 1,
					),
					persistedId = 11L,
				),
			)

			coEvery {
				domainEventRepository.getUnconsumedBatchWithIds(
					ActivityDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(batch, emptyList())

			consumer.processUnconsumed()

			// markBatchConsumed must ack the LAST event (lateTimestamp, id=11)
			coVerify(exactly = 1) {
				domainEventRepository.markBatchConsumed(
					ActivityDomainEventConsumer.CONSUMER_ID,
					lateTimestamp,
					11L,
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
					ActivityDomainEventConsumer.CONSUMER_ID,
					DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
				)
			} returnsMany listOf(firstBatch, secondBatch, emptyList())

			consumer.processUnconsumed()

			coVerifyOrder {
				domainEventRepository.markBatchConsumed(ActivityDomainEventConsumer.CONSUMER_ID, firstTimestamp, 11L)
				domainEventRepository.markBatchConsumed(ActivityDomainEventConsumer.CONSUMER_ID, secondTimestamp, 12L)
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

		@Test
		fun `unique work name includes session id`() {
			ActivityDomainEventConsumer.uniqueWorkName(42L) shouldBe "ActivityRecognition-session-42"
		}
	}
}

