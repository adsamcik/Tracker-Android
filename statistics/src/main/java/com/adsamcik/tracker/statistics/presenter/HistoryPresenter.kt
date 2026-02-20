package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.presenter.Presenter
import com.adsamcik.tracker.stats.api.repository.DailySummary
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.ExplorationRepository
import com.adsamcik.tracker.stats.api.repository.ExplorationStats
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import java.time.LocalDate
import javax.inject.Inject

/** UI state for the history screen. */
sealed interface HistoryState {
	data object Loading : HistoryState
	data class Content(
		val trips: List<TripSummary>,
		val explorationStats: ExplorationStats,
		val todaySummary: DailySummary?,
	) : HistoryState
}

/** UI events from the history screen. */
sealed interface HistoryEvent {
	data class SelectDay(val date: LocalDate) : HistoryEvent
}

/**
 * Presenter for the history screen.
 * Replaces HistoryViewModel with repository-based data loading.
 */
class HistoryPresenter @Inject constructor(
	private val tripRepository: TripRepository,
	private val explorationRepository: ExplorationRepository,
	private val dailySummaryRepository: DailySummaryRepository,
) : Presenter<HistoryEvent, HistoryState> {

	override fun present(events: Flow<HistoryEvent>): Flow<HistoryState> {
		return combine(
			tripRepository.observeTrips(),
			explorationRepository.observeStats(),
			dailySummaryRepository.observeToday(),
		) { trips, exploration, today ->
			HistoryState.Content(
				trips = trips,
				explorationStats = exploration,
				todaySummary = today,
			) as HistoryState
		}.onStart {
			emit(HistoryState.Loading)
		}
	}
}
