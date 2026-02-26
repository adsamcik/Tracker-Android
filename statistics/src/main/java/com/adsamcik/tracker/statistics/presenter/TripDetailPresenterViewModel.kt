package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.database.dao.SkiRunSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import com.adsamcik.tracker.stats.api.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hilt-compatible ViewModel wrapper for [TripDetailPresenter].
 * Bridges the Molecule-style Presenter pattern with Compose's [hiltViewModel] lifecycle.
 *
 * - Extracts `tripId` from [SavedStateHandle] and emits [TripDetailEvent.LoadTrip] on init.
 * - Exposes reactive [state] for the Compose UI to collect.
 */
@HiltViewModel
class TripDetailPresenterViewModel @Inject constructor(
	presenter: TripDetailPresenter,
	private val tripDao: TripDao,
	private val skiRunSegmentDao: SkiRunSegmentDao,
	savedStateHandle: SavedStateHandle,
) : ViewModel() {

	private val tripId: Long = requireNotNull(savedStateHandle["tripId"])

	private val events = MutableSharedFlow<TripDetailEvent>(replay = 1)

	val state: StateFlow<TripDetailState> = presenter.present(events)
		.stateIn(viewModelScope, SharingStarted.Lazily, TripDetailState.Loading)

	private val _skiSegments = MutableStateFlow<List<SkiRunSegment>>(emptyList())
	val skiSegments: StateFlow<List<SkiRunSegment>> = _skiSegments.asStateFlow()

	init {
		events.tryEmit(TripDetailEvent.LoadTrip(tripId))
		loadSkiSegments()
	}

	private fun loadSkiSegments() {
		viewModelScope.launch {
			try {
				val loaded = state.filterIsInstance<TripDetailState.Loaded>().first()
				loadSkiSegmentsForTrip(loaded)
			} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
				// Ski segments are optional — don't break trip detail on failure
			}
		}
	}

	private suspend fun loadSkiSegmentsForTrip(loaded: TripDetailState.Loaded) {
		val trip = loaded.trip
		val segments = skiRunSegmentDao.getByTimeRange(
			trip.startTimeMs.raw,
			trip.endTimeMs.raw
		)
		_skiSegments.value = segments
	}

	/**
	 * Retry loading the trip detail after an error.
	 */
	fun retry() {
		events.tryEmit(TripDetailEvent.LoadTrip(tripId))
	}

	/**
	 * Delete the current trip and invoke [onDeleted] on completion.
	 */
	fun deleteTrip(onDeleted: () -> Unit) {
		viewModelScope.launch {
			tripDao.deleteById(tripId)
			onDeleted()
		}
	}
}
