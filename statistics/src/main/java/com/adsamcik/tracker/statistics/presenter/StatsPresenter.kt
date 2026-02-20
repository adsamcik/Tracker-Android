package com.adsamcik.tracker.statistics.presenter

import arrow.core.Either
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.presenter.Presenter
import com.adsamcik.tracker.stats.api.repository.DailySummary
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

/** UI state for the stats overview screen. */
sealed interface StatsState {
	data object Loading : StatsState
	data class Content(
		val recentTrips: List<TripSummary>,
		val todaySummary: DailySummary?,
		val weekSummaries: List<DailySummary>,
	) : StatsState

	data class Error(val message: String) : StatsState
}

/** UI events from the stats overview screen. */
sealed interface StatsEvent {
	data object Refresh : StatsEvent
	data class TripClicked(val tripId: Long) : StatsEvent
}

/**
 * Presenter for the stats overview screen.
 * Replaces StatsViewModel with repository-based data loading.
 */
class StatsPresenter @Inject constructor(
	private val tripRepository: TripRepository,
	private val dailySummaryRepository: DailySummaryRepository,
) : Presenter<StatsEvent, StatsState> {

	override fun present(events: Flow<StatsEvent>): Flow<StatsState> {
		return combine(
			tripRepository.observeTrips(),
			dailySummaryRepository.observeToday(),
			dailySummaryRepository.observeWeek(),
		) { trips, today, week ->
			StatsState.Content(
				recentTrips = trips,
				todaySummary = today,
				weekSummaries = week,
			) as StatsState
		}.onStart {
			emit(StatsState.Loading)
		}
	}
}
