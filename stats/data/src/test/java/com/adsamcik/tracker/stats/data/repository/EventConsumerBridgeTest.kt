package com.adsamcik.tracker.stats.data.repository

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * End-to-end test that exercises the tracker → statistics bridge against a
 * real Room [AppDatabase]:
 *
 *  1. [DefaultDomainEventRepository] persists [DomainEvent] subtypes through
 *     the real [com.adsamcik.tracker.shared.base.database.dao.DomainEventDao]
 *     (serialization + composite-cursor consumption).
 *  2. [DailySummaryAggregator] reads `session_segment` rows and materializes
 *     `daily_summary` rows.
 *
 * Together these two components form the production handoff from the tracker
 * pipeline (which emits [DomainEvent]s and writes session segments) to the
 * statistics surface (which observes events and reads daily summaries). The
 * test pins:
 *
 *  - Composite `(timestamp_ms, id)` cursor ordering across out-of-order
 *    persists and same-ms clusters.
 *  - Partial [DomainEventRepository.markBatchConsumed] leaves remaining
 *    events available for the next batch in the correct order.
 *  - [DailySummaryAggregator] sums intra-day segments correctly.
 *  - [DailySummaryAggregator] pro-rates a cross-midnight segment by duration
 *    fraction and only counts the trip on the day where it started.
 *
 * No production code is modified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventConsumerBridgeTest {

	private lateinit var database: AppDatabase
	private lateinit var repository: DefaultDomainEventRepository
	private lateinit var aggregator: DailySummaryAggregator

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext())
		repository = DefaultDomainEventRepository(database.domainEventDao())
		aggregator = DailySummaryAggregator(
			dailySummaryDao = database.dailySummaryDao(),
			sessionSegmentDao = database.sessionSegmentDao(),
		)
	}

	@After
	fun tearDown() {
		database.close()
	}

	// region domain-event bridge

	@Test
	fun `getUnconsumedBatchWithIds returns events in (timestamp, id) order across out-of-order persists and same-ms cluster`() = runTest {
		// Persist five events in deliberately out-of-order timestamps so the
		// row id order does NOT match the timestamp order — the ORDER BY in
		// getUnconsumedBatchSeek must surface them by (timestamp_ms, id).
		repository.persist(
			listOf(
				sessionStarted(timestampMs = 1_500L, processorId = "p-mid"),
				tripCompleted(timestampMs = 3_000L, tripStartMs = 2_000L),
				sessionEnded(timestampMs = 2_000L, sessionId = 7L),
				tripCompleted(timestampMs = 1_000L, tripStartMs = 500L),
				sessionStarted(timestampMs = 1_000L, processorId = "p-early"),
			),
		)
		// Persist a same-ms cluster — three events all at timestamp 2_500.
		repository.persist(
			listOf(
				sessionStarted(timestampMs = 2_500L, processorId = "cluster-a"),
				sessionStarted(timestampMs = 2_500L, processorId = "cluster-b"),
				sessionStarted(timestampMs = 2_500L, processorId = "cluster-c"),
			),
		)

		val batch = repository.getUnconsumedBatchWithIds(
			consumerId = "test-consumer",
			limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
		)

		batch shouldHaveSize 8
		val timestamps = batch.map { it.event.timestampMs.raw }
		// Strictly non-decreasing — primary ordering is by timestamp_ms ASC.
		for (i in 1 until timestamps.size) {
			(timestamps[i] >= timestamps[i - 1]) shouldBe true
		}
		// The same-ms cluster must be ordered by persisted id ASC (insertion
		// order at that timestamp) — cluster-a, then cluster-b, then cluster-c.
		val cluster = batch.filter { it.event.timestampMs.raw == 2_500L }
		cluster shouldHaveSize 3
		(cluster[0].persistedId < cluster[1].persistedId) shouldBe true
		(cluster[1].persistedId < cluster[2].persistedId) shouldBe true
		cluster.map { it.event.processorId } shouldBe listOf("cluster-a", "cluster-b", "cluster-c")
	}

	@Test
	fun `partial markBatchConsumed leaves remaining events for the next batch`() = runTest {
		// Six events at strictly ascending timestamps to make the partial-ack
		// boundary unambiguous.
		repository.persist(
			(1..6).map { idx ->
				sessionStarted(
					timestampMs = idx * 1_000L,
					processorId = "evt-$idx",
				)
			},
		)

		val firstBatch = repository.getUnconsumedBatchWithIds(
			consumerId = "test-consumer",
			limit = 3,
		)
		firstBatch shouldHaveSize 3
		firstBatch.map { it.event.processorId } shouldBe listOf("evt-1", "evt-2", "evt-3")

		// Ack only the first batch — using the LAST event's composite
		// (timestamp, id) as the inclusive cursor.
		val lastInFirstBatch = firstBatch.last()
		repository.markBatchConsumed(
			consumerId = "test-consumer",
			upToTimestamp = lastInFirstBatch.event.timestampMs,
			upToEventId = lastInFirstBatch.persistedId,
		)

		val secondBatch = repository.getUnconsumedBatchWithIds(
			consumerId = "test-consumer",
			limit = 10,
		)
		secondBatch shouldHaveSize 3
		secondBatch.map { it.event.processorId } shouldBe listOf("evt-4", "evt-5", "evt-6")
		// Cursor moved forward strictly past the first batch's last id.
		secondBatch.first().persistedId shouldBeGreaterThan lastInFirstBatch.persistedId
	}

	// endregion

	// region daily-summary materialization

	@Test
	fun `DailySummaryAggregator sums multiple intra-day session_segment rows into a single daily_summary row`() = runTest {
		// Pick a fixed local day so the test is deterministic regardless of
		// when it runs. Use a recent past day to avoid clock edge cases.
		val epochDay = LocalDate.now().minusDays(7L).toEpochDay()
		val dayStartMs = startOfLocalDayMs(epochDay)

		// Three segments scattered within the same local day.
		insertSegment(
			startTimeMs = dayStartMs + HOUR_MS * 2L,
			endTimeMs = dayStartMs + HOUR_MS * 2L + MINUTE_MS * 30L,
			distanceM = 1_500f,
			steps = 2_500,
		)
		insertSegment(
			startTimeMs = dayStartMs + HOUR_MS * 10L,
			endTimeMs = dayStartMs + HOUR_MS * 10L + MINUTE_MS * 45L,
			distanceM = 4_200f,
			steps = 0,
		)
		insertSegment(
			startTimeMs = dayStartMs + HOUR_MS * 18L,
			endTimeMs = dayStartMs + HOUR_MS * 18L + MINUTE_MS * 15L,
			distanceM = 800f,
			steps = 1_200,
		)

		aggregator.materializeDayFromSegments(epochDay)

		val summary = database.dailySummaryDao().getByDay(epochDay)
		summary.shouldNotBeNull()
		summary.dateEpochDay shouldBe epochDay
		summary.totalDistanceM shouldBe (1_500f + 4_200f + 800f).plusOrMinus(0.5f)
		summary.totalSteps shouldBe (2_500 + 0 + 1_200)
		summary.totalDurationMs shouldBe (MINUTE_MS * 30L + MINUTE_MS * 45L + MINUTE_MS * 15L)
		// Three intra-day segments → three trips counted on this day.
		summary.tripCount shouldBe 3
	}

	@Test
	fun `DailySummaryAggregator pro-rates a cross-midnight segment by duration fraction`() = runTest {
		val day1 = LocalDate.now().minusDays(10L).toEpochDay()
		val day2 = day1 + 1L
		val day1StartMs = startOfLocalDayMs(day1)
		val day2StartMs = startOfLocalDayMs(day2)

		// 60-minute segment spanning midnight: 23:30 on day1 → 00:30 on day2.
		val segmentStart = day2StartMs - MINUTE_MS * 30L
		val segmentEnd = day2StartMs + MINUTE_MS * 30L
		insertSegment(
			startTimeMs = segmentStart,
			endTimeMs = segmentEnd,
			distanceM = 1_000f,
			steps = 1_000,
		)

		aggregator.materializeDayFromSegments(day1)
		aggregator.materializeDayFromSegments(day2)

		val day1Summary = database.dailySummaryDao().getByDay(day1)
		val day2Summary = database.dailySummaryDao().getByDay(day2)
		day1Summary.shouldNotBeNull()
		day2Summary.shouldNotBeNull()

		// Half of each metric goes to each day; rounding tolerance is small.
		day1Summary.totalDistanceM shouldBe 500f.plusOrMinus(0.5f)
		day2Summary.totalDistanceM shouldBe 500f.plusOrMinus(0.5f)
		// Steps are pro-rated by time fraction and rounded down — both halves
		// should be exactly 500 because the duration fraction is exactly 0.5.
		day1Summary.totalSteps shouldBe 500
		day2Summary.totalSteps shouldBe 500
		day1Summary.totalDurationMs shouldBe (MINUTE_MS * 30L)
		day2Summary.totalDurationMs shouldBe (MINUTE_MS * 30L)
		// Trip count: only the day the segment STARTED gets +1.
		day1Summary.tripCount shouldBe 1
		day2Summary.tripCount shouldBe 0
	}

	// endregion

	// region fixtures

	private fun sessionStarted(
		timestampMs: Long,
		processorId: String,
	): DomainEvent.SessionStarted = DomainEvent.SessionStarted(
		timestampMs = EpochMs(timestampMs),
		processorId = processorId,
		isUserInitiated = true,
		initialTier = PolicyTier.PRECISION,
	)

	private fun sessionEnded(
		timestampMs: Long,
		sessionId: Long,
	): DomainEvent.SessionEnded = DomainEvent.SessionEnded(
		timestampMs = EpochMs(timestampMs),
		processorId = "test-processor",
		sessionId = sessionId,
		totalDistance = DistanceM.coerced(123f),
		totalSteps = StepCount(456),
		duration = DurationMs(1_000L),
	)

	private fun tripCompleted(
		timestampMs: Long,
		tripStartMs: Long,
	): DomainEvent.TripCompleted = DomainEvent.TripCompleted(
		timestampMs = EpochMs(timestampMs),
		processorId = "test-processor",
		tripStartMs = EpochMs(tripStartMs),
		distance = DistanceM.coerced(500f),
		steps = StepCount(0),
		duration = DurationMs(timestampMs - tripStartMs),
		primaryMode = TransportMode.DRIVE,
	)

	private suspend fun insertSegment(
		startTimeMs: Long,
		endTimeMs: Long,
		distanceM: Float,
		steps: Int,
	) {
		val id = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = startTimeMs,
				endTimeMs = endTimeMs,
				distanceM = distanceM,
				steps = steps,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 1,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = null,
				createdAt = startTimeMs,
			),
		)
		// Guard against the DAO silently returning -1L on insert (would indicate
		// an entity / schema mismatch we should fail loudly on).
		id shouldBeGreaterThanOrEqual 1L
	}

	private fun startOfLocalDayMs(epochDay: Long): Long = LocalDate.ofEpochDay(epochDay)
		.atStartOfDay(ZoneId.systemDefault())
		.toInstant()
		.toEpochMilli()

	private companion object {
		val MINUTE_MS: Long = TimeUnit.MINUTES.toMillis(1L)
		val HOUR_MS: Long = TimeUnit.HOURS.toMillis(1L)
	}

	// endregion
}
