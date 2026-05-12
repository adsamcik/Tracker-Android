package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefaultAchievementMetricsProviderTest {

	private val dailySummaryDao: DailySummaryDao = mockk()
	private val explorationCellDao: ExplorationCellDao = mockk()
	private val explorationStreakDao: ExplorationStreakDao = mockk()
	private val sessionSegmentDao: SessionSegmentDao = mockk()
	private val exportLogDao: ExportLogDao = mockk()

	private lateinit var provider: DefaultAchievementMetricsProvider

	@BeforeEach
	fun setUp() {
		provider = DefaultAchievementMetricsProvider(
			dailySummaryDao = dailySummaryDao,
			explorationCellDao = explorationCellDao,
			explorationStreakDao = explorationStreakDao,
			sessionSegmentDao = sessionSegmentDao,
			exportLogDao = exportLogDao,
		)
	}

	private fun stubAllZero() {
		coEvery { explorationCellDao.countAtLevelLong(14) } returns 0L
		coEvery { explorationCellDao.countAtLevelLong(10) } returns 0L
		coEvery { explorationCellDao.getDistinctSeasonBitmasks(14) } returns emptyList()
		coEvery { dailySummaryDao.sumTotalDistance() } returns 0L
		coEvery { dailySummaryDao.sumTotalSteps() } returns 0L
		coEvery { dailySummaryDao.maxDailySteps() } returns 0L
		coEvery { dailySummaryDao.sumTotalTrips() } returns 0L
		coEvery { sessionSegmentDao.maxSegmentDistance() } returns 0L
		coEvery { sessionSegmentDao.countDistinctActivities() } returns 0L
		coEvery { sessionSegmentDao.countByActivity(any()) } returns 0L
		coEvery { explorationStreakDao.getByType(any()) } returns null
		coEvery { exportLogDao.countTotal() } returns 0L
	}

	@Test
	fun `collect returns zero metrics when database is empty`() = runTest {
		stubAllZero()

		val metrics = provider.collect()

		metrics["cells_discovered"] shouldBe 0L
		metrics["total_steps"] shouldBe 0L
		metrics["total_distance_km"] shouldBe 0L
		metrics["daily_streak"] shouldBe 0L
		metrics["total_exports"] shouldBe 0L
	}

	@Test
	fun `collect returns correct cell count`() = runTest {
		stubAllZero()
		coEvery { explorationCellDao.countAtLevelLong(14) } returns 42L

		val metrics = provider.collect()

		metrics["cells_discovered"] shouldBe 42L
	}

	@Test
	fun `collect converts distance from meters to km`() = runTest {
		stubAllZero()
		coEvery { dailySummaryDao.sumTotalDistance() } returns 15_500L

		val metrics = provider.collect()

		metrics["total_distance_km"] shouldBe 15L // 15500 / 1000 = 15 (integer division)
	}

	@Test
	fun `collect computes seasons from bitmasks`() = runTest {
		stubAllZero()
		// spring=1, summer=2, autumn=4 → OR'd = 7, which has 3 bits set
		coEvery { explorationCellDao.getDistinctSeasonBitmasks(14) } returns listOf(1, 2, 4)

		val metrics = provider.collect()

		metrics["seasons_explored"] shouldBe 3L
	}

	@Test
	fun `collect reads streak best count`() = runTest {
		stubAllZero()
		coEvery { explorationStreakDao.getByType("DAILY_DISCOVERY") } returns ExplorationStreakEntity(
			type = "DAILY_DISCOVERY",
			currentCount = 3,
			bestCount = 7,
			lastIncrementDay = 19000L,
			updatedAt = 0L,
		)

		val metrics = provider.collect()

		metrics["daily_streak"] shouldBe 7L
	}

	@Test
	fun `collect sums walking trips from multiple activity types`() = runTest {
		stubAllZero()
		coEvery { sessionSegmentDao.countByActivity(2) } returns 10L  // ON_FOOT
		coEvery { sessionSegmentDao.countByActivity(7) } returns 20L  // WALKING
		coEvery { sessionSegmentDao.countByActivity(8) } returns 5L   // RUNNING
		coEvery { sessionSegmentDao.countByActivity(-2) } returns 3L  // Native WALKING
		coEvery { sessionSegmentDao.countByActivity(-3) } returns 2L  // Native RUNNING

		val metrics = provider.collect()

		metrics["walking_trips"] shouldBe 40L
	}

	@Test
	fun `collect sums cycling trips from GMS and native bicycle ids`() = runTest {
		stubAllZero()
		coEvery { sessionSegmentDao.countByActivity(1) } returns 6L
		coEvery { sessionSegmentDao.countByActivity(-4) } returns 4L

		val metrics = provider.collect()

		metrics["cycling_trips"] shouldBe 10L
	}
}
