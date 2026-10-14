package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.adsamcik.tracker.feature.statistics.api.export.SourceHistoryFileExporter
import com.adsamcik.tracker.feature.statistics.api.export.SourceHistoryFileExportResult
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailHandoff
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
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
	private val actions: SourceHistoryDetailActions,
	private val exporter: SourceHistoryFileExporter,
) : ViewModel() {
	private val selectionToken: String? = savedStateHandle["selectionToken"]
	private val _state = MutableStateFlow<SourceHistoryDetailState>(
		SourceHistoryDetailState.Loading,
	)
	val state: StateFlow<SourceHistoryDetailState> = _state.asStateFlow()
	private var loadJob: Job? = null
	private var ownedSelection: SourceHistoryDetailSelection? = null
	private var tokenConsumed = false
	private var loadGeneration = 0L
	private val _actionBusy = MutableStateFlow(false)
	val actionBusy = _actionBusy.asStateFlow()
	private val _actionResult = MutableStateFlow<SourceHistoryActionResult?>(null)
	val actionResult = _actionResult.asStateFlow()
	private var actionJob: Job? = null
	private var pendingExport: SourceHistoryDetailSelection? = null

	fun prepareExport(): Boolean {
		val loaded = _state.value as? SourceHistoryDetailState.Loaded ?: return false
		if (_actionBusy.value) return false
		pendingExport = loaded.selection
		_actionResult.value = null
		return true
	}
	fun exportTo(destination: Uri?) {
		val selected = pendingExport ?: return
		pendingExport = null
		if (destination == null || _actionBusy.value || ownedSelection != selected) return
		performAction(selected) {
			when (exporter.export(destination, selected)) {
				SourceHistoryFileExportResult.SAVED -> SourceHistoryActionResult.EXPORTED
				SourceHistoryFileExportResult.STALE -> SourceHistoryActionResult.CHANGED
				SourceHistoryFileExportResult.UNAVAILABLE -> SourceHistoryActionResult.UNAVAILABLE
				SourceHistoryFileExportResult.STORAGE -> SourceHistoryActionResult.FAILED
			}
		}
	}
	fun delete() {
		val loaded = _state.value as? SourceHistoryDetailState.Loaded ?: return
		if (_actionBusy.value) return
		performAction(loaded.selection) { actions.delete(loaded.selection) }
	}
	private fun performAction(selected: SourceHistoryDetailSelection, action: suspend () -> SourceHistoryActionResult) {
		_actionBusy.value = true; _actionResult.value = null
		actionJob = viewModelScope.launch {
			try {
				val result = action()
				if (ownedSelection == selected) {
					_actionResult.value = result
					if (result == SourceHistoryActionResult.DELETED) {
						_state.value = SourceHistoryDetailState.Unavailable(SourceHistoryDetailUnavailableReason.NOT_FOUND, selected.entry.source)
					}
				}
			} catch (cancelled: CancellationException) { throw cancelled }
			catch (_: Exception) { if (ownedSelection == selected) _actionResult.value = SourceHistoryActionResult.FAILED }
			finally { _actionBusy.value = false }
		}
	}

	init {
		retry()
	}

	fun retry() {
		val current = _state.value
		if (current is SourceHistoryDetailState.Unavailable && !current.canRetry) {
			return
		}
		val generation = ++loadGeneration
		loadJob?.cancel()
		loadJob = null
		val selection = ownedSelection ?: consumeInitialSelection()
		if (selection == null) {
			publishExpired()
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
					canRetry = true,
				)
			}
			if (generation == loadGeneration && ownedSelection == selection) {
				_state.value = when (result) {
					is SourceHistoryDetailState.Loaded -> {
						ownedSelection = result.selection
						result
					}
					is SourceHistoryDetailState.Unavailable -> {
						val canRetry = result.canRetry && ownedSelection == selection
						if (!canRetry) {
							relinquishOwnership(selection)
						}
						result.copy(canRetry = canRetry)
					}
					SourceHistoryDetailState.Loading -> result
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
		ownedSelection = null
		pendingExport = null
		actionJob?.cancel(); actionJob = null
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


	private fun relinquishOwnership(selection: SourceHistoryDetailSelection) {
		if (ownedSelection != selection) return
		ownedSelection = null
	}

	private fun publishExpired(source: com.adsamcik.tracker.stats.api.repository.HistorySource? = null) {
		_state.value = SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
			source = source,
		)
	}
}
