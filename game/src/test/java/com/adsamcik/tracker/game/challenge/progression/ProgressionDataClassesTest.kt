package com.adsamcik.tracker.game.challenge.progression

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StreakState")
class StreakStateTest {

	@Test
	fun `construction with all fields`() {
		val state = StreakState(
			currentCount = 10,
			bestCount = 15,
			freezeCount = 2,
			milestoneResId = com.adsamcik.tracker.game.R.string.game_streak_milestone_7,
		)
		state.currentCount shouldBe 10
		state.bestCount shouldBe 15
		state.freezeCount shouldBe 2
	}

	@Test
	fun `milestoneResId can be null`() {
		val state = StreakState(
			currentCount = 1,
			bestCount = 1,
			freezeCount = 0,
			milestoneResId = null,
		)
		state.milestoneResId shouldBe null
	}
}

@DisplayName("CompletionResult")
class CompletionResultTest {

	@Test
	fun `construction with all fields`() {
		val result = CompletionResult(
			medal = com.adsamcik.tracker.game.challenge.data.Medal.GOLD,
			xpAwarded = 400,
			streakCount = 5,
			newRecords = listOf("HIGHEST_VALUE"),
			leveledUp = true,
			newLevel = 3,
		)
		result.medal shouldBe com.adsamcik.tracker.game.challenge.data.Medal.GOLD
		result.xpAwarded shouldBe 400
		result.streakCount shouldBe 5
		result.newRecords shouldBe listOf("HIGHEST_VALUE")
		result.leveledUp shouldBe true
		result.newLevel shouldBe 3
	}
}

@DisplayName("ExpiryResult")
class ExpiryResultTest {

	@Test
	fun `streak broken when no freeze used`() {
		val result = ExpiryResult(
			expiredCount = 2,
			streakBroken = true,
			freezeUsed = false,
			currentStreak = 0,
		)
		result.streakBroken shouldBe true
		result.freezeUsed shouldBe false
	}

	@Test
	fun `freeze used preserves streak`() {
		val result = ExpiryResult(
			expiredCount = 1,
			streakBroken = false,
			freezeUsed = true,
			currentStreak = 10,
		)
		result.streakBroken shouldBe false
		result.freezeUsed shouldBe true
		result.currentStreak shouldBe 10
	}
}

@DisplayName("XpAward")
class XpAwardTest {

	@Test
	fun `construction`() {
		val award = XpAward(amount = 150, source = "CHALLENGE")
		award.amount shouldBe 150
		award.source shouldBe "CHALLENGE"
	}

	@Test
	fun `equality`() {
		val a = XpAward(100, "SESSION")
		val b = XpAward(100, "SESSION")
		a shouldBe b
	}
}
