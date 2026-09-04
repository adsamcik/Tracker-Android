package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.data.geo.CountryBoundaryLookup
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultAchievementMetricsProviderStepsTest {
	private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
	private val achievementProgressDao = mockk<AchievementProgressDao>(relaxed = true)
	private val provider = DefaultAchievementMetricsProvider(
		dailySummaryDao = dailySummaryDao,
		explorationCellDao = mockk<ExplorationCellDao>(relaxed = true),
		explorationStreakDao = mockk<ExplorationStreakDao>(relaxed = true),
		sessionSegmentDao = mockk<SessionSegmentDao>(relaxed = true),
		exportLogDao = mockk<ExportLogDao>(relaxed = true),
		miniGameScoreDao = mockk<MiniGameScoreDao>(relaxed = true),
		locationSampleDao = mockk<LocationSampleDao>(relaxed = true),
		countryLookup = mockk<CountryBoundaryLookup>(relaxed = true),
		achievementProgressDao = achievementProgressDao,
	)

	@Test
	fun `Steps-derived achievements are absent until retained evidence can qualify them`() = runTest {
		coEvery { dailySummaryDao.sumTotalDistance() } returns 321L
		coEvery { dailySummaryDao.sumTotalSteps() } returns 999_999L
		coEvery { dailySummaryDao.maxDailySteps() } returns 888_888L

		val metrics = provider.collect().asMap()

		metrics[MetricKey.DISTANCE_TOTAL_M] shouldBe 321.0
		metrics[MetricKey.STEPS_TOTAL] shouldBe null
		metrics[MetricKey.BEST_DAILY_STEPS] shouldBe null
		metrics[MetricKey.PERFECT_WEEKS] shouldBe null
		metrics[MetricKey.GOAL_STREAK_DAYS] shouldBe null
		metrics[MetricKey.PLAYER_LEVEL] shouldBe null
		metrics[MetricKey.BEST_DAY_XP] shouldBe null
		metrics[MetricKey.XP_SOURCES_USED] shouldBe null
		coVerify(exactly = 0) { dailySummaryDao.sumTotalSteps() }
		coVerify(exactly = 0) { dailySummaryDao.maxDailySteps() }
	}

	@Test
	fun `legacy Steps progress cannot inflate meta achievements`() = runTest {
		val distanceTiers = AchievementCatalog.byMetric(MetricKey.DISTANCE_TOTAL_M).size
		coEvery { achievementProgressDao.getAll() } returns listOf(
			progress(MetricKey.STEPS_TOTAL, lastTierIndex = 6),
			progress(MetricKey.BEST_DAILY_STEPS, lastTierIndex = 4),
			progress(MetricKey.PLAYER_LEVEL, lastTierIndex = 7),
			progress(MetricKey.ACHIEVEMENTS_UNLOCKED, lastTierIndex = 3),
			progress(MetricKey.CATEGORIES_COMPLETED, lastTierIndex = 2),
			progress("imported_unknown", lastTierIndex = Int.MAX_VALUE),
			progress(MetricKey.DISTANCE_TOTAL_M, lastTierIndex = Int.MAX_VALUE),
		)

		val metrics = provider.collect().asMap()

		metrics[MetricKey.ACHIEVEMENTS_UNLOCKED] shouldBe distanceTiers.toDouble()
		metrics[MetricKey.CATEGORIES_COMPLETED] shouldBe 0.0
	}

	private fun progress(metric: MetricKey, lastTierIndex: Int) =
		progress(metric.storageKey, lastTierIndex)

	private fun progress(metricKey: String, lastTierIndex: Int) = AchievementProgressEntity(
		metricKey = metricKey,
		lastTierIndex = lastTierIndex,
		lastValue = 1_000_000.0,
		updatedAt = 1L,
	)
}
