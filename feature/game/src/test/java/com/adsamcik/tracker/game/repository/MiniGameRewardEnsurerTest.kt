package com.adsamcik.tracker.game.repository

import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.database.PointsAwardedDao
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MiniGameRewardEnsurerTest {

	@Test
	fun `points insertion uses exact per game source and is idempotent`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val dao = RecordingPointsDao()
		val progressionAttempts = mutableListOf<Pair<Int, Long>>()
		val ensurer = MiniGameRewardEnsurer(
			pointsDao = dao,
			dispatchers = TestDispatchersProvider(dispatcher),
			awardProgression = { points, time -> progressionAttempts += points to time },
			markMiniGameMetricsDirty = {},
			scheduleAchievementEvaluation = {},
		)
		val reward = GameReward(
			rewardId = miniGameRewardId("outrun", 123L),
			gameId = "outrun",
			points = 40,
			earnedAtMs = 123L,
		)

		ensurer.ensure(reward) shouldBe GameRewardEnsureResult.Created
		ensurer.ensure(reward) shouldBe GameRewardEnsureResult.AlreadyEnsured

		dao.inserted.shouldHaveSize(1)
		dao.inserted.single().source.value shouldBe "minigame:outrun"
		dao.inserted.single().time shouldBe 123L
		progressionAttempts shouldBe listOf(40 to 123L, 40 to 123L)
	}

	@Test
	fun `existing points row still retries idempotent progression repair`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val dao = RecordingPointsDao().apply { existing += 456L to "minigame:territory" }
		var progressionAttempts = 0
		val ensurer = MiniGameRewardEnsurer(
			pointsDao = dao,
			dispatchers = TestDispatchersProvider(dispatcher),
			awardProgression = { _, _ -> progressionAttempts++ },
			markMiniGameMetricsDirty = {},
			scheduleAchievementEvaluation = {},
		)

		val result = ensurer.ensure(
			GameReward(
				rewardId = miniGameRewardId("territory", 456L),
				gameId = "territory",
				points = 50,
				earnedAtMs = 456L,
			),
		)

		result shouldBe GameRewardEnsureResult.AlreadyEnsured
		dao.inserted shouldBe emptyList()
		progressionAttempts shouldBe 1
	}

	private class RecordingPointsDao : PointsAwardedDao {
		val inserted = mutableListOf<PointsAwarded>()
		val existing = mutableSetOf<Pair<Long, String>>()

		override fun countBetween(from: Long, to: Long): Double = 0.0
		override fun countBetweenFlow(from: Long, to: Long): Flow<Double> = flowOf(0.0)
		override fun hasAwardAt(time: Long, source: String): Boolean = time to source in existing
		override suspend fun insert(obj: PointsAwarded): Long {
			inserted += obj
			existing += obj.time to obj.source.value
			return inserted.size.toLong()
		}
		override suspend fun insert(obj: Collection<PointsAwarded>): List<Long> = obj.map { insert(it) }
		override suspend fun update(obj: PointsAwarded) = Unit
		override suspend fun update(obj: Collection<PointsAwarded>) = Unit
		override suspend fun delete(obj: PointsAwarded) = Unit
		override suspend fun delete(obj: Collection<PointsAwarded>) = Unit
		override fun deleteAll() = Unit
	}
}
