package com.adsamcik.tracker.statistics.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for Trip detail screen.
 */
sealed interface TripDetailState {
	data object Loading : TripDetailState
	data class Loaded(val trip: Trip) : TripDetailState
	data object NotFound : TripDetailState
	data class Error(val message: String) : TripDetailState
}

/**
 * ViewModel for the Trip detail screen.
 * Loads a single [Trip] by ID from [TripDao].
 */
@HiltViewModel
class TripDetailViewModel @Inject constructor(
	private val tripDao: TripDao,
	savedStateHandle: SavedStateHandle
) : ViewModel() {

	private val tripId: Long = requireNotNull(savedStateHandle["tripId"])

	private val _state = MutableStateFlow<TripDetailState>(TripDetailState.Loading)
	val state: StateFlow<TripDetailState> = _state.asStateFlow()

	init {
		loadTrip()
	}

	/** Retry loading the trip after an error. */
	fun retry() {
		_state.value = TripDetailState.Loading
		loadTrip()
	}

	private fun loadTrip() {
		viewModelScope.launch {
			try {
				val trip = tripDao.getById(tripId)
				_state.value = if (trip != null) {
					TripDetailState.Loaded(trip)
				} else {
					TripDetailState.NotFound
				}
			} catch (e: Exception) {
				_state.value = TripDetailState.Error(e.message ?: "Failed to load trip")
			}
		}
	}
}
