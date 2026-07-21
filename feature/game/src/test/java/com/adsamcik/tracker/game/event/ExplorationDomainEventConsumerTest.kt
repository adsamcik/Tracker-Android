package com.adsamcik.tracker.game.event

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.game.event.ExplorationDomainEventConsumer.Companion.CONSUMER_ID
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.data.repository.DefaultDomainEventRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
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

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		repository = DefaultDomainEventRepository(database.domainEventDao())

		mockkObject(AppDatabase.Companion)
		every { AppDatabase.database(any()) } returns database
		mockkObject(Logger)
		every { Logger.log(any()) } returns Unit

		consumer = ExplorationDomainEventConsumer(
			domainEventRepository = repository,
			context = context,
			dirtyTracker = mockk<MetricDirtyTracker>(relaxed = true),
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
		)

		val failure = runCatching { failingConsumer.processUnconsumed() }.exceptionOrNull()

		assertTrue(failure is IllegalStateException)
		assertNull(database.explorationCellDao().getByToken(event.cellToken))
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
}
