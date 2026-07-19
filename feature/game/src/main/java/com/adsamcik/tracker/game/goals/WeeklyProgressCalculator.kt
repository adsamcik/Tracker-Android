package com.adsamcik.tracker.game.goals

import java.time.LocalDate
import kotlin.math.floor

internal object WeeklyProgressCalculator {
	fun dailyCap(weeklyGoal: Int, dailyLimit: Float): Int =
		floor(weeklyGoal.coerceAtLeast(1).toDouble() * dailyLimit.coerceIn(0f, 1f))
			.coerceAtMost(Int.MAX_VALUE.toDouble())
			.toInt()

	fun cappedTotal(
		dailySteps: Map<LocalDate, Int>,
		today: LocalDate,
		todayLiveSteps: Int?,
		weeklyGoal: Int,
		dailyLimit: Float,
	): Int {
		val cap = dailyCap(weeklyGoal, dailyLimit)
		val materialized = dailySteps.toMutableMap()
		if (todayLiveSteps != null) materialized[today] = todayLiveSteps.coerceAtLeast(0)
		return materialized.values
			.fold(0L) { total, steps ->
				total + steps.coerceAtLeast(0).coerceAtMost(cap)
			}
			.coerceAtMost(Int.MAX_VALUE.toLong())
			.toInt()
	}
}
