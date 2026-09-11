package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AchievementProgressQualifiedDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: AchievementProgressDao

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		dao = database.achievementProgressDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `qualified replacement can lower correction-sensitive progress`() = runTest {
		dao.replaceQualified(
			metricKey = METRIC,
			lastTierIndex = 2,
			lastValue = 30.0,
			updatedAt = 100L,
			authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
			authorityRevision = 4L,
			authorityDigest = "a".repeat(64),
			authorityState = AchievementProgressEntity.AUTHORITY_STATE_READY,
		)
		dao.replaceQualified(
			metricKey = METRIC,
			lastTierIndex = -1,
			lastValue = 1.0,
			updatedAt = 200L,
			authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
			authorityRevision = 5L,
			authorityDigest = "b".repeat(64),
			authorityState = AchievementProgressEntity.AUTHORITY_STATE_READY,
		)

		dao.getByMetric(METRIC) shouldBe AchievementProgressEntity(
			metricKey = METRIC,
			lastTierIndex = -1,
			lastValue = 1.0,
			updatedAt = 200L,
			authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
			authorityRevision = 5L,
			authorityDigest = "b".repeat(64),
			authorityState = AchievementProgressEntity.AUTHORITY_STATE_READY,
		)
	}

	@Test
	fun `generic monotonic write clears qualified authority`() = runTest {
		dao.replaceQualified(
			metricKey = METRIC,
			lastTierIndex = 1,
			lastValue = 7.0,
			updatedAt = 100L,
			authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
			authorityRevision = 4L,
			authorityDigest = "a".repeat(64),
			authorityState = AchievementProgressEntity.AUTHORITY_STATE_READY,
		)
		dao.upsertMonotonic(METRIC, 2, 30.0, 200L)

		dao.getByMetric(METRIC) shouldBe AchievementProgressEntity(
			metricKey = METRIC,
			lastTierIndex = 2,
			lastValue = 30.0,
			updatedAt = 200L,
		)
	}

	@Test
	fun `generic progress preserves exact unlock time until a higher tier unlocks`() = runTest {
		dao.upsertMonotonic(METRIC, 0, 3.0, 100L)
		dao.getByMetric(METRIC)?.lastUnlockedAt shouldBe 100L

		dao.upsertMonotonic(METRIC, 0, 4.0, 200L)
		dao.getByMetric(METRIC)?.lastUnlockedAt shouldBe 100L

		dao.upsertMonotonic(METRIC, 1, 7.0, 300L)
		dao.getByMetric(METRIC)?.lastUnlockedAt shouldBe 300L
	}

	@Test
	fun `qualified Steps query returns every owned metric and excludes other rows`() = runTest {
		QUALIFIED_STEPS_METRICS.forEachIndexed { index, metricKey ->
			dao.replaceQualified(
				metricKey = metricKey,
				lastTierIndex = index,
				lastValue = (index + 1).toDouble(),
				updatedAt = 100L + index,
				authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
				authorityRevision = 4L,
				authorityDigest = "a".repeat(64),
				authorityState = AchievementProgressEntity.AUTHORITY_STATE_READY,
				qualifiedNotificationClaimedTierIndex = index,
			)
		}
		dao.upsertMonotonic("distance_total_m", 0, 1_000.0, 200L)

		dao.getQualifiedStepsAchievementRows().map { it.metricKey }.toSet() shouldBe
			QUALIFIED_STEPS_METRICS.toSet()

		dao.markQualifiedStepsMaterializing(sourceEvidenceRevision = 5L, updatedAtMs = 300L) shouldBe 2
		dao.getQualifiedStepsAchievementRows().forEach { row ->
			val isGoalMetric = row.metricKey == AchievementProgressEntity.METRIC_GOAL_STREAK_DAYS ||
				row.metricKey == AchievementProgressEntity.METRIC_PERFECT_WEEKS
			row.authorityRevision shouldBe if (isGoalMetric) 5L else 4L
			row.authorityDigest shouldBe "a".repeat(64)
			row.authorityState shouldBe if (isGoalMetric) {
				AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING
			} else {
				AchievementProgressEntity.AUTHORITY_STATE_READY
			}
		}
		dao.getByMetric("distance_total_m")?.authorityState shouldBe null
	}

	private companion object {
		const val METRIC = "goal_streak_days"
		val QUALIFIED_STEPS_METRICS = listOf(
			AchievementProgressEntity.METRIC_STEPS_TOTAL,
			AchievementProgressEntity.METRIC_BEST_DAILY_STEPS,
			AchievementProgressEntity.METRIC_GOAL_STREAK_DAYS,
			AchievementProgressEntity.METRIC_PERFECT_WEEKS,
		)
	}
}
