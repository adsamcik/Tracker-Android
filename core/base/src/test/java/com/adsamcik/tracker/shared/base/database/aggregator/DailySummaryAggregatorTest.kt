package com.adsamcik.tracker.shared.base.database.aggregator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailySummaryAggregatorTest {

	private lateinit var database: AppDatabase
	private lateinit var aggregator: DailySummaryAggregator

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		aggregator = DailySummaryAggregator(
			dailySummaryDao = database.dailySummaryDao(),
			sessionSegmentDao = database.sessionSegmentDao(),
		)
	}

	@After
	fun tearDown() {
		database.close()
	}

	private fun createSegment(
		startTimeMs: Long,
		endTimeMs: Long,
		distanceM: Float = 1000f,
		steps: Int? = 500,
		sampleCount: Int = 10,
	) = SessionSegment(
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = distanceM,
		steps = steps,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = sampleCount,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = null,
		createdAt = System.currentTimeMillis(),
	)

	@Test
	fun materializeDayWithNoSegmentsDoesNotCreateRow() = runBlocking {
		val epochDay = 19000L
		aggregator.materializeDayFromSegments(epochDay)

		val result = database.dailySummaryDao().getByDay(epochDay)
		assertNull(result)
	}

	@Test
	fun materializeDayRemovesAnEmptyDerivedRowButPreservesIndependentActiveTime() = runBlocking {
		val emptyDerivedDay = 19000L
		val activeOnlyDay = 19001L
		database.dailySummaryDao().upsert(
			dateEpochDay = emptyDerivedDay,
			totalDistanceM = 100f,
			totalSteps = 50,
			totalDurationMs = 1_000L,
			tripCount = 1,
			activeTrackingMs = 0L,
			lastUpdatedMs = 1L,
			calendarZoneId = ZoneId.systemDefault().id,
		)
		database.dailySummaryDao().upsert(
			dateEpochDay = activeOnlyDay,
			totalDistanceM = 100f,
			totalSteps = 50,
			totalDurationMs = 1_000L,
			tripCount = 1,
			activeTrackingMs = 5_000L,
			lastUpdatedMs = 1L,
			calendarZoneId = ZoneId.systemDefault().id,
		)

		aggregator.materializeDayFromSegments(emptyDerivedDay)
		aggregator.materializeDayFromSegments(activeOnlyDay)

		assertNull(database.dailySummaryDao().getByDay(emptyDerivedDay))
		val activeOnly = database.dailySummaryDao().getByDay(activeOnlyDay)
		assertNotNull(activeOnly)
		assertEquals(0, activeOnly!!.totalSteps)
		assertEquals(0, activeOnly.tripCount)
		assertEquals(5_000L, activeOnly.activeTrackingMs)
	}

	@Test
	fun materializeDayCreatesRowFromSingleSegment() = runBlocking {
		val epochDay = 19000L
		val startMs = startOfDayMs(epochDay) + 1000L
		val endMs = startMs + 3600_000L

		database.sessionSegmentDao().insert(
			createSegment(
				startTimeMs = startMs,
				endTimeMs = endMs,
				distanceM = 5000f,
				steps = 2000,
			)
		)

		aggregator.materializeDayFromSegments(epochDay)

		val result = database.dailySummaryDao().getByDay(epochDay)
		assertNotNull(result)
		assertEquals(5000f, result!!.totalDistanceM)
		assertEquals(2000, result.totalSteps)
		assertEquals(3600_000L, result.totalDurationMs)
		assertEquals(1, result.tripCount)
		assertEquals(ZoneId.systemDefault().id, result.calendarZoneId)
	}

	@Test
	fun materializeDayAggregatesMultipleSegments() = runBlocking {
		val epochDay = 19000L
		val dayStartMs = startOfDayMs(epochDay)

		// Session 1: morning walk
		database.sessionSegmentDao().insert(
			createSegment(
				startTimeMs = dayStartMs + 3600_000L,
				endTimeMs = dayStartMs + 5400_000L,
				distanceM = 3000f,
				steps = 1500,
			)
		)

		// Session 2: evening run
		database.sessionSegmentDao().insert(
			createSegment(
				startTimeMs = dayStartMs + 64800_000L,
				endTimeMs = dayStartMs + 68400_000L,
				distanceM = 7000f,
				steps = 3500,
			)
		)

		aggregator.materializeDayFromSegments(epochDay)

		val result = database.dailySummaryDao().getByDay(epochDay)
		assertNotNull(result)
		assertEquals(10000f, result!!.totalDistanceM)
		assertEquals(5000, result.totalSteps)
		assertEquals(5400_000L, result.totalDurationMs) // 1800_000 + 3600_000
		assertEquals(2, result.tripCount)
	}

	@Test
	fun materializeDayIsIdempotent() = runBlocking {
		val epochDay = 19000L
		val startMs = startOfDayMs(epochDay) + 1000L

		database.sessionSegmentDao().insert(
			createSegment(
				startTimeMs = startMs,
				endTimeMs = startMs + 1800_000L,
				distanceM = 2000f,
				steps = 1000,
			)
		)

		// Run materialization twice
		aggregator.materializeDayFromSegments(epochDay)
		aggregator.materializeDayFromSegments(epochDay)

		val result = database.dailySummaryDao().getByDay(epochDay)
		assertNotNull(result)
		assertEquals(2000f, result!!.totalDistanceM)
		assertEquals(1000, result.totalSteps)
		assertEquals(1, result.tripCount)
	}

	@Test
	fun materializeDayExcludesSegmentsFromOtherDays() = runBlocking {
		val epochDay = 19000L
		val dayStartMs = startOfDayMs(epochDay)

		// Segment in target day
		database.sessionSegmentDao().insert(
			createSegment(
				startTimeMs = dayStartMs + 1000L,
				endTimeMs = dayStartMs + 3600_000L,
				distanceM = 5000f,
				steps = 2000,
			)
		)

		// Segment in previous day — should not be included
		database.sessionSegmentDao().insert(
			createSegment(
				startTimeMs = dayStartMs - 7200_000L,
				endTimeMs = dayStartMs - 3600_000L,
				distanceM = 3000f,
				steps = 1500,
			)
		)

		aggregator.materializeDayFromSegments(epochDay)

		val result = database.dailySummaryDao().getByDay(epochDay)
		assertNotNull(result)
		assertEquals(5000f, result!!.totalDistanceM)
		assertEquals(2000, result.totalSteps)
		assertEquals(1, result.tripCount)
	}

	@Test
	fun materializeDayHandlesNullSteps() = runBlocking {
		val epochDay = 19000L
		val startMs = startOfDayMs(epochDay) + 1000L

		database.sessionSegmentDao().insert(
			createSegment(
				startTimeMs = startMs,
				endTimeMs = startMs + 1800_000L,
				distanceM = 2000f,
				steps = null,
			)
		)

		aggregator.materializeDayFromSegments(epochDay)

		val result = database.dailySummaryDao().getByDay(epochDay)
		assertNotNull(result)
		assertEquals(2000f, result!!.totalDistanceM)
		assertEquals(0, result.totalSteps)
	}

	@Test
	fun materializeDayUpdatesExistingRow() = runBlocking {
		val epochDay = 19000L
		val dayStartMs = startOfDayMs(epochDay)

		// First segment
		database.sessionSegmentDao().insert(
			createSegment(
				startTimeMs = dayStartMs + 1000L,
				endTimeMs = dayStartMs + 1800_000L,
				distanceM = 2000f,
				steps = 1000,
			)
		)

		aggregator.materializeDayFromSegments(epochDay)

		// Add another segment (new session)
		database.sessionSegmentDao().insert(
			createSegment(
				startTimeMs = dayStartMs + 36000_000L,
				endTimeMs = dayStartMs + 39600_000L,
				distanceM = 5000f,
				steps = 2500,
			)
		)

		// Re-materialize — should reflect both segments
		aggregator.materializeDayFromSegments(epochDay)

		val result = database.dailySummaryDao().getByDay(epochDay)
		assertNotNull(result)
		assertEquals(7000f, result!!.totalDistanceM)
		assertEquals(3500, result.totalSteps)
		assertEquals(2, result.tripCount)
	}

	@Test
	fun materializeDayPreservesActiveTrackingMs() = runBlocking {
		val epochDay = 19000L
		val dayStartMs = startOfDayMs(epochDay)

		// Pre-populate with existing active tracking time
		database.dailySummaryDao().upsert(
			dateEpochDay = epochDay,
			totalDistanceM = 0f,
			totalSteps = 0,
			totalDurationMs = 0L,
			tripCount = 0,
			activeTrackingMs = 7200_000L,
			lastUpdatedMs = System.currentTimeMillis(),
			calendarZoneId = ZoneId.systemDefault().id,
		)

		// Add a segment
		database.sessionSegmentDao().insert(
			createSegment(
				startTimeMs = dayStartMs + 1000L,
				endTimeMs = dayStartMs + 1800_000L,
				distanceM = 2000f,
				steps = 1000,
			)
		)

		aggregator.materializeDayFromSegments(epochDay)

		val result = database.dailySummaryDao().getByDay(epochDay)
		assertNotNull(result)
		assertEquals(2000f, result!!.totalDistanceM)
		assertEquals(7200_000L, result.activeTrackingMs)
	}

	@Test
	fun materializeTodayIncludesTodaySessionInWeeklySummaryForPositiveOffsetTimeZone() = runBlocking {
		val originalTimeZone = TimeZone.getDefault()
		try {
			val pragueZone = ZoneId.of("Europe/Prague")
			TimeZone.setDefault(TimeZone.getTimeZone(pragueZone))
			aggregator = DailySummaryAggregator(
				dailySummaryDao = database.dailySummaryDao(),
				sessionSegmentDao = database.sessionSegmentDao(),
				zoneId = pragueZone,
			)
			val today = LocalDate.now(pragueZone)
			val todayEpochDay = today.toEpochDay()
			val startMs = today
				.atStartOfDay(pragueZone)
				.plusHours(11)
				.toInstant()
				.toEpochMilli()

			database.sessionSegmentDao().insert(
				createSegment(
					startTimeMs = startMs,
					endTimeMs = startMs + 15_000L,
					distanceM = 5100f,
					steps = 0,
				)
			)

			aggregator.materializeToday()

			val weeklyDistance = database.dailySummaryDao()
				.getBetween(todayEpochDay - 6, todayEpochDay)
				.sumOf { it.totalDistanceM.toDouble() }
				.toFloat()
			assertEquals(5100f, weeklyDistance)
		} finally {
			TimeZone.setDefault(originalTimeZone)
		}
	}

	@Test
	fun explicitZoneRemainsTheAuthorityWhenTheProcessDefaultChanges() = runBlocking {
		val originalTimeZone = TimeZone.getDefault()
		try {
			val authorityZone = ZoneId.of("America/New_York")
			val targetDate = LocalDate.of(2026, 3, 8)
			val targetDay = targetDate.toEpochDay()
			val startMs = targetDate.atStartOfDay(authorityZone).plusHours(12)
				.toInstant().toEpochMilli()
			val fixedZoneAggregator = DailySummaryAggregator(
				dailySummaryDao = database.dailySummaryDao(),
				sessionSegmentDao = database.sessionSegmentDao(),
				zoneId = authorityZone,
			)
			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
			database.sessionSegmentDao().insert(
				createSegment(startMs, startMs + 60_000L, distanceM = 5f, steps = 7),
			)

			fixedZoneAggregator.materializeDayFromSegments(targetDay)

			val result = database.dailySummaryDao().getByDay(targetDay)
			assertNotNull(result)
			assertEquals(7, result!!.totalSteps)
			assertEquals(authorityZone.id, result.calendarZoneId)
		} finally {
			TimeZone.setDefault(originalTimeZone)
		}
	}

	@Test
	fun materializeDayProratesCrossMidnightSegmentByDuration() = runBlocking {
		val dayA = 19000L
		val dayB = 19001L
		val dayBStartMs = startOfDayMs(dayB)
		// Run crosses midnight: 11:50 PM day A → 12:10 AM day B (20 minutes total,
		// 10 min on each side). Distance 6000 m, steps 4000.
		val startMs = dayBStartMs - 10L * 60_000L
		val endMs = dayBStartMs + 10L * 60_000L
		database.sessionSegmentDao().insert(
			createSegment(
				startTimeMs = startMs,
				endTimeMs = endMs,
				distanceM = 6000f,
				steps = 4000,
			)
		)

		aggregator.materializeDayFromSegments(dayA)
		aggregator.materializeDayFromSegments(dayB)

		val resultA = database.dailySummaryDao().getByDay(dayA)
		val resultB = database.dailySummaryDao().getByDay(dayB)
		assertNotNull(resultA)
		assertNotNull(resultB)
		// Each day gets ~half the duration. Constant-speed approximation: ~half distance + steps.
		assertEquals(10L * 60_000L, resultA!!.totalDurationMs)
		assertEquals(10L * 60_000L, resultB!!.totalDurationMs)
		assertEquals(3000f, resultA.totalDistanceM, 0.5f)
		assertEquals(3000f, resultB.totalDistanceM, 0.5f)
		assertEquals(2000, resultA.totalSteps)
		assertEquals(2000, resultB.totalSteps)
		// Trip is counted on the day it STARTED (day A) — not double-counted.
		assertEquals(1, resultA.tripCount)
		assertEquals(0, resultB.tripCount)
	}

	private fun startOfDayMs(epochDay: Long): Long {
		return LocalDate.ofEpochDay(epochDay)
			.atStartOfDay(ZoneId.systemDefault())
			.toInstant()
			.toEpochMilli()
	}
}
