package com.adsamcik.tracker.stats.engine

import com.adsamcik.tracker.stats.api.repository.LiveStats
import com.adsamcik.tracker.stats.api.repository.LiveStatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeLiveStatsRepository(
	initial: LiveStats = LiveStats(),
) : LiveStatsRepository {
	private val state = MutableStateFlow(initial)

	val updates = mutableListOf<LiveStats>()
	val calls = mutableListOf<String>()
	var clearCount: Int = 0
		private set

	override fun observeLiveStats(): Flow<LiveStats> = state

	override suspend fun updateLiveStats(stats: LiveStats) {
		updates += stats
		calls += "update"
		state.value = stats
	}

	override suspend fun clear() {
		clearCount++
		calls += "clear"
		state.value = LiveStats()
	}
}
