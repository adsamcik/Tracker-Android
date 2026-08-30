package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.presenter.Presenter
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** UI state for the trip detail screen. */
sealed interface TripDetailState {
	data object Loading : TripDetailState
	data class Loaded(
		val trip: TripSummary,
		val steps: TripDetailStepsState,
	) : TripDetailState
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
	private val trackingHistoryRepository: TrackingHistoryRepository,
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
							emit(
								TripDetailState.Loaded(
									trip = trip,
									steps = TripDetailStepsState.Materializing,
								),
							)
							emitAll(
								trackingHistoryRepository.observeSession(event.tripId).map { query ->
									when (query) {
										SessionHistoryQuery.NotFound ->
											TripDetailState.NotFound(event.tripId)
										is SessionHistoryQuery.Found -> TripDetailState.Loaded(
											trip = trip,
											steps = query.history.steps.toTripDetailStepsState(),
										)
									}
								}.catch {
									emit(
										TripDetailState.Loaded(
											trip = trip,
											steps = TripDetailStepsState.Failed,
										),
									)
								},
							)
						},
					)
				}
			}
		}
	}
}
