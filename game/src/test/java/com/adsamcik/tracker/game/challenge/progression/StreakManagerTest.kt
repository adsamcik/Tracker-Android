package com.adsamcik.tracker.game.challenge.progression

import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.dao.ChallengeStreakDao
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeStreakEntity
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StreakManager")
class StreakManagerTest {

	private lateinit var streakManager: StreakManager
	private lateinit var database: ChallengeDatabase
	private lateinit var streakDao: ChallengeStreakDao

	@BeforeEach
	fun setup() {
		streakManager = StreakManager()
		database = mockk(relaxed = true)
		streakDao = mockk(relaxed = true)
		every { database.challengeStreakDao() } returns streakDao
	}

	@AfterEach
	fun teardown() {
		unmockkAll()
	}

	@Nested
	@DisplayName("onChallengeCompleted")
	inner class OnChallengeCompleted {
		@Test
		fun `increments streak count`() = runTest {
			val current = ChallengeStreakEntity(currentCount = 5, bestCount = 10, freezeCount = 0)
			coEvery { streakDao.get() } returns current

			val result = streakManager.onChallengeCompleted(database, 1000L)
			result.currentCount shouldBe 6
		}

		@Test
		fun `updates best count when current exceeds it`() = runTest {
			val current = ChallengeStreakEntity(currentCount = 9, bestCount = 9, freezeCount = 0)
			coEvery { streakDao.get() } returns current

			val result = streakManager.onChallengeCompleted(database, 1000L)
			result.bestCount shouldBe 10
		}

		@Test
		fun `keeps best count when current is below`() = runTest {
			val current = ChallengeStreakEntity(currentCount = 3, bestCount = 15, freezeCount = 0)
			coEvery { streakDao.get() } returns current

			val result = streakManager.onChallengeCompleted(database, 1000L)
			result.bestCount shouldBe 15
		}

		@Test
		fun `earns freeze at COMPLETIONS_PER_FREEZE boundary`() = runTest {
			val current = ChallengeStreakEntity(currentCount = 6, bestCount = 6, freezeCount = 0)
			coEvery { streakDao.get() } returns current

			val result = streakManager.onChallengeCompleted(database, 1000L)
			// 6+1=7, 7%7==0 -> earn freeze
			result.freezeCount shouldBe 1
		}

		@Test
		fun `does not earn freeze when not on boundary`() = runTest {
			val current = ChallengeStreakEntity(currentCount = 5, bestCount = 5, freezeCount = 0)
			coEvery { streakDao.get() } returns current

			val result = streakManager.onChallengeCompleted(database, 1000L)
			// 5+1=6, 6%7!=0
			result.freezeCount shouldBe 0
		}

		@Test
		fun `does not exceed MAX_FREEZES`() = runTest {
			val current = ChallengeStreakEntity(currentCount = 20, bestCount = 20, freezeCount = 3)
			coEvery { streakDao.get() } returns current

			val result = streakManager.onChallengeCompleted(database, 1000L)
			// 21%7==0, but already at max (3)
			result.freezeCount shouldBe 3
		}

		@Test
		fun `updates last completion time`() = runTest {
			val current = ChallengeStreakEntity(currentCount = 0, bestCount = 0)
			coEvery { streakDao.get() } returns current

			val result = streakManager.onChallengeCompleted(database, 42000L)
			result.lastCompletionTime shouldBe 42000L
		}

		@Test
		fun `persists updated entity`() = runTest {
			val current = ChallengeStreakEntity(currentCount = 0, bestCount = 0)
			coEvery { streakDao.get() } returns current

			streakManager.onChallengeCompleted(database, 1000L)
			coVerify(exactly = 1) { streakDao.update(any<ChallengeStreakEntity>()) }
		}

		@Test
		fun `handles null dao result as fresh entity`() = runTest {
			coEvery { streakDao.get() } returns null

			val result = streakManager.onChallengeCompleted(database, 1000L)
			result.currentCount shouldBe 1
			result.bestCount shouldBe 1
		}
	}

	@Nested
	@DisplayName("onChallengesExpired")
	inner class OnChallengesExpired {
		@Test
		fun `uses freeze when available`() = runTest {
			val current = ChallengeStreakEntity(currentCount = 10, bestCount = 10, freezeCount = 2)
			coEvery { streakDao.get() } returns current

			val (result, froze) = streakManager.onChallengesExpired(database)
			froze shouldBe true
			result.freezeCount shouldBe 1
			result.currentCount shouldBe 10 // streak preserved
		}

		@Test
		fun `breaks streak when no freeze available`() = runTest {
			val current = ChallengeStreakEntity(currentCount = 10, bestCount = 10, freezeCount = 0)
			coEvery { streakDao.get() } returns current

			val (result, froze) = streakManager.onChallengesExpired(database)
			froze shouldBe false
			result.currentCount shouldBe 0
		}

		@Test
		fun `handles null dao result`() = runTest {
			coEvery { streakDao.get() } returns null

			val (result, froze) = streakManager.onChallengesExpired(database)
			froze shouldBe false
			result.currentCount shouldBe 0
		}
	}

	@Nested
	@DisplayName("getMilestoneResId")
	inner class GetMilestoneResId {
		@Test
		fun `returns null for count below 3`() {
			StreakManager.getMilestoneResId(0) shouldBe null
			StreakManager.getMilestoneResId(1) shouldBe null
			StreakManager.getMilestoneResId(2) shouldBe null
		}

		@Test
		fun `returns milestone_3 for count 3-6`() {
			val resId = StreakManager.getMilestoneResId(3)
			resId shouldBe com.adsamcik.tracker.game.R.string.game_streak_milestone_3
			StreakManager.getMilestoneResId(6) shouldBe resId
		}

		@Test
		fun `returns milestone_7 for count 7-13`() {
			val resId = StreakManager.getMilestoneResId(7)
			resId shouldBe com.adsamcik.tracker.game.R.string.game_streak_milestone_7
			StreakManager.getMilestoneResId(13) shouldBe resId
		}

		@Test
		fun `returns milestone_14 for count 14-29`() {
			val resId = StreakManager.getMilestoneResId(14)
			resId shouldBe com.adsamcik.tracker.game.R.string.game_streak_milestone_14
			StreakManager.getMilestoneResId(29) shouldBe resId
		}

		@Test
		fun `returns milestone_30 for count 30+`() {
			val resId = StreakManager.getMilestoneResId(30)
			resId shouldBe com.adsamcik.tracker.game.R.string.game_streak_milestone_30
			StreakManager.getMilestoneResId(100) shouldBe resId
		}
	}
}
