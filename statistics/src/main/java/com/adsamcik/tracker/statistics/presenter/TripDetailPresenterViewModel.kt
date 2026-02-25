package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.stats.api.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
	savedStateHandle: SavedStateHandle,
) : ViewModel() {

	private val tripId: Long = requireNotNull(savedStateHandle["tripId"])

	private val events = MutableSharedFlow<TripDetailEvent>(replay = 1)

	val state: StateFlow<TripDetailState> = presenter.present(events)
		.stateIn(viewModelScope, SharingStarted.Lazily, TripDetailState.Loading)

	init {
		events.tryEmit(TripDetailEvent.LoadTrip(tripId))
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
