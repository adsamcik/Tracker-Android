package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailHandoff
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SourceHistoryDetailViewModel @Inject constructor(
	private val presenter: SourceHistoryDetailPresenter,
	savedStateHandle: SavedStateHandle,
) : ViewModel() {
	private val selectionToken: String? = savedStateHandle["selectionToken"]
	private val _state = MutableStateFlow<SourceHistoryDetailState>(
		SourceHistoryDetailState.Loading,
	)
	val state: StateFlow<SourceHistoryDetailState> = _state.asStateFlow()
	private var loadJob: Job? = null

	init {
		retry()
	}

	fun retry() {
		val token = selectionToken
		val selection = token?.let(SourceHistoryDetailHandoff::resolve)
		if (selection == null) {
			_state.value = SourceHistoryDetailState.Unavailable(
				reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
				source = null,
			)
			return
		}
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			_state.value = SourceHistoryDetailState.Loading
			_state.value = try {
				presenter.load(selection)
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
				SourceHistoryDetailState.Unavailable(
					reason = SourceHistoryDetailUnavailableReason.RETRYABLE_FAILURE,
					source = selection.entry.source,
				)
			}
		}
	}
}
