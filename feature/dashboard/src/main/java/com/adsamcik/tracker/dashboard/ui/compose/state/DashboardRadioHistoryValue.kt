package com.adsamcik.tracker.dashboard.ui.compose.state

import androidx.compose.runtime.Immutable
import com.adsamcik.tracker.stats.api.repository.CellHistoryChildCompleteness
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryResultCompleteness

/** Identity-free radio history summary shared by live and recent Dashboard surfaces. */
@Immutable
data class DashboardRadioHistoryValue(
	val source: HistorySource,
	val origin: DashboardRadioOrigin,
	val state: DashboardRadioProductState,
	val coverage: DashboardRadioCoverage,
	val deliveryCount: Int,
	val retainedRecordCount: Long,
	val partialDeliveryCount: Int,
) {
	init {
		require(source == HistorySource.WIFI || source == HistorySource.CELL)
		require(deliveryCount >= 0)
		require(retainedRecordCount >= 0L)
		require(partialDeliveryCount in 0..deliveryCount)
	}
}

enum class DashboardRadioOrigin { LOCAL, IMPORTED }

enum class DashboardRadioProductState {
	MATERIALIZING,
	PARTIAL,
	READY,
	UNAVAILABLE,
	MISSING,
	DELETED,
	UNVERIFIABLE,
	FAILED,
}

enum class DashboardRadioCoverage { NONE, PARTIAL, COMPLETE, UNKNOWN }

internal fun WifiHistoryEntry.toDashboardRadioHistoryValue() = DashboardRadioHistoryValue(
	source = HistorySource.WIFI,
	origin = when (origin) {
		WifiHistoryOrigin.LOCAL -> DashboardRadioOrigin.LOCAL
		WifiHistoryOrigin.IMPORTED -> DashboardRadioOrigin.IMPORTED
	},
	state = when (state) {
		WifiHistoryProductState.MATERIALIZING -> DashboardRadioProductState.MATERIALIZING
		WifiHistoryProductState.PARTIAL -> DashboardRadioProductState.PARTIAL
		WifiHistoryProductState.READY -> DashboardRadioProductState.READY
		WifiHistoryProductState.UNAVAILABLE -> DashboardRadioProductState.UNAVAILABLE
		WifiHistoryProductState.MISSING -> DashboardRadioProductState.MISSING
		WifiHistoryProductState.DELETED -> DashboardRadioProductState.DELETED
		WifiHistoryProductState.FAILED -> DashboardRadioProductState.FAILED
	},
	coverage = when (coverage) {
		WifiHistoryCoverage.NONE -> DashboardRadioCoverage.NONE
		WifiHistoryCoverage.PARTIAL -> DashboardRadioCoverage.PARTIAL
		WifiHistoryCoverage.COMPLETE -> DashboardRadioCoverage.COMPLETE
		WifiHistoryCoverage.UNKNOWN -> DashboardRadioCoverage.UNKNOWN
	},
	deliveryCount = observations.size,
	retainedRecordCount = observations.sumOf { it.observationCount.toLong() },
	partialDeliveryCount = observations.count {
		it.resultCompleteness == WifiHistoryResultCompleteness.PARTIAL
	},
)

internal fun CellHistoryEntry.toDashboardRadioHistoryValue() = DashboardRadioHistoryValue(
	source = HistorySource.CELL,
	origin = when (origin) {
		CellHistoryOrigin.Local -> DashboardRadioOrigin.LOCAL
		is CellHistoryOrigin.Imported -> DashboardRadioOrigin.IMPORTED
	},
	state = when (state) {
		CellHistoryProductState.MATERIALIZING -> DashboardRadioProductState.MATERIALIZING
		CellHistoryProductState.PARTIAL -> DashboardRadioProductState.PARTIAL
		CellHistoryProductState.READY -> DashboardRadioProductState.READY
		CellHistoryProductState.UNAVAILABLE -> DashboardRadioProductState.UNAVAILABLE
		CellHistoryProductState.MISSING -> DashboardRadioProductState.MISSING
		CellHistoryProductState.DELETED -> DashboardRadioProductState.DELETED
		CellHistoryProductState.UNVERIFIABLE -> DashboardRadioProductState.UNVERIFIABLE
		CellHistoryProductState.FAILED -> DashboardRadioProductState.FAILED
	},
	coverage = when (coverage) {
		CellHistoryCoverage.NONE -> DashboardRadioCoverage.NONE
		CellHistoryCoverage.PARTIAL -> DashboardRadioCoverage.PARTIAL
		CellHistoryCoverage.COMPLETE -> DashboardRadioCoverage.COMPLETE
		CellHistoryCoverage.UNKNOWN -> DashboardRadioCoverage.UNKNOWN
	},
	deliveryCount = observations.size,
	retainedRecordCount = observations.sumOf { it.acceptedChildCount.toLong() },
	partialDeliveryCount = observations.count {
		it.childCompleteness == CellHistoryChildCompleteness.PARTIAL
	},
)
