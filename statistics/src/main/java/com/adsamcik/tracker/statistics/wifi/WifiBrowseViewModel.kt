package com.adsamcik.tracker.statistics.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseFilter
import com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseItem
import com.adsamcik.tracker.stats.api.repository.WifiObservationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WifiBrowseViewModel @Inject constructor(
	private val wifiObservationRepository: WifiObservationRepository,
) : ViewModel() {
	private val _filter = MutableStateFlow(WifiObservationBrowseFilter())
	val filter: StateFlow<WifiObservationBrowseFilter> = _filter.asStateFlow()

	private val _uiState = MutableStateFlow<WifiBrowseUiState>(WifiBrowseUiState.Loading)
	val uiState: StateFlow<WifiBrowseUiState> = _uiState.asStateFlow()

	private var loadJob: Job? = null

	init {
		refresh()
	}

	fun applyFilter(filter: WifiObservationBrowseFilter) {
		_filter.value = filter
		refresh()
	}

	fun refresh() {
		val currentFilter = _filter.value
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			_uiState.value = WifiBrowseUiState.Loading
			wifiObservationRepository.getBrowseItems(currentFilter).fold(
				ifLeft = { error ->
					_uiState.value = WifiBrowseUiState.Error(error.message)
				},
				ifRight = { items ->
					_uiState.value = WifiBrowseUiState.Success(items)
				},
			)
		}
	}
}

sealed interface WifiBrowseUiState {
	data object Loading : WifiBrowseUiState
	data class Error(val message: String) : WifiBrowseUiState
	data class Success(val items: List<WifiObservationBrowseItem>) : WifiBrowseUiState
}
