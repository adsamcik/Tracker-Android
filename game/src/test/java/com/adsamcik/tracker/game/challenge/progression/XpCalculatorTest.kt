package com.adsamcik.tracker.game.challenge.progression

import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.Medal
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("XpCalculator")
class XpCalculatorTest {

	private val calculator = XpCalculator()

	@Nested
	@DisplayName("calculateSessionXp")
	inner class SessionXp {

		@Test
		fun `vehicle or still session yields 0 XP`() {
			val session = makeSession(distanceOnFoot = 5000f, steps = 1000, durationMs = 60_000L * 30)
			val award = calculator.calculateSessionXp(session, isVehicleOrStill = true)
			award.amount shouldBe 0
			award.source shouldBe "SESSION"
		}

		@Test
		fun `zero activity yields 0 XP`() {
			val session = makeSession(distanceOnFoot = 0f, steps = 0, durationMs = 0L)
			val award = calculator.calculateSessionXp(session)
			award.amount shouldBe 0
		}

		@Test
		fun `normal walk session produces positive XP`() {
			// 2km walk, 3000 steps, 30 min
			val session = makeSession(distanceOnFoot = 2000f, steps = 3000, durationMs = 60_000L * 30)
			val award = calculator.calculateSessionXp(session)
			award.amount shouldBeGreaterThan 0
			award.source shouldBe "SESSION"
		}

		@Test
		fun `session XP is capped at SESSION_CAP`() {
			// Huge session
			val session = makeSession(distanceOnFoot = 50_000f, steps = 100_000, durationMs = 60_000L * 300)
			val award = calculator.calculateSessionXp(session)
			award.amount shouldBeLessThanOrEqual XpCalculator.SESSION_CAP
		}

		@Test
		fun `duration capped at 120 minutes for XP calculation`() {
			val short = makeSession(distanceOnFoot = 0f, steps = 0, durationMs = 60_000L * 120)
			val long = makeSession(distanceOnFoot = 0f, steps = 0, durationMs = 60_000L * 240)
			val shortXp = calculator.calculateSessionXp(short)
			val longXp = calculator.calculateSessionXp(long)
			shortXp.amount shouldBe longXp.amount
		}
	}

	@Nested
	@DisplayName("calculateChallengeXp")
	inner class ChallengeXp {

		@Test
		fun `NONE medal yields 0 XP`() {
			val award = calculator.calculateChallengeXp(ChallengeDifficulty.MEDIUM, Medal.NONE, 0)
			award.amount shouldBe 0
		}

		@Test
		fun `harder difficulty gives more XP for same medal`() {
			val easy = calculator.calculateChallengeXp(ChallengeDifficulty.EASY, Medal.BRONZE, 0)
			val hard = calculator.calculateChallengeXp(ChallengeDifficulty.HARD, Medal.BRONZE, 0)
			hard.amount shouldBeGreaterThan easy.amount
		}

		@Test
		fun `higher medal gives more XP for same difficulty`() {
			val bronze = calculator.calculateChallengeXp(ChallengeDifficulty.MEDIUM, Medal.BRONZE, 0)
			val gold = calculator.calculateChallengeXp(ChallengeDifficulty.MEDIUM, Medal.GOLD, 0)
			gold.amount shouldBeGreaterThan bronze.amount
		}

		@Test
		fun `streak bonus increases XP`() {
			val noStreak = calculator.calculateChallengeXp(ChallengeDifficulty.MEDIUM, Medal.BRONZE, 0)
			val withStreak = calculator.calculateChallengeXp(ChallengeDifficulty.MEDIUM, Medal.BRONZE, 10)
			withStreak.amount shouldBeGreaterThan noStreak.amount
		}

		@Test
		fun `streak bonus caps at 20`() {
			val at20 = calculator.calculateChallengeXp(ChallengeDifficulty.MEDIUM, Medal.BRONZE, 20)
			val at100 = calculator.calculateChallengeXp(ChallengeDifficulty.MEDIUM, Medal.BRONZE, 100)
			at20.amount shouldBe at100.amount
		}

		@Test
		fun `source is CHALLENGE`() {
			val award = calculator.calculateChallengeXp(ChallengeDifficulty.MEDIUM, Medal.BRONZE, 0)
			award.source shouldBe "CHALLENGE"
		}
	}

	@Nested
	@DisplayName("calculateGoalXp")
	inner class GoalXp {

		@Test
		fun `daily goal XP is 75`() {
			val award = calculator.calculateGoalXp(isWeekly = false)
			award.amount shouldBe XpCalculator.DAILY_GOAL_XP
			award.source shouldBe "GOAL"
		}

		@Test
		fun `weekly goal XP is 200`() {
			val award = calculator.calculateGoalXp(isWeekly = true)
			award.amount shouldBe XpCalculator.WEEKLY_GOAL_XP
			award.source shouldBe "GOAL"
		}
	}

	private fun makeSession(
		distanceOnFoot: Float,
		steps: Int,
		durationMs: Long,
	): TrackerSession {
		val start = 1_000_000L
		return TrackerSession(
			id = 1,
			start = start,
			end = start + durationMs,
			isUserInitiated = true,
			collections = 10,
			distanceInM = distanceOnFoot,
			distanceOnFootInM = distanceOnFoot,
			distanceInVehicleInM = 0f,
			steps = steps,
			sessionActivityId = null,
		)
	}
}
