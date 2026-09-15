package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.presenter.Presenter
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** UI state for the trip detail screen. */
sealed interface TripDetailState {
	data object Loading : TripDetailState
	data class Loaded(
		val trip: TripSummary,
		val steps: TripDetailStepsState,
		val sourcePresentation: TripDetailSourcePresentation = TripDetailSourcePresentation.Resolving,
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
							var latestState: TripDetailState = TripDetailState.Loaded(
								trip = trip,
								steps = TripDetailStepsState.Materializing,
								sourcePresentation = TripDetailSourcePresentation.Resolving,
							)
							emit(latestState)
							try {
								trackingHistoryRepository.observeLiveSession(event.tripId).collect { snapshot ->
									latestState = when (val session = snapshot.session) {
										SessionHistoryQuery.NotFound -> TripDetailState.NotFound(event.tripId)
										is SessionHistoryQuery.Found -> {
											TripDetailState.Loaded(
												trip = trip,
												steps = session.history.steps.toTripDetailStepsState(),
												sourcePresentation = snapshot
													.toTripDetailSourcePresentation(),
											)
										}
									}
									emit(latestState)
								}
							} catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
								if (error is CancellationException) throw error
								val failedState = (latestState as? TripDetailState.Loaded)?.copy(
									steps = TripDetailStepsState.Failed,
									sourcePresentation = TripDetailSourcePresentation.Failed,
								) ?: latestState
								emit(failedState)
							}
						},
					)
				}
			}
		}
	}
}
