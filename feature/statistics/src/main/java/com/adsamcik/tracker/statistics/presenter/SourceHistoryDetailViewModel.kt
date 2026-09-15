package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailHandoff
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
	private var ownershipExpiryJob: Job? = null
	private var ownedSelection: SourceHistoryDetailSelection? = null
	private var tokenConsumed = false
	private var activityPresented = false
	private var loadGeneration = 0L

	init {
		retry()
	}

	fun retry() {
		val generation = ++loadGeneration
		loadJob?.cancel()
		loadJob = null
		val selection = ownedSelection ?: consumeInitialSelection()?.also(::scheduleOwnershipExpiry)
		if (selection == null) {
			publishExpired()
			return
		}
		if (activityPresented && selection.entry is SourceAwareHistoryPageEntry.ActivityOnly) {
			ownershipExpiryJob?.cancel()
			ownershipExpiryJob = null
			ownedSelection = null
			publishExpired(selection.entry.source)
			return
		}
		ownedSelection = selection
		_state.value = SourceHistoryDetailState.Loading
		loadJob = viewModelScope.launch {
			val result = try {
				presenter.load(selection)
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
				SourceHistoryDetailState.Unavailable(
					reason = SourceHistoryDetailUnavailableReason.RETRYABLE_FAILURE,
					source = selection.entry.source,
				)
			}
			if (generation == loadGeneration && ownedSelection == selection) {
				_state.value = result
				if (
					result is SourceHistoryDetailState.Loaded &&
					result.selection.entry is SourceAwareHistoryPageEntry.ActivityOnly
				) {
					activityPresented = true
				} else if (
					result is SourceHistoryDetailState.Unavailable &&
					selection.entry is SourceAwareHistoryPageEntry.ActivityOnly
				) {
					ownershipExpiryJob?.cancel()
					ownershipExpiryJob = null
					ownedSelection = null
				}
			}
		}
	}

	/** Releases destination ownership immediately when navigation leaves this detail. */
	fun close() {
		val source = ownedSelection?.entry?.source ?: when (val current = _state.value) {
			is SourceHistoryDetailState.Loaded -> current.selection.entry.source
			is SourceHistoryDetailState.Unavailable -> current.source
			SourceHistoryDetailState.Loading -> null
		}
		++loadGeneration
		loadJob?.cancel()
		loadJob = null
		ownershipExpiryJob?.cancel()
		ownershipExpiryJob = null
		ownedSelection = null
		selectionToken?.let(SourceHistoryDetailHandoff::release)
		publishExpired(source)
	}

	override fun onCleared() {
		close()
		super.onCleared()
	}

	private fun consumeInitialSelection(): SourceHistoryDetailSelection? {
		if (tokenConsumed) return null
		tokenConsumed = true
		return selectionToken?.let(SourceHistoryDetailHandoff::consume)
	}

	private fun scheduleOwnershipExpiry(selection: SourceHistoryDetailSelection) {
		ownershipExpiryJob?.cancel()
		ownershipExpiryJob = viewModelScope.launch {
			delay(SourceHistoryDetailHandoff.DESTINATION_OWNERSHIP_TIMEOUT_MILLIS)
			if (ownedSelection == selection) {
				ownershipExpiryJob = null
				++loadGeneration
				loadJob?.cancel()
				loadJob = null
				ownedSelection = null
				publishExpired(selection.entry.source)
			}
		}
	}

	private fun publishExpired(source: com.adsamcik.tracker.stats.api.repository.HistorySource? = null) {
		_state.value = SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
			source = source,
		)
	}
}
