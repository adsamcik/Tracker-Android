package com.adsamcik.tracker.game.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.LifetimeStatsUi
import com.adsamcik.tracker.game.repository.PersonalRecordUi
import com.adsamcik.tracker.game.repository.TrophyItemUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Filter for trophy list by medal type. */
enum class TrophyFilter {
	ALL, GOLD, SILVER, BRONZE
}

/** Provides trophy case data: filtered history, personal records, lifetime stats. */
@HiltViewModel
class TrophyCaseViewModel @Inject constructor(
	private val gameRepository: GameRepository,
) : ViewModel() {

	private val _filter = MutableStateFlow(TrophyFilter.ALL)
	val filter: StateFlow<TrophyFilter> = _filter

	val trophies: StateFlow<List<TrophyItemUi>> = combine(
		gameRepository.getChallengeHistory(),
		_filter,
	) { history, filterValue ->
		when (filterValue) {
			TrophyFilter.ALL -> history
			TrophyFilter.GOLD -> history.filter { it.medal == "GOLD" }
			TrophyFilter.SILVER -> history.filter { it.medal == "SILVER" }
			TrophyFilter.BRONZE -> history.filter { it.medal == "BRONZE" }
		}
	}.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

	val personalRecords: StateFlow<List<PersonalRecordUi>> =
		gameRepository.getPersonalRecords()
			.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

	val lifetimeStats: StateFlow<LifetimeStatsUi> =
		gameRepository.getLifetimeStats()
			.stateIn(
				viewModelScope,
				SharingStarted.Lazily,
				LifetimeStatsUi(0, 0, 0f, 0, 0, 0, 0L),
			)

	fun setFilter(filter: TrophyFilter) {
		_filter.value = filter
	}
}
