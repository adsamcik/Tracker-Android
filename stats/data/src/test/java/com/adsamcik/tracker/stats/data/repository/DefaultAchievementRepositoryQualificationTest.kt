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
	fun `snapshots expose all four ready source-qualified Steps metrics`() = runTest {
		val rows = QUALIFIED_STEPS_METRICS.mapIndexed { index, metric ->
			qualifiedProgress(metric, index)
		}
		coEvery { progressDao.getAll() } returns rows

		val snapshots = repository.getAllSnapshots()

		QUALIFIED_STEPS_METRICS.forEachIndexed { index, metric ->
			snapshots.count { it.metric == metric } shouldBe AchievementCatalog.byMetric(metric).size
			snapshots.first { it.metric == metric }.currentValue shouldBe (index + 1).toDouble()
		}
	}

	@Test
	fun `snapshots hide Steps metrics without exact ready provenance and a claimed tier`() = runTest {
		QUALIFIED_STEPS_METRICS.forEach { metric ->
			val unavailableRows = listOf(
				progress(metric, 0),
				qualifiedProgress(
					metric,
					0,
					authorityState = AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING,
				),
				qualifiedProgress(
					metric,
					0,
					authorityState = AchievementProgressEntity.AUTHORITY_STATE_UNVERIFIABLE,
				),
				qualifiedProgress(metric, 0, claimedTierIndex = null),
				AchievementProgressEntity(
					metricKey = metric.storageKey,
					lastTierIndex = 0,
					lastValue = 1.0,
					updatedAt = 2L,
					authorityKind = "OTHER_AUTHORITY",
					authorityRevision = 3L,
					authorityDigest = "a".repeat(64),
					authorityState = AchievementProgressEntity.AUTHORITY_STATE_READY,
				),
			)

			unavailableRows.forEach { row ->
				coEvery { progressDao.getAll() } returns listOf(row)

				(repository.getAllSnapshots().none { it.metric == metric }) shouldBe true
			}
		}
	}

	@Test
	fun `recent unlocks skip quarantined rows without consuming the caller limit`() = runTest {
		every { progressDao.getAllFlow() } returns flowOf(
			listOf(
				qualifiedProgress(MetricKey.GOAL_STREAK_DAYS, 0, updatedAt = 40L),
				progress(MetricKey.STEPS_TOTAL, 0, updatedAt = 30L),
				progress(MetricKey.PLAYER_LEVEL, 0, updatedAt = 20L),
				progress(MetricKey.DISTANCE_TOTAL_M, 0, updatedAt = 10L, unlockedAt = 5L),
				progress(MetricKey.SESSIONS_TOTAL, 0, updatedAt = 5L, unlockedAt = 10L),
			),
		)

		val recent = repository.observeRecentUnlocks(limit = 2).first()

		recent.map { it.metric } shouldContainExactly listOf(
			MetricKey.SESSIONS_TOTAL,
			MetricKey.DISTANCE_TOTAL_M,
		)
		recent.map { it.updatedAt } shouldContainExactly listOf(10L, 5L)
	}

	private fun progress(
		metric: MetricKey,
		lastTierIndex: Int,
		updatedAt: Long = 1L,
		unlockedAt: Long = updatedAt,
	) = progress(metric.storageKey, lastTierIndex, updatedAt, unlockedAt)

	private fun progress(
		metricKey: String,
		lastTierIndex: Int,
		updatedAt: Long = 1L,
		unlockedAt: Long = updatedAt,
	) =
		AchievementProgressEntity(
			metricKey = metricKey,
			lastTierIndex = lastTierIndex,
			lastValue = 1_000_000.0,
			updatedAt = updatedAt,
			lastUnlockedAt = unlockedAt.takeIf { lastTierIndex >= 0 },
		)

	private fun qualifiedProgress(
		metric: MetricKey,
		lastTierIndex: Int,
		authorityState: String = AchievementProgressEntity.AUTHORITY_STATE_READY,
		updatedAt: Long = 2L,
		claimedTierIndex: Int? = lastTierIndex,
	) = AchievementProgressEntity(
		metricKey = metric.storageKey,
		lastTierIndex = lastTierIndex,
		lastValue = (lastTierIndex + 1).toDouble(),
		updatedAt = updatedAt,
		authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
		authorityRevision = 3L,
		authorityDigest = "a".repeat(64),
		authorityState = authorityState,
		qualifiedNotificationClaimedTierIndex = claimedTierIndex,
	)

	private companion object {
		val QUALIFIED_STEPS_METRICS = listOf(
			MetricKey.STEPS_TOTAL,
			MetricKey.BEST_DAILY_STEPS,
			MetricKey.GOAL_STREAK_DAYS,
			MetricKey.PERFECT_WEEKS,
		)
	}
}
