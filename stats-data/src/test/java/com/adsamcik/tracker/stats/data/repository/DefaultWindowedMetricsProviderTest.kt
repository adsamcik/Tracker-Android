package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefaultWindowedMetricsProviderTest {

	private val dailySummaryDao: DailySummaryDao = mockk()
	private val explorationCellDao: ExplorationCellDao = mockk()
	private val explorationStreakDao: ExplorationStreakDao = mockk()
	private val sessionSegmentDao: SessionSegmentDao = mockk()
	private val exportLogDao: ExportLogDao = mockk()

	private lateinit var provider: DefaultWindowedMetricsProvider

	@BeforeEach
	fun setUp() {
		provider = DefaultWindowedMetricsProvider(
			dailySummaryDao = dailySummaryDao,
			explorationCellDao = explorationCellDao,
			explorationStreakDao = explorationStreakDao,
			sessionSegmentDao = sessionSegmentDao,
			exportLogDao = exportLogDao,
		)
	}

	@Test
	fun `collect returns cumulative steps`() = runTest {
		coEvery { dailySummaryDao.sumTotalSteps() } returns 12_345L

		val result = provider.collect(MetricKeys.TOTAL_STEPS, TimeWindow.Cumulative)

		result shouldBe 12_345L
	}

	@Test
	fun `collect returns interval distance in km`() = runTest {
		coEvery { dailySummaryDao.sumTotalDistanceBetween(1_000L, 5_000L) } returns 15_500L

		val result = provider.collect(
			metric = MetricKeys.TOTAL_DISTANCE_KM,
			window = TimeWindow.Interval(1_000L, 5_000L),
		)

		result shouldBe 15L
	}

	@Test
	fun `collect returns interval active minutes`() = runTest {
		coEvery { dailySummaryDao.sumActiveMinutesBetween(10L, 50L) } returns 42L

		val result = provider.collect(
			metric = MetricKeys.ACTIVE_MINUTES,
			window = TimeWindow.Interval(10L, 50L),
		)

		result shouldBe 42L
	}

	@Test
	fun `collect returns interval discovered cells`() = runTest {
		coEvery { explorationCellDao.countDiscoveredBetween(100L, 200L, 14) } returns 7L

		val result = provider.collect(
			metric = MetricKeys.CELLS_DISCOVERED,
			window = TimeWindow.Interval(100L, 200L),
		)

		result shouldBe 7L
	}

	@Test
	fun `collect returns interval on foot distance`() = runTest {
		coEvery { sessionSegmentDao.sumDistanceByActivitiesBetween(100L, 200L, any()) } returns 1_250L

		val result = provider.collect(
			metric = MetricKeys.DISTANCE_ON_FOOT_M,
			window = TimeWindow.Interval(100L, 200L),
		)

		result shouldBe 1_250L
	}

	@Test
	fun `collect returns interval walking trips`() = runTest {
		coEvery { sessionSegmentDao.countByActivitiesBetween(100L, 200L, any()) } returns 11L

		val result = provider.collect(
			metric = MetricKeys.WALKING_TRIPS,
			window = TimeWindow.Interval(100L, 200L),
		)

		result shouldBe 11L
	}

	@Test
	fun `collect returns interval cycling trips`() = runTest {
		coEvery { sessionSegmentDao.countByActivitiesBetween(1L, 2L, any()) } returns 9L

		val result = provider.collect(
			metric = MetricKeys.CYCLING_TRIPS,
			window = TimeWindow.Interval(1L, 2L),
		)

		result shouldBe 9L
	}

	@Test
	fun `collect rolling delegates to interval query`() = runTest {
		coEvery { dailySummaryDao.sumStepsBetween(any(), any()) } returns 999L

		val result = provider.collect(
			metric = MetricKeys.STEPS,
			window = TimeWindow.Rolling(durationMs = 60_000L),
		)

		result shouldBe 999L
		coVerify(exactly = 1) { dailySummaryDao.sumStepsBetween(any(), any()) }
	}

	@Test
	fun `collect returns cumulative for non windowable streak metric`() = runTest {
		coEvery { explorationStreakDao.getByType("DAILY_DISCOVERY") } returns ExplorationStreakEntity(
			type = "DAILY_DISCOVERY",
			currentCount = 3,
			bestCount = 17,
			lastIncrementDay = 19_000L,
			updatedAt = 0L,
		)

		val result = provider.collect(
			metric = MetricKeys.DAILY_STREAK,
			window = TimeWindow.Interval(1_000L, 2_000L),
		)

		result shouldBe 17L
	}

	@Test
	fun `collect returns zero for unsupported metric`() = runTest {
		val result = provider.collect(
			metric = "unsupported_metric",
			window = TimeWindow.Interval(1_000L, 2_000L),
		)

		result shouldBe 0L
	}

	@Test
	fun `active_days returns count of days with at least MIN_DAILY_TRIPS trips - Interval`() = runTest {
		coEvery {
			dailySummaryDao.countActiveDaysBetween(10_000L, 50_000L, MetricKeys.MIN_DAILY_TRIPS)
		} returns 4L

		val result = provider.collect(
			metric = MetricKeys.ACTIVE_DAYS,
			window = TimeWindow.Interval(10_000L, 50_000L),
		)

		result shouldBe 4L
	}

	@Test
	fun `active_days returns cumulative count for Cumulative window`() = runTest {
		coEvery { dailySummaryDao.countActiveDays(MetricKeys.MIN_DAILY_TRIPS) } returns 12L

		val result = provider.collect(
			metric = MetricKeys.ACTIVE_DAYS,
			window = TimeWindow.Cumulative,
		)

		result shouldBe 12L
	}

	@Test
	fun `active_days Rolling delegates to interval query`() = runTest {
		coEvery {
			dailySummaryDao.countActiveDaysBetween(any(), any(), MetricKeys.MIN_DAILY_TRIPS)
		} returns 3L

		val result = provider.collect(
			metric = MetricKeys.ACTIVE_DAYS,
			window = TimeWindow.Rolling(durationMs = 7L * 24 * 60 * 60 * 1000L),
		)

		result shouldBe 3L
		coVerify(exactly = 1) {
			dailySummaryDao.countActiveDaysBetween(any(), any(), MetricKeys.MIN_DAILY_TRIPS)
		}
	}
}
