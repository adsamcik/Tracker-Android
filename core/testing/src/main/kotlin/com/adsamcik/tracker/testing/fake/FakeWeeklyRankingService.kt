package com.adsamcik.tracker.testing.fake

import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankingResult
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankingService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeWeeklyRankingService(
	initialResult: WeeklyRankingResult = WeeklyRankingResult.DataUnavailable,
) : WeeklyRankingService {
	private val result = MutableStateFlow(initialResult)

	override fun observeCurrentWeek(): Flow<WeeklyRankingResult> = result.asStateFlow()

	fun setResult(value: WeeklyRankingResult) {
		result.value = value
	}
}
