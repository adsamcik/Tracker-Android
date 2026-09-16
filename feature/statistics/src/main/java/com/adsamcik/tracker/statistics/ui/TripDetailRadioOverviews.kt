@file:Suppress("FunctionNaming")

package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.stats.api.repository.CellHistoryChildCompleteness
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SourceOnlyHistoryIntent
import com.adsamcik.tracker.stats.api.repository.WifiHistoryBand
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryResultCompleteness

/** Identity-free selected Wi-Fi detail. Counts are retained results, never unique networks. */
@Composable
internal fun TripDetailWifiOverview(
	history: WifiHistoryEntry,
	intent: SourceOnlyHistoryIntent,
) {
	val resources = LocalContext.current.resources
	SourceDetailColumn(tag = "trip_detail_wifi_only") {
		SourceDetailHeader(
			title = stringResource(R.string.trip_detail_wifi_session),
			startTime = history.startTime,
			endTime = history.endTime,
		)
		SourceHistoryAuthorityCard(
			source = HistorySource.WIFI,
			originLabel = stringResource(history.origin.labelResource),
			intent = intent,
		)
		RadioHistoryStateCard(
			status = stringResource(history.state.labelResource),
			coverage = stringResource(history.coverage.labelResource),
			empty = history.observations.isEmpty(),
			emptyExplanation = stringResource(history.state.emptyExplanationResource),
		)
		if (history.observations.isNotEmpty()) {
			val retainedResults = history.observations.sumOf { it.observationCount.toLong() }
			val partialDeliveries = history.observations.count {
				it.resultCompleteness == WifiHistoryResultCompleteness.PARTIAL
			}
			GlassCard(modifier = Modifier.fillMaxWidth()) {
				Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
					SourceDetailHeading(stringResource(R.string.trip_detail_wifi_evidence))
					FactRow(
						label = stringResource(R.string.trip_detail_radio_deliveries),
						value = history.observations.size.formatReadable(),
					)
					FactRow(
						label = stringResource(R.string.trip_detail_wifi_retained_results),
						value = retainedResults.formatReadable(),
					)
					FactRow(
						label = stringResource(R.string.trip_detail_radio_partial_deliveries),
						value = partialDeliveries.formatReadable(),
					)
					val bandMix = history.observations
						.flatMap { observation -> observation.bandMix.entries }
						.groupBy { it.key }
						.mapValues { (_, entries) -> entries.sumOf { it.value.toLong() } }
					if (bandMix.isNotEmpty()) {
						FactRow(
							label = stringResource(R.string.trip_detail_wifi_band_mix),
							value = bandMix.entries
								.sortedBy { it.key.ordinal }
								.joinToString { (band, count) ->
									"${resources.getString(band.labelResource)}: ${count.formatReadable()}"
								},
						)
					}
					val strongest = history.observations.maxOf {
						it.signalQuality.strongestSignalDbm
					}
					val weakest = history.observations.minOf {
						it.signalQuality.weakestSignalDbm
					}
					FactRow(
						label = stringResource(R.string.trip_detail_wifi_signal_range),
						value = stringResource(
							R.string.trip_detail_wifi_signal_range_value,
							weakest,
							strongest,
						),
					)
				}
			}
		}
		RadioLocationExclusionNotice()
	}
}

