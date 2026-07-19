package com.adsamcik.tracker.game.goals.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GoalsSettingsStateTest {
	@Test
	fun `fresh defaults enable feedback without remembering a setup`() {
		val state = GoalsSettingsState.defaults()

		state.notificationsEnabled shouldBe true
		state.dailyStepGoal shouldBe 4_000
		state.weeklyStepGoal shouldBe 24_000
		state.weeklyProgressDailyLimit shouldBe 0.3f
		state.gameHapticsEnabled shouldBe true
		state.quietCoachingEnabled shouldBe true
		state.rememberLastSetup shouldBe false
		state.rememberedOutrunSetup shouldBe null
		state.rememberedTerritorySetup shouldBe null
		state.rememberedZenSetup shouldBe null
		state.rememberedFuseRunSetup shouldBe null
		state.rememberedSwitchbackSetup shouldBe null
		state.dailyGoalReachedPeriod shouldBe null
		state.weeklyGoalReachedPeriod shouldBe null
	}

	@Test
	fun `copy preserves remembered setups and reached markers`() {
		val state = GoalsSettingsState.defaults().copy(
			rememberLastSetup = true,
			rememberedOutrunSetup = RememberedGameSetup(100, "HARD"),
			rememberedTerritorySetup = RememberedGameSetup(20, null),
			rememberedZenSetup = RememberedGameSetup(5, "EASY"),
			rememberedFuseRunSetup = RememberedGameSetup(8, "HARD"),
			rememberedSwitchbackSetup = RememberedGameSetup(4, "EASY"),
			dailyGoalReachedPeriod = 2_026_199,
			weeklyGoalReachedPeriod = 202_629,
		)

		state.copy(notificationsEnabled = false).let { copied ->
			copied.notificationsEnabled shouldBe false
			copied.rememberedOutrunSetup shouldBe RememberedGameSetup(100, "HARD")
			copied.rememberedTerritorySetup shouldBe RememberedGameSetup(20, null)
			copied.rememberedZenSetup shouldBe RememberedGameSetup(5, "EASY")
			copied.rememberedFuseRunSetup shouldBe RememberedGameSetup(8, "HARD")
			copied.rememberedSwitchbackSetup shouldBe RememberedGameSetup(4, "EASY")
			copied.dailyGoalReachedPeriod shouldBe 2_026_199
			copied.weeklyGoalReachedPeriod shouldBe 202_629
		}
	}
}
