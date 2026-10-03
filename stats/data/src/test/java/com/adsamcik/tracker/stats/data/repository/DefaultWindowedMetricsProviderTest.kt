package com.adsamcik.tracker.stats.data.repository

import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefaultWindowedMetricsProviderTest {

	private val dailySummaryDao: DailySummaryDao = mockk()
	private val explorationCellDao: ExplorationCellDao = mockk()
	private val explorationStreakDao: ExplorationStreakDao = mockk()
	private val sessionSegmentDao: SessionSegmentDao = mockk()
	private val exportLogDao: ExportLogDao = mockk()
	private val savedTripDistanceReader: SavedTripDistanceReader = mockk()

	private lateinit var provider: DefaultWindowedMetricsProvider

	@BeforeEach
	fun setUp() {
		provider = DefaultWindowedMetricsProvider(
			dailySummaryDao = dailySummaryDao,
			explorationCellDao = explorationCellDao,
			explorationStreakDao = explorationStreakDao,
			sessionSegmentDao = sessionSegmentDao,
			exportLogDao = exportLogDao,
			dispatchers = TestDispatchersProvider(Dispatchers.Unconfined),
			savedTripDistanceReader = savedTripDistanceReader,
		)
	}

	@Test
	fun `longest trip uses whole measured saved distance in the selected interval`() = runTest {
		coEvery { savedTripDistanceReader.longestDistanceMeters(100L, 200L) } returns 4_950L.right()
		provider.collect(MetricKeys.LONGEST_TRIP_KM, TimeWindow.Interval(100L, 200L)) shouldBe 4L.right()
		coVerify(exactly = 0) { sessionSegmentDao.maxSegmentDistance() }
	}

	@Test
	fun `all time longest uses the same whole trip reader instead of a physical segment maximum`() = runTest {
		coEvery { savedTripDistanceReader.longestDistanceMeters(0L, Long.MAX_VALUE) } returns 8_500L.right()
		provider.collect(MetricKeys.LONGEST_TRIP_KM, TimeWindow.Cumulative) shouldBe 8L.right()
		coVerify(exactly = 0) { sessionSegmentDao.maxSegmentDistance() }
	}

	@Test
	fun `longest trip preserves unavailable measurements instead of fabricating zero or lifetime data`() = runTest {
		val error = StatsError.NotFound("No measured trip distance is available", "Location trip", "100:200")
		coEvery { savedTripDistanceReader.longestDistanceMeters(100L, 200L) } returns error.left()
		provider.collect(MetricKeys.LONGEST_TRIP_KM, TimeWindow.Interval(100L, 200L)) shouldBe error.left()
		coVerify(exactly = 0) { sessionSegmentDao.maxSegmentDistance() }
	}

	@Test
	fun `longest trip preserves cancellation and does not retry an unrelated aggregate`() = runTest {
		val cancellation = CancellationException("cancelled read")
		coEvery { savedTripDistanceReader.longestDistanceMeters(100L, 200L) } throws cancellation
		val result = runCatching { provider.collect(MetricKeys.LONGEST_TRIP_KM, TimeWindow.Interval(100L, 200L)) }.exceptionOrNull()
		result shouldBe cancellation
		coVerify(exactly = 0) { sessionSegmentDao.maxSegmentDistance() }
	}

	@Test
	fun `seasonal period never uses an undated lifetime mask`() = runTest {
		provider.collect(MetricKeys.SEASONS_EXPLORED, TimeWindow.Interval(100L, 200L)) shouldBe
			StatsError.ValidationError("Seasonal visits are unavailable for this period").left()
		coVerify(exactly = 0) { explorationCellDao.getDistinctSeasonBitmasks(any()) }
	}

	@Test
	fun `all Steps metrics and windows reject unqualified aggregates before any DAO access`() = runTest {
		val windows = listOf(TimeWindow.Cumulative, TimeWindow.Interval(100L, 200L), TimeWindow.Rolling(60_000L))
		for (metric in listOf(MetricKeys.STEPS, MetricKeys.STEPS_TOTAL, MetricKeys.BEST_DAILY_STEPS)) {
			for (window in windows) {
				provider.collect(metric, window) shouldBe
					StatsError.ValidationError("Steps require source-qualified numeric evidence").left()
			}
		}
		verify {
			listOf(dailySummaryDao, explorationCellDao, explorationStreakDao, sessionSegmentDao, exportLogDao) wasNot Called
		}
	}

	@Test
	fun `collect returns canonical interval total distance in meters`() = runTest {
		// Provider now converts the window's ms → epoch-day before calling the DAO so
		// the SQLite index on date_epoch_day is usable. The test asserts the result;
		// exact day-conversion math is covered by dedicated tests.
		coEvery { dailySummaryDao.sumTotalDistanceBetween(any(), any()) } returns 15_500L

		val result = provider.collect(
			metric = MetricKeys.DISTANCE_TOTAL_M,
			window = TimeWindow.Interval(1_000L, 5_000L),
		)

		result shouldBe 15_500L.right()
	}

	@Test
	fun `collect returns canonical cumulative total distance in meters`() = runTest {
		coEvery { dailySummaryDao.sumTotalDistance() } returns 42_125L

		val result = provider.collect(
			metric = MetricKeys.DISTANCE_TOTAL_M,
			window = TimeWindow.Cumulative,
		)

		result shouldBe 42_125L.right()
	}

	@Test
	fun `collect returns interval active minutes`() = runTest {
		coEvery { dailySummaryDao.sumActiveMinutesBetween(any(), any()) } returns 42L

		val result = provider.collect(
			metric = MetricKeys.ACTIVE_MINUTES,
			window = TimeWindow.Interval(10L, 50L),
		)

		result shouldBe 42L.right()
	}

	@Test
	fun `collect returns interval discovered cells`() = runTest {
		coEvery { explorationCellDao.countDiscoveredBetween(100L, 200L, 14) } returns 7L

		val result = provider.collect(
			metric = MetricKeys.CELLS_DISCOVERED,
			window = TimeWindow.Interval(100L, 200L),
		)

		result shouldBe 7L.right()
	}

	@Test
	fun `collect returns interval on foot distance`() = runTest {
		coEvery { sessionSegmentDao.sumDistanceByActivitiesBetween(100L, 200L, any()) } returns 1_250L

		val result = provider.collect(
			metric = MetricKeys.DISTANCE_ON_FOOT_M,
			window = TimeWindow.Interval(100L, 200L),
		)

		result shouldBe 1_250L.right()
	}

	@Test
	fun `collect returns interval walking trips`() = runTest {
		coEvery { sessionSegmentDao.countByActivitiesBetween(100L, 200L, any()) } returns 11L

		val result = provider.collect(
			metric = MetricKeys.WALKING_TRIPS,
			window = TimeWindow.Interval(100L, 200L),
		)

		result shouldBe 11L.right()
	}

	@Test
	fun `collect returns interval cycling trips`() = runTest {
		coEvery { sessionSegmentDao.countByActivitiesBetween(1L, 2L, any()) } returns 9L

		val result = provider.collect(
			metric = MetricKeys.CYCLING_TRIPS,
			window = TimeWindow.Interval(1L, 2L),
		)

		result shouldBe 9L.right()
	}

	@Test
	fun `collect rolling delegates to interval query`() = runTest {
		coEvery { dailySummaryDao.sumTotalDistanceBetween(any(), any()) } returns 999L

		val result = provider.collect(
			metric = MetricKeys.DISTANCE_TOTAL_M,
			window = TimeWindow.Rolling(durationMs = 60_000L),
		)

		result shouldBe 999L.right()
		coVerify(exactly = 1) { dailySummaryDao.sumTotalDistanceBetween(any(), any()) }
	}

	@Test
	fun `interval activity variety queries only the selected period`() = runTest {
		coEvery { sessionSegmentDao.countDistinctActivitiesInClosedInterval(1_000L, 2_000L) } returns 2L
		provider.collect(MetricKeys.ACTIVITY_TYPES_USED, TimeWindow.Interval(1_000L, 2_000L)) shouldBe 2L.right()
		coVerify(exactly = 0) { sessionSegmentDao.countDistinctActivities() }
	}

	@Test
	fun `interval export count uses completion timestamps within that period`() = runTest {
		coEvery { exportLogDao.countCompletedBetween(1_000L, 2_000L) } returns 3L
		provider.collect(MetricKeys.TOTAL_EXPORTS, TimeWindow.Interval(1_000L, 2_000L)) shouldBe 3L.right()
		coVerify(exactly = 0) { exportLogDao.countTotal() }
	}

	@Test
	fun `rolling export count uses the same bounded query`() = runTest {
		coEvery { exportLogDao.countCompletedBetween(any(), any()) } returns 1L
		provider.collect(MetricKeys.TOTAL_EXPORTS, TimeWindow.Rolling(60_000L)) shouldBe 1L.right()
		coVerify(exactly = 1) { exportLogDao.countCompletedBetween(any(), any()) }
		coVerify(exactly = 0) { exportLogDao.countTotal() }
	}

	@Test
	fun `all time retains the complete lifetime streak`() = runTest {
		coEvery { explorationStreakDao.getByType("DAILY_DISCOVERY") } returns ExplorationStreakEntity(
			type = "DAILY_DISCOVERY",
			currentCount = 3,
			bestCount = 17,
			lastIncrementDay = 19_000L,
			updatedAt = 0L,
		)

		val result = provider.collect(
			metric = MetricKeys.DAILY_STREAK,
			window = TimeWindow.Cumulative,
		)

		result shouldBe 17L.right()
	}

	@Test
	fun `selected daily streak uses only dated discoveries and never lifetime best`() = runTest {
		coEvery { explorationCellDao.discoveryTimestampsBetween(1_000L, 2_000L, 14, 65_537) } returns
			listOf(1_000L, 1_500L, 2_000L)
		provider.collect(MetricKeys.DAILY_STREAK, TimeWindow.Interval(1_000L, 2_000L)) shouldBe 1L.right()
		coVerify(exactly = 0) { explorationStreakDao.getByType(any()) }
	}

	@Test
	fun `weekly window counts consecutive local weeks represented inside that window`() = runTest {
		val firstDay = LocalDate.of(2026, 1, 15)
		fun timestamp(day: LocalDate) = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
		val timestamps = listOf(firstDay, firstDay.plusWeeks(1), firstDay.plusWeeks(3)).map(::timestamp)
		coEvery { explorationCellDao.discoveryTimestampsBetween(timestamps.first(), timestamps.last(), 14, 65_537) } returns timestamps
		provider.collect(MetricKeys.WEEKLY_STREAK,
			TimeWindow.Interval(timestamps.first(), timestamps.last())) shouldBe 2L.right()
		coVerify(exactly = 0) { explorationStreakDao.getByType(any()) }
	}

	@Test
	fun `rolling streak uses bounded discovery evidence`() = runTest {
		coEvery { explorationCellDao.discoveryTimestampsBetween(any(), any(), 14, 65_537) } coAnswers {
			listOf(secondArg<Long>())
		}
		provider.collect(MetricKeys.DAILY_STREAK, TimeWindow.Rolling(60_000L)) shouldBe 1L.right()
		coVerify(exactly = 1) { explorationCellDao.discoveryTimestampsBetween(any(), any(), 14, 65_537) }
		coVerify(exactly = 0) { explorationStreakDao.getByType(any()) }
	}

	@Test
	fun `empty bounded discovery evidence has no streak`() = runTest {
		coEvery { explorationCellDao.discoveryTimestampsBetween(100L, 200L, 14, 65_537) } returns emptyList()
		for (metric in listOf(MetricKeys.DAILY_STREAK, MetricKeys.WEEKLY_STREAK)) {
			provider.collect(metric, TimeWindow.Interval(100L, 200L)) shouldBe 0L.right()
		}
	}

	@Test
	fun `lookahead exhaustion rejects a plausible partial streak`() = runTest {
		coEvery { explorationCellDao.discoveryTimestampsBetween(0L, 1_000_000L, 14, 65_537) } returns
			List(65_537) { it.toLong() }
		provider.collect(MetricKeys.DAILY_STREAK, TimeWindow.Interval(0L, 1_000_000L)) shouldBe
			StatsError.ValidationError("Discovery streak read budget exceeded").left()
		coVerify(exactly = 0) { explorationStreakDao.getByType(any()) }
	}

	@Test
	fun `exact read ceiling is complete without lookahead`() = runTest {
		coEvery { explorationCellDao.discoveryTimestampsBetween(0L, 1_000_000L, 14, 65_537) } returns
			List(65_536) { it.toLong() }
		provider.collect(MetricKeys.DAILY_STREAK, TimeWindow.Interval(0L, 1_000_000L)) shouldBe 1L.right()
	}

	@Test
	fun `storage failure remains an error without a fabricated streak`() = runTest {
		val failure = IllegalStateException("storage unavailable")
		coEvery { explorationCellDao.discoveryTimestampsBetween(any(), any(), 14, 65_537) } throws failure
		provider.collect(MetricKeys.WEEKLY_STREAK, TimeWindow.Interval(100L, 200L)) shouldBe
			StatsError.DatabaseError("Failed to load discovery streak", failure).left()
		coVerify(exactly = 0) { explorationStreakDao.getByType(any()) }
	}

	@Test
	fun `cancelled discovery query propagates cancellation`() = runTest {
		val cancellation = CancellationException("clear or caller cancelled")
		coEvery { explorationCellDao.discoveryTimestampsBetween(any(), any(), 14, 65_537) } throws cancellation
		val result = try {
			provider.collect(MetricKeys.DAILY_STREAK, TimeWindow.Interval(100L, 200L))
			error("Expected cancellation")
		} catch (caught: CancellationException) { caught }
		result shouldBe cancellation
	}

	@Test
	fun `day membership retains the calendar captured before the query suspends`() = runTest {
		val originalZone = TimeZone.getDefault()
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
			val timestamps = listOf("2026-01-01T23:30:00Z", "2026-01-02T00:30:00Z")
				.map { java.time.Instant.parse(it).toEpochMilli() }
			coEvery { explorationCellDao.discoveryTimestampsBetween(any(), any(), 14, 65_537) } coAnswers {
				TimeZone.setDefault(TimeZone.getTimeZone("Europe/Prague"))
				timestamps
			}
			provider.collect(MetricKeys.DAILY_STREAK,
				TimeWindow.Interval(timestamps.first(), timestamps.last())) shouldBe 2L.right()
		} finally {
			TimeZone.setDefault(originalZone)
		}
	}

	@Test
	fun `week membership retains the locale captured before the query suspends`() = runTest {
		val originalZone = TimeZone.getDefault()
		val originalLocale = Locale.getDefault()
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
			Locale.setDefault(Locale.US)
			val timestamps = listOf("2026-01-03T12:00:00Z", "2026-01-04T12:00:00Z")
				.map { java.time.Instant.parse(it).toEpochMilli() }
			coEvery { explorationCellDao.discoveryTimestampsBetween(any(), any(), 14, 65_537) } coAnswers {
				Locale.setDefault(Locale.forLanguageTag("cs-CZ"))
				timestamps
			}
			provider.collect(MetricKeys.WEEKLY_STREAK,
				TimeWindow.Interval(timestamps.first(), timestamps.last())) shouldBe 2L.right()
		} finally {
			TimeZone.setDefault(originalZone)
			Locale.setDefault(originalLocale)
		}
	}

	@Test
	fun `unknown metric is unavailable for every window without reading a DAO`() = runTest {
		for (window in listOf(TimeWindow.Cumulative, TimeWindow.Interval(1_000L, 2_000L),
			TimeWindow.Rolling(60_000L))) {
			provider.collect("unsupported_metric", window) shouldBe
				StatsError.ValidationError("Metric is not supported by this provider").left()
		}
		verify {
			listOf(dailySummaryDao, explorationCellDao, explorationStreakDao, sessionSegmentDao, exportLogDao) wasNot Called
		}
	}

	@Test
	fun `malformed windows are unavailable without coerced zero or widened DAO reads`() = runTest {
		provider.collect(MetricKeys.DISTANCE_TOTAL_M, TimeWindow.Interval(2_000L, 1_000L)) shouldBe
			StatsError.ValidationError("Metric interval cannot end before it starts").left()
		provider.collect(MetricKeys.DISTANCE_TOTAL_M, TimeWindow.Rolling(-1L)) shouldBe
			StatsError.ValidationError("Rolling duration cannot be negative").left()
		verify {
			listOf(dailySummaryDao, explorationCellDao, explorationStreakDao, sessionSegmentDao, exportLogDao) wasNot Called
		}
	}

	@Test
	fun `active_days returns count of days with at least MIN_DAILY_TRIPS trips - Interval`() = runTest {
		coEvery {
			dailySummaryDao.countActiveDaysBetween(any(), any(), MetricKeys.MIN_DAILY_TRIPS)
		} returns 4L

		val result = provider.collect(
			metric = MetricKeys.ACTIVE_DAYS_TOTAL,
			window = TimeWindow.Interval(10_000L, 50_000L),
		)

		result shouldBe 4L.right()
	}

	@Test
	fun `active_days returns cumulative count for Cumulative window`() = runTest {
		coEvery { dailySummaryDao.countActiveDays(MetricKeys.MIN_DAILY_TRIPS) } returns 12L

		val result = provider.collect(
			metric = MetricKeys.ACTIVE_DAYS_TOTAL,
			window = TimeWindow.Cumulative,
		)

		result shouldBe 12L.right()
	}

	@Test
	fun `active_days Rolling delegates to interval query`() = runTest {
		coEvery {
			dailySummaryDao.countActiveDaysBetween(any(), any(), MetricKeys.MIN_DAILY_TRIPS)
		} returns 3L

		val result = provider.collect(
			metric = MetricKeys.ACTIVE_DAYS_TOTAL,
			window = TimeWindow.Rolling(durationMs = 7L * 24 * 60 * 60 * 1000L),
		)

		result shouldBe 3L.right()
		coVerify(exactly = 1) {
			dailySummaryDao.countActiveDaysBetween(any(), any(), MetricKeys.MIN_DAILY_TRIPS)
		}
	}
}