/** Identity-free selected Cell detail. Counts never imply unique towers or subscriptions. */
@Composable
internal fun TripDetailCellOverview(
	history: CellHistoryEntry,
	intent: SourceOnlyHistoryIntent,
) {
	val resources = LocalContext.current.resources
	SourceDetailColumn(tag = "trip_detail_cell_only") {
		SourceDetailHeader(
			title = stringResource(R.string.trip_detail_cell_session),
			startTime = history.startTime,
			endTime = history.endTime,
		)
		SourceHistoryAuthorityCard(
			source = HistorySource.CELL,
			originLabel = stringResource(history.origin.labelResource),
			intent = intent,
		)
		RadioHistoryStateCard(
			status = stringResource(history.state.labelResource),
			coverage = stringResource(history.coverage.labelResource),
			empty = history.observations.isEmpty(),
			emptyExplanation = stringResource(history.state.emptyExplanationResource),
		)
		if (history.observations.isNotEmpty()) {
			val retainedRecords = history.observations.sumOf { it.acceptedChildCount.toLong() }
			val registeredRecords = history.observations.sumOf {
				it.registeredObservationCount.toLong()
			}
			val partialDeliveries = history.observations.count {
				it.childCompleteness == CellHistoryChildCompleteness.PARTIAL
			}
			GlassCard(modifier = Modifier.fillMaxWidth()) {
				Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
					SourceDetailHeading(stringResource(R.string.trip_detail_cell_evidence))
					FactRow(
						label = stringResource(R.string.trip_detail_radio_deliveries),
						value = history.observations.size.formatReadable(),
					)
					FactRow(
						label = stringResource(R.string.trip_detail_cell_retained_records),
						value = retainedRecords.formatReadable(),
					)
					FactRow(
						label = stringResource(R.string.trip_detail_cell_registered_records),
						value = registeredRecords.formatReadable(),
					)
					FactRow(
						label = stringResource(R.string.trip_detail_radio_partial_deliveries),
						value = partialDeliveries.formatReadable(),
					)
					val technologyMix = history.observations
						.flatMap { observation -> observation.technologyMix.entries }
						.groupBy { it.key }
						.mapValues { (_, entries) -> entries.sumOf { it.value.toLong() } }
					if (technologyMix.isNotEmpty()) {
						FactRow(
							label = stringResource(R.string.trip_detail_cell_technology_mix),
							value = technologyMix.entries
								.sortedBy { it.key.ordinal }
								.joinToString { (technology, count) ->
									"${resources.getString(technology.labelResource)}: ${count.formatReadable()}"
								},
						)
					}
				}
			}
		}
		RadioLocationExclusionNotice()
	}
}

