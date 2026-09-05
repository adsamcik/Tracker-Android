package com.adsamcik.tracker.game.event

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.game.event.ExplorationDomainEventConsumer.Companion.CONSUMER_ID
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.data.repository.DefaultDomainEventRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorationDomainEventConsumerTest {

	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var repository: DefaultDomainEventRepository
	private lateinit var consumer: ExplorationDomainEventConsumer
	private lateinit var dirtyTracker: MetricDirtyTracker
	private lateinit var trackingStartupGate: SerializedTestTrackingStartupGate

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		repository = DefaultDomainEventRepository(database.domainEventDao())
		dirtyTracker = mockk(relaxed = true)
		trackingStartupGate = SerializedTestTrackingStartupGate()

		mockkObject(AppDatabase.Companion)
		every { AppDatabase.database(any()) } returns database

		consumer = ExplorationDomainEventConsumer(
			domainEventRepository = repository,
			context = context,
			dirtyTracker = dirtyTracker,
			trackingStartupGate = trackingStartupGate,
		)
	}

	@After
	fun tearDown() {
		database.close()
		unmockkAll()
	}

	@Test
	fun explorationCommitThenCrash_replayDoesNotIncrementVisitAgain() = runTest {
		val event = cellDiscoveredEvent()
		repository.persist(listOf(event))

		consumer.processUnconsumed()

		val firstApplication = requireNotNull(database.explorationCellDao().getByToken(event.cellToken))
		assertEquals(1, firstApplication.visitCount)
		val cursor = requireNotNull(database.domainEventDao().getCursor(CONSUMER_ID))
		assertEquals(event.timestampMs.raw, cursor.lastProcessedMs)

		assertTrue(
			repository.getUnconsumedBatchWithIds(
				consumerId = CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			).isEmpty(),
		)
		consumer.processUnconsumed()

		assertEquals(1, requireNotNull(database.explorationCellDao().getByToken(event.cellToken)).visitCount)
	}

	@Test
	fun acknowledgementFailure_rollsBackExplorationMutation() = runTest {
		val event = cellDiscoveredEvent()
		val failingRepository = mockk<DomainEventRepository>()
		coEvery {
			failingRepository.getUnconsumedBatchWithIds(
				consumerId = CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returns listOf(UnconsumedEvent(event, persistedId = 1L))
		coEvery {
			failingRepository.markBatchConsumed(
				consumerId = CONSUMER_ID,
				upToTimestamp = event.timestampMs,
				upToEventId = 1L,
			)
		} throws IllegalStateException("simulated acknowledgement failure")
		val failingConsumer = ExplorationDomainEventConsumer(
			domainEventRepository = failingRepository,
			context = context,
			dirtyTracker = mockk(relaxed = true),
			trackingStartupGate = trackingStartupGate,
		)

		val failure = runCatching { failingConsumer.processUnconsumed() }.exceptionOrNull()

		assertTrue(failure is IllegalStateException)
		assertNull(database.explorationCellDao().getByToken(event.cellToken))
	}

	@Test
	fun loadedBatch_fullDeletionWinsBeforeMutationAndAcknowledgement() = runTest {
		val event = cellDiscoveredEvent()
		repository.persist(listOf(event))
		trackingStartupGate.afterNextOperation = {
			trackingStartupGate.closeDeleteReopen {
				database.withTransaction {
					database.explorationCellDao().deleteAll()
					database.explorationStreakDao().deleteAll()
					database.domainEventDao().deleteAllCursors()
					database.domainEventDao().deleteAll()
				}
			}
		}

		consumer.processUnconsumed()

		assertNull(database.explorationCellDao().getByToken(event.cellToken))
		assertTrue(database.explorationStreakDao().getAll().isEmpty())
		assertNull(database.domainEventDao().getCursor(CONSUMER_ID))
		assertTrue(
			repository.getUnconsumedBatchWithIds(
				consumerId = CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			).isEmpty(),
		)
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		assertEquals(listOf(1L, 1L), trackingStartupGate.operationGenerations)
	}

	@Test
	fun cancelledBatchLoad_propagatesWithoutMutationOrAcknowledgement() = runTest {
		val cancellingRepository = mockk<DomainEventRepository>()
		coEvery {
			cancellingRepository.getUnconsumedBatchWithIds(
				consumerId = CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} throws CancellationException("cancelled batch load")
		val cancellingConsumer = ExplorationDomainEventConsumer(
			domainEventRepository = cancellingRepository,
			context = context,
			dirtyTracker = dirtyTracker,
			trackingStartupGate = trackingStartupGate,
		)

		val failure = runCatching { cancellingConsumer.processUnconsumed() }.exceptionOrNull()

		assertTrue(failure is CancellationException)
		val cellTokens = database.explorationCellDao()
			.getAllTokensAtLevel(ExplorationStreakTracker.EXPLORATION_LEVEL)
		assertTrue(cellTokens.isEmpty())
		coVerify(exactly = 1) {
			cancellingRepository.getUnconsumedBatchWithIds(
				CONSUMER_ID,
				DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		}
		confirmVerified(cancellingRepository)
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
	}

	private fun cellDiscoveredEvent() = DomainEvent.CellDiscovered(
		timestampMs = EpochMs(1_000L),
		processorId = "test-processor",
		cellToken = "test-cell",
		level = ExplorationStreakTracker.EXPLORATION_LEVEL,
		centerLatE7 = 500_000_000,
		centerLonE7 = 140_000_000,
		quality = 5,
		seasonBit = 1,
	)

	private class SerializedTestTrackingStartupGate : TrackingStartupGate {
		private val operationMutex = Mutex()
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
			val result = operationMutex.withLock {
				if (isReadyGeneration(expectedGeneration)) {
					operation()
				} else {
					null
				}
			}
			afterNextOperation?.also { afterNextOperation = null }?.invoke()
			return result
		}

		suspend fun closeDeleteReopen(delete: suspend () -> Unit) {
			ready = false
			generation += 1L
			operationMutex.withLock {
				delete()
				ready = true
			}
		}
	}
}
