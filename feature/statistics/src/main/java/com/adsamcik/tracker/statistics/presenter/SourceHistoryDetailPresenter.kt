package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryRepository
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryActionTarget
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRepository
import javax.inject.Inject

sealed interface SourceHistoryDetailState {
	data object Loading : SourceHistoryDetailState
	data class Loaded(
		val selection: SourceHistoryDetailSelection,
	) : SourceHistoryDetailState
	data class Unavailable(
		val reason: SourceHistoryDetailUnavailableReason,
		val source: HistorySource?,
	) : SourceHistoryDetailState
}

enum class SourceHistoryDetailUnavailableReason {
	SELECTION_EXPIRED,
	NOT_FOUND,
	SOURCE_INTEGRITY_FAILURE,
	SNAPSHOT_UNAVAILABLE,
	SELECTION_CHANGED,
	RETRYABLE_FAILURE,
}

/** Resolves only producer-issued Wi-Fi/Cell selectors; Activity retains its exact handed entry. */
class SourceHistoryDetailPresenter @Inject constructor(
	private val wifiHistoryRepository: WifiHistoryRepository,
	private val cellHistoryRepository: CellHistoryRepository,
) {
	suspend fun load(selection: SourceHistoryDetailSelection): SourceHistoryDetailState =
		when (val entry = selection.entry) {
			is SourceAwareHistoryPageEntry.ActivityOnly -> if (selection.readSnapshot == null) {
				SourceHistoryDetailState.Unavailable(
					reason = SourceHistoryDetailUnavailableReason.SNAPSHOT_UNAVAILABLE,
					source = HistorySource.ACTIVITY,
				)
			} else {
				SourceHistoryDetailState.Loaded(selection)
			}
			is SourceAwareHistoryPageEntry.WifiOnly -> loadWifi(selection)
			is SourceAwareHistoryPageEntry.CellOnly -> loadCell(selection)
			is SourceAwareHistoryPageEntry.Physical,
			is SourceAwareHistoryPageEntry.StepsOnly,
			is SourceAwareHistoryPageEntry.ImportedSteps,
			is SourceAwareHistoryPageEntry.PressureOnly ->
				SourceHistoryDetailState.Unavailable(
					reason = SourceHistoryDetailUnavailableReason.SELECTION_CHANGED,
					source = entry.source,
				)
		}

	private suspend fun loadWifi(
		selection: SourceHistoryDetailSelection,
	): SourceHistoryDetailState {
		val target = selection.actionTarget as? TrackingHistoryActionTarget.Wifi
			?: return selectionChanged(HistorySource.WIFI)
		return when (val query = wifiHistoryRepository.lookup(target.selection)) {
			is WifiHistoryQuery.Found -> {
				if (query.entry.selection != target.selection) {
					selectionChanged(HistorySource.WIFI)
				} else {
					SourceHistoryDetailState.Loaded(
						selection.copy(
							entry = SourceAwareHistoryPageEntry.WifiOnly(query.entry),
						),
					)
				}
			}
			is WifiHistoryQuery.Failed -> SourceHistoryDetailState.Unavailable(
				reason = SourceHistoryDetailUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				source = HistorySource.WIFI,
			)
			WifiHistoryQuery.NotFound -> SourceHistoryDetailState.Unavailable(
				reason = SourceHistoryDetailUnavailableReason.NOT_FOUND,
				source = HistorySource.WIFI,
			)
		}
	}

	private suspend fun loadCell(
		selection: SourceHistoryDetailSelection,
	): SourceHistoryDetailState {
		val target = selection.actionTarget as? TrackingHistoryActionTarget.Cell
			?: return selectionChanged(HistorySource.CELL)
		return when (val query = cellHistoryRepository.detail(target.selection)) {
			is CellHistoryQuery.Found -> {
				if (query.entry.selection != target.selection) {
					selectionChanged(HistorySource.CELL)
				} else {
					SourceHistoryDetailState.Loaded(
						selection.copy(
							entry = SourceAwareHistoryPageEntry.CellOnly(query.entry),
						),
					)
				}
			}
			CellHistoryQuery.NotFound -> SourceHistoryDetailState.Unavailable(
				reason = SourceHistoryDetailUnavailableReason.NOT_FOUND,
				source = HistorySource.CELL,
			)
		}
	}

	private fun selectionChanged(source: HistorySource) = SourceHistoryDetailState.Unavailable(
		reason = SourceHistoryDetailUnavailableReason.SELECTION_CHANGED,
		source = source,
	)
}