@Composable
internal fun SourceHistoryAuthorityCard(
	source: HistorySource,
	originLabel: String,
	intent: SourceOnlyHistoryIntent,
) {
	GlassCard(modifier = Modifier.fillMaxWidth()) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			SourceDetailHeading(stringResource(R.string.trip_detail_source_authority))
			FactRow(
				label = stringResource(R.string.trip_detail_source),
				value = stringResource(source.labelResource),
			)
			FactRow(
				label = stringResource(R.string.trip_detail_source_origin),
				value = originLabel,
			)
			FactRow(
				label = stringResource(R.string.trip_detail_source_purpose),
				value = stringResource(intent.purposeLabelResource),
			)
			Text(
				text = stringResource(intent.explanationResource),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun RadioHistoryStateCard(
	status: String,
	coverage: String,
	empty: Boolean,
	emptyExplanation: String,
) {
	GlassCard(modifier = Modifier.fillMaxWidth()) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			SourceDetailHeading(stringResource(R.string.trip_detail_radio_summary))
			FactRow(label = stringResource(R.string.trip_detail_radio_status), value = status)
			FactRow(label = stringResource(R.string.trip_detail_radio_coverage), value = coverage)
			if (empty) {
				Text(
					text = emptyExplanation,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun RadioLocationExclusionNotice() {
	Text(
		text = stringResource(R.string.trip_detail_radio_location_excluded),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

private val WifiHistoryOrigin.labelResource: Int
	get() = when (this) {
		WifiHistoryOrigin.LOCAL -> R.string.trip_detail_source_origin_local
		WifiHistoryOrigin.IMPORTED -> R.string.trip_detail_source_origin_imported
	}

private val CellHistoryOrigin.labelResource: Int
	get() = when (this) {
		CellHistoryOrigin.Local -> R.string.trip_detail_source_origin_local
		is CellHistoryOrigin.Imported -> R.string.trip_detail_source_origin_imported
	}

private val SourceOnlyHistoryIntent.purposeLabelResource: Int
	get() = when (this) {
		SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY -> R.string.trip_detail_source_purpose_exact
		SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP ->
			R.string.trip_detail_source_purpose_imported
	}

private val SourceOnlyHistoryIntent.explanationResource: Int
	get() = when (this) {
		SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY ->
			R.string.trip_detail_source_purpose_exact_explanation
		SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP ->
			R.string.trip_detail_source_purpose_imported_explanation
	}

private val WifiHistoryProductState.labelResource: Int
	get() = when (this) {
		WifiHistoryProductState.MATERIALIZING -> R.string.trip_detail_radio_materializing
		WifiHistoryProductState.PARTIAL -> R.string.trip_detail_radio_partial
		WifiHistoryProductState.READY -> R.string.trip_detail_radio_ready
		WifiHistoryProductState.UNAVAILABLE -> R.string.trip_detail_radio_unavailable
		WifiHistoryProductState.MISSING -> R.string.trip_detail_radio_missing
		WifiHistoryProductState.DELETED -> R.string.trip_detail_radio_deleted
		WifiHistoryProductState.FAILED -> R.string.trip_detail_radio_failed
	}

private val CellHistoryProductState.labelResource: Int
	get() = when (this) {
		CellHistoryProductState.MATERIALIZING -> R.string.trip_detail_radio_materializing
		CellHistoryProductState.PARTIAL -> R.string.trip_detail_radio_partial
		CellHistoryProductState.READY -> R.string.trip_detail_radio_ready
		CellHistoryProductState.UNAVAILABLE -> R.string.trip_detail_radio_unavailable
		CellHistoryProductState.MISSING -> R.string.trip_detail_radio_missing
		CellHistoryProductState.DELETED -> R.string.trip_detail_radio_deleted
		CellHistoryProductState.UNVERIFIABLE -> R.string.trip_detail_radio_unverifiable
		CellHistoryProductState.FAILED -> R.string.trip_detail_radio_failed
	}

private val WifiHistoryProductState.emptyExplanationResource: Int
	get() = when (this) {
		WifiHistoryProductState.MATERIALIZING -> R.string.trip_detail_radio_waiting
		WifiHistoryProductState.DELETED -> R.string.trip_detail_radio_deleted_explanation
		WifiHistoryProductState.FAILED -> R.string.trip_detail_radio_unverifiable_explanation
		WifiHistoryProductState.PARTIAL,
		WifiHistoryProductState.READY,
		WifiHistoryProductState.UNAVAILABLE,
		WifiHistoryProductState.MISSING -> R.string.trip_detail_radio_no_evidence
	}

private val CellHistoryProductState.emptyExplanationResource: Int
	get() = when (this) {
		CellHistoryProductState.MATERIALIZING -> R.string.trip_detail_radio_waiting
		CellHistoryProductState.DELETED -> R.string.trip_detail_radio_deleted_explanation
		CellHistoryProductState.UNVERIFIABLE,
		CellHistoryProductState.FAILED -> R.string.trip_detail_radio_unverifiable_explanation
		CellHistoryProductState.PARTIAL,
		CellHistoryProductState.READY,
		CellHistoryProductState.UNAVAILABLE,
		CellHistoryProductState.MISSING -> R.string.trip_detail_radio_no_evidence
	}

private val WifiHistoryCoverage.labelResource: Int
	get() = when (this) {
		WifiHistoryCoverage.NONE -> R.string.trip_detail_radio_coverage_none
		WifiHistoryCoverage.PARTIAL -> R.string.trip_detail_radio_coverage_partial
		WifiHistoryCoverage.COMPLETE -> R.string.trip_detail_radio_coverage_complete
		WifiHistoryCoverage.UNKNOWN -> R.string.trip_detail_radio_coverage_unknown
	}

private val CellHistoryCoverage.labelResource: Int
	get() = when (this) {
		CellHistoryCoverage.NONE -> R.string.trip_detail_radio_coverage_none
		CellHistoryCoverage.PARTIAL -> R.string.trip_detail_radio_coverage_partial
		CellHistoryCoverage.COMPLETE -> R.string.trip_detail_radio_coverage_complete
		CellHistoryCoverage.UNKNOWN -> R.string.trip_detail_radio_coverage_unknown
	}

private val WifiHistoryBand.labelResource: Int
	get() = when (this) {
		WifiHistoryBand.TWO_POINT_FOUR_GHZ -> R.string.trip_detail_wifi_band_2_4
		WifiHistoryBand.FIVE_GHZ -> R.string.trip_detail_wifi_band_5
		WifiHistoryBand.SIX_GHZ -> R.string.trip_detail_wifi_band_6
		WifiHistoryBand.OTHER -> R.string.trip_detail_wifi_band_other
	}

private val CellHistoryTechnology.labelResource: Int
	get() = when (this) {
		CellHistoryTechnology.GSM -> R.string.trip_detail_cell_technology_gsm
		CellHistoryTechnology.CDMA -> R.string.trip_detail_cell_technology_cdma
		CellHistoryTechnology.WCDMA -> R.string.trip_detail_cell_technology_wcdma
		CellHistoryTechnology.TDSCDMA -> R.string.trip_detail_cell_technology_tdscdma
		CellHistoryTechnology.LTE -> R.string.trip_detail_cell_technology_lte
		CellHistoryTechnology.NR -> R.string.trip_detail_cell_technology_nr
	}
