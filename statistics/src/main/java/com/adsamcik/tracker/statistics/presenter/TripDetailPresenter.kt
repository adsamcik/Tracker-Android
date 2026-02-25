package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.presenter.Presenter
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

/** UI state for the trip detail screen. */
sealed interface TripDetailState {
	data object Loading : TripDetailState
	data class Loaded(val trip: TripSummary) : TripDetailState
	data class NotFound(val tripId: Long) : TripDetailState
	data class Error(val message: String) : TripDetailState
}

/** UI events from the trip detail screen. */
sealed interface TripDetailEvent {
	data class LoadTrip(val tripId: Long) : TripDetailEvent
}

/**
 * Presenter for the trip detail screen.
 * Replaces TripDetailViewModel with repository-based data loading.
 */
class TripDetailPresenter @Inject constructor(
	private val tripRepository: TripRepository,
) : Presenter<TripDetailEvent, TripDetailState> {

	override fun present(events: Flow<TripDetailEvent>): Flow<TripDetailState> {
		@OptIn(ExperimentalCoroutinesApi::class)
		return events.flatMapLatest { event ->
			when (event) {
				is TripDetailEvent.LoadTrip -> flow {
					emit(TripDetailState.Loading)
					val result = tripRepository.getTripDetail(event.tripId)
					result.fold(
						ifLeft = { error ->
							when (error) {
								is com.adsamcik.tracker.stats.api.error.StatsError.NotFound ->
									emit(TripDetailState.NotFound(event.tripId))
								else ->
									emit(TripDetailState.Error(error.message))
							}
						},
						ifRight = { trip ->
							emit(TripDetailState.Loaded(trip))
						},
					)
				}
			}
		}
	}
}
