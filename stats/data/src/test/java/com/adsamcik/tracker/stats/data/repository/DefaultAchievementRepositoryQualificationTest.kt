package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricKey
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultAchievementRepositoryQualificationTest {
	private val progressDao = mockk<AchievementProgressDao>()
	private val repository = DefaultAchievementRepository(progressDao)

	@Test
	fun `summary counts only catalog-bounded source-qualified direct progress`() = runTest {
		val rows = listOf(
			progress(MetricKey.STEPS_TOTAL, 6),
			progress(MetricKey.PLAYER_LEVEL, 7),
			progress(MetricKey.ACHIEVEMENTS_UNLOCKED, 3),
			progress("imported_unknown", Int.MAX_VALUE),
			progress(MetricKey.DISTANCE_TOTAL_M, Int.MAX_VALUE),
		)
		every { progressDao.getAllFlow() } returns flowOf(rows)

		val summary = repository.observeSummary().first()
		val visibleDefinitions = AchievementCatalog.definitions.filter {
			AchievementMetricQualification.isTrustedPersistedProgress(it.metric)
		}

		summary.total shouldBe visibleDefinitions.size
		summary.unlocked shouldBe AchievementCatalog.byMetric(MetricKey.DISTANCE_TOTAL_M).size
		summary.inProgress shouldBe summary.total - summary.unlocked
	}

	@Test
	fun `snapshots quarantine unverifiable Steps XP and derived meta history`() = runTest {
		val rows = listOf(
			progress(MetricKey.STEPS_TOTAL, 6),
			progress(MetricKey.BEST_DAY_XP, 4),
			progress(MetricKey.CATEGORIES_COMPLETED, 2),
			progress(MetricKey.DISTANCE_TOTAL_M, 0),
		)
		coEvery { progressDao.getAll() } returns rows

		val snapshots = repository.getAllSnapshots()

		(snapshots.none { it.metric == MetricKey.STEPS_TOTAL }) shouldBe true
		(snapshots.none { it.metric == MetricKey.BEST_DAY_XP }) shouldBe true
		(snapshots.none { it.metric in AchievementMetricQualification.derivedMetaMetrics }) shouldBe true
		(snapshots.first { it.metric == MetricKey.DISTANCE_TOTAL_M }.isUnlocked) shouldBe true
	}

	@Test
	fun `recent unlocks skip quarantined rows without consuming the caller limit`() = runTest {
		every { progressDao.getAllFlow() } returns flowOf(
			listOf(
				progress(MetricKey.STEPS_TOTAL, 0, updatedAt = 30L),
				progress(MetricKey.PLAYER_LEVEL, 0, updatedAt = 20L),
				progress(MetricKey.DISTANCE_TOTAL_M, 0, updatedAt = 10L),
				progress(MetricKey.SESSIONS_TOTAL, 0, updatedAt = 5L),
			),
		)

		val recent = repository.observeRecentUnlocks(limit = 2).first()

		recent.map { it.metric } shouldContainExactly listOf(
			MetricKey.DISTANCE_TOTAL_M,
			MetricKey.SESSIONS_TOTAL,
		)
	}

	private fun progress(metric: MetricKey, lastTierIndex: Int, updatedAt: Long = 1L) =
		progress(metric.storageKey, lastTierIndex, updatedAt)

	private fun progress(metricKey: String, lastTierIndex: Int, updatedAt: Long = 1L) =
		AchievementProgressEntity(
			metricKey = metricKey,
			lastTierIndex = lastTierIndex,
			lastValue = 1_000_000.0,
			updatedAt = updatedAt,
		)
}
