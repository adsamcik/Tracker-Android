package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.stats.api.repository.ActivityHistorySelection
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryRepository
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryActionTarget
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
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
		val canRetry: Boolean = false,
	) : SourceHistoryDetailState
}

enum class SourceHistoryDetailUnavailableReason {
	SELECTION_EXPIRED,
	NOT_FOUND,
	SOURCE_READ_BUDGET_EXCEEDED,
	SOURCE_INTEGRITY_FAILURE,
	SNAPSHOT_UNAVAILABLE,
	SELECTION_CHANGED,
	RETRYABLE_FAILURE,
}

/** Resolves radio selectors while Activity retains its exact handed selection hierarchy. */
class SourceHistoryDetailPresenter @Inject constructor(
	private val wifiHistoryRepository: WifiHistoryRepository,
	private val cellHistoryRepository: CellHistoryRepository,
) {
	suspend fun load(selection: SourceHistoryDetailSelection): SourceHistoryDetailState =
		when (val entry = selection.entry) {
			is SourceAwareHistoryPageEntry.ActivityOnly -> loadActivity(selection)
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

	private fun loadActivity(
		selection: SourceHistoryDetailSelection,
	): SourceHistoryDetailState {
		val entry = selection.entry as? SourceAwareHistoryPageEntry.ActivityOnly
			?: return selectionChanged(HistorySource.ACTIVITY)
		val target = selection.actionTarget as? TrackingHistoryActionTarget.Activity
			?: return selectionChanged(HistorySource.ACTIVITY)
		val exactSelection: ActivityHistorySelection = entry.history.selection
			?: return selectionChanged(HistorySource.ACTIVITY)
		return when {
			target.selection != exactSelection -> selectionChanged(HistorySource.ACTIVITY)
			selection.readSnapshot == null ->
				SourceHistoryDetailState.Unavailable(
					reason = SourceHistoryDetailUnavailableReason.SNAPSHOT_UNAVAILABLE,
					source = HistorySource.ACTIVITY,
				)
			else -> SourceHistoryDetailState.Loaded(selection)
		}
	}

	private suspend fun loadWifi(
		selection: SourceHistoryDetailSelection,
	): SourceHistoryDetailState {
		val target = selection.actionTarget as? TrackingHistoryActionTarget.Wifi
			?: return selectionChanged(HistorySource.WIFI)
		return when (val query = wifiHistoryRepository.lookup(target.selection)) {
			is WifiHistoryQuery.Found -> {
				when {
					query.entry.selection != target.selection ->
						selectionChanged(HistorySource.WIFI)
					query.entry.state == WifiHistoryProductState.FAILED ||
						query.entry.causes.any(WifiHistoryCause::isIntegrityFailure) ->
						SourceHistoryDetailState.Unavailable(
							reason = query.entry.causes.toWifiDetailUnavailableReason(),
							source = HistorySource.WIFI,
						)
					else -> SourceHistoryDetailState.Loaded(
						selection.copy(
							entry = SourceAwareHistoryPageEntry.WifiOnly(query.entry),
						),
					)
				}
			}
			is WifiHistoryQuery.Failed -> SourceHistoryDetailState.Unavailable(
				reason = query.cause.toDetailUnavailableReason(),
				source = HistorySource.WIFI,
				canRetry = true,
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
				when {
					query.entry.selection != target.selection ->
						selectionChanged(HistorySource.CELL)
					query.entry.state == CellHistoryProductState.UNVERIFIABLE ||
						query.entry.state == CellHistoryProductState.FAILED ||
						query.entry.causes.any(CellHistoryCause::isIntegrityFailure) ->
						SourceHistoryDetailState.Unavailable(
							reason = query.entry.causes.toCellDetailUnavailableReason(),
							source = HistorySource.CELL,
						)
					else -> SourceHistoryDetailState.Loaded(
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

private fun WifiHistoryCause.toDetailUnavailableReason() =
	if (this == WifiHistoryCause.READ_BUDGET_EXCEEDED) {
		SourceHistoryDetailUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
	} else {
		SourceHistoryDetailUnavailableReason.SOURCE_INTEGRITY_FAILURE
	}

private fun Set<WifiHistoryCause>.toWifiDetailUnavailableReason() =
	firstOrNull(WifiHistoryCause::isIntegrityFailure)?.toDetailUnavailableReason()
		?: SourceHistoryDetailUnavailableReason.SOURCE_INTEGRITY_FAILURE

private fun Set<CellHistoryCause>.toCellDetailUnavailableReason() =
	if (CellHistoryCause.READ_BUDGET_EXCEEDED in this) {
		SourceHistoryDetailUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
	} else {
		SourceHistoryDetailUnavailableReason.SOURCE_INTEGRITY_FAILURE
	}
