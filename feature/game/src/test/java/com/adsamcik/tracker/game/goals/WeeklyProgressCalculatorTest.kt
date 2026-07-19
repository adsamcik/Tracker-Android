package com.adsamcik.tracker.game.goals

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class WeeklyProgressCalculatorTest {
	private val monday = LocalDate.of(2026, 7, 13)
	private val tuesday = monday.plusDays(1)
	private val wednesday = monday.plusDays(2)

	@Test
	fun `caps each local day before summing the week`() {
		val total = WeeklyProgressCalculator.cappedTotal(
			dailySteps = mapOf(
				monday to 12_000,
				tuesday to 5_000,
				wednesday to 9_000,
			),
			today = wednesday,
			todayLiveSteps = null,
			weeklyGoal = 20_000,
			dailyLimit = 0.3f,
		)

		total shouldBe 17_000
	}

	@Test
	fun `daily cap uses floor semantics`() {
		WeeklyProgressCalculator.dailyCap(
			weeklyGoal = 10,
			dailyLimit = 0.25f,
		) shouldBe 2
	}

	@Test
	fun `today live cumulative steps override database materialization`() {
		val total = WeeklyProgressCalculator.cappedTotal(
			dailySteps = mapOf(
				monday to 4_000,
				tuesday to 9_000,
			),
			today = tuesday,
			todayLiveSteps = 2_000,
			weeklyGoal = 20_000,
			dailyLimit = 0.3f,
		)

		total shouldBe 6_000
	}

	@Test
	fun `today live value is capped like every other local day`() {
		val total = WeeklyProgressCalculator.cappedTotal(
			dailySteps = mapOf(monday to 4_000),
			today = tuesday,
			todayLiveSteps = 50_000,
			weeklyGoal = 20_000,
			dailyLimit = 0.3f,
		)

		total shouldBe 10_000
	}
}
