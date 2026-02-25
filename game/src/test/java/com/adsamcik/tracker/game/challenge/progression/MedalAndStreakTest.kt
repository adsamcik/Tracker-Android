package com.adsamcik.tracker.game.challenge.progression

import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.Medal
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Medal, Streak & XpCalculator")
class MedalAndStreakTest {

	@Nested
	@DisplayName("Medal.fromCompletion")
	inner class MedalTests {

		@Test
		fun `GOLD when completed at 30 percent of time`() {
			// 30% of 1000 = 300
			Medal.fromCompletion(completedAt = 300L, startTime = 0L, endTime = 1000L) shouldBe Medal.GOLD
		}

		@Test
		fun `SILVER when completed at 60 percent of time`() {
			Medal.fromCompletion(completedAt = 600L, startTime = 0L, endTime = 1000L) shouldBe Medal.SILVER
		}

		@Test
		fun `BRONZE when completed at 90 percent of time`() {
			Medal.fromCompletion(completedAt = 900L, startTime = 0L, endTime = 1000L) shouldBe Medal.BRONZE
		}

		@Test
		fun `NONE when completedAt is null`() {
			Medal.fromCompletion(completedAt = null, startTime = 0L, endTime = 1000L) shouldBe Medal.NONE
		}

		@Test
		fun `GOLD at exactly 40 percent boundary`() {
			Medal.fromCompletion(completedAt = 400L, startTime = 0L, endTime = 1000L) shouldBe Medal.GOLD
		}

		@Test
		fun `SILVER at exactly 70 percent boundary`() {
			Medal.fromCompletion(completedAt = 700L, startTime = 0L, endTime = 1000L) shouldBe Medal.SILVER
		}

		@Test
		fun `BRONZE when duration is zero`() {
			Medal.fromCompletion(completedAt = 100L, startTime = 100L, endTime = 100L) shouldBe Medal.BRONZE
		}
	}

	@Nested
	@DisplayName("StreakManager.getMilestoneResId")
	inner class StreakMilestoneTests {

		@Test
		fun `count 0 returns null`() {
			StreakManager.getMilestoneResId(0).shouldBeNull()
		}

		@Test
		fun `count 2 returns null`() {
			StreakManager.getMilestoneResId(2).shouldBeNull()
		}

		@Test
		fun `count 3 returns non-null resource`() {
			StreakManager.getMilestoneResId(3).shouldNotBeNull()
		}

		@Test
		fun `count 7 returns different resource than count 3`() {
			val res3 = StreakManager.getMilestoneResId(3)
			val res7 = StreakManager.getMilestoneResId(7)
			res7.shouldNotBeNull()
			(res7 != res3) shouldBe true
		}

		@Test
		fun `count 14 returns non-null resource`() {
			StreakManager.getMilestoneResId(14).shouldNotBeNull()
		}

		@Test
		fun `count 30 returns non-null resource`() {
			StreakManager.getMilestoneResId(30).shouldNotBeNull()
		}

		@Test
		fun `count 100 returns same as count 30`() {
			StreakManager.getMilestoneResId(100) shouldBe StreakManager.getMilestoneResId(30)
		}
	}

	@Nested
	@DisplayName("XpCalculator")
	inner class XpCalculatorTests {

		private val calculator = XpCalculator()

		@Nested
		@DisplayName("Session XP")
		inner class SessionXpTests {

			@Test
			fun `session with distance steps and duration yields positive capped XP`() {
				val session = mockk<TrackerSession>(relaxed = true) {
					every { distanceOnFootInM } returns 1000f
					every { steps } returns 2000
					every { start } returns 0L
					every { end } returns 30 * 60_000L // 30 minutes
				}
				val award = calculator.calculateSessionXp(session)
				award.amount shouldBeGreaterThan 0
				award.amount shouldBeLessThanOrEqual XpCalculator.SESSION_CAP
			}

			@Test
			fun `vehicle or still session yields 0 XP`() {
				val session = mockk<TrackerSession>(relaxed = true)
				calculator.calculateSessionXp(session, isVehicleOrStill = true).amount shouldBe 0
			}
		}

		@Nested
		@DisplayName("Challenge XP")
		inner class ChallengeXpTests {

			@Test
			fun `MEDIUM BRONZE streak 0 yields 200`() {
				val award = calculator.calculateChallengeXp(
					difficulty = ChallengeDifficulty.MEDIUM,
					medal = Medal.BRONZE,
					streakCount = 0,
				)
				award.amount shouldBe 200
			}

			@Test
			fun `MEDIUM GOLD streak 0 yields 400`() {
				val award = calculator.calculateChallengeXp(
					difficulty = ChallengeDifficulty.MEDIUM,
					medal = Medal.GOLD,
					streakCount = 0,
				)
				award.amount shouldBe 400
			}

			@Test
			fun `VERY_HARD GOLD streak 10 yields 2250`() {
				// 750 * 2.0 * (1.0 + 10*0.05) = 750 * 2.0 * 1.5 = 2250
				val award = calculator.calculateChallengeXp(
					difficulty = ChallengeDifficulty.VERY_HARD,
					medal = Medal.GOLD,
					streakCount = 10,
				)
				award.amount shouldBe 2250
			}
		}

		@Nested
		@DisplayName("Goal XP")
		inner class GoalXpTests {

			@Test
			fun `daily goal yields 75 XP`() {
				calculator.calculateGoalXp(isWeekly = false).amount shouldBe 75
			}

			@Test
			fun `weekly goal yields 200 XP`() {
				calculator.calculateGoalXp(isWeekly = true).amount shouldBe 200
			}
		}
	}
}
