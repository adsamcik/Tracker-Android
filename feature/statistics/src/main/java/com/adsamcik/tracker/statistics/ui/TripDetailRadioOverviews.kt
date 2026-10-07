@file:Suppress("FunctionNaming")

package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
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
import com.adsamcik.tracker.stats.api.repository.CellObservedTower
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SourceOnlyHistoryIntent
import com.adsamcik.tracker.stats.api.repository.WifiHistoryBand
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryResultCompleteness
import com.adsamcik.tracker.stats.api.repository.WifiObservedNetwork
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Saved Wi-Fi readings; counts describe observations rather than unique networks. */
@Composable
internal fun TripDetailWifiOverview(
	history: WifiHistoryEntry,
	intent: SourceOnlyHistoryIntent,
) {
	val resources = LocalContext.current.resources
	val networkReadings = remember(history.observations) {
		history.observations.flatMap { it.networks }.sortedByDescending { it.observedTimeMs }
	}
	val formatter = radioReadingTimeFormatter()
	RadioDetailList(tag = "trip_detail_wifi_only") {
		item {
			SourceDetailHeader(
				title = stringResource(R.string.trip_detail_wifi_session),
				startTime = history.startTime,
				endTime = history.endTime,
			)
		}
		item {
			SourceHistoryAuthorityCard(
				source = HistorySource.WIFI,
				originLabel = stringResource(history.origin.labelResource),
				intent = intent,
			)
		}
		item {
			RadioHistoryStateCard(
				status = stringResource(history.state.labelResource),
				coverage = stringResource(history.coverage.labelResource),
				empty = history.observations.isEmpty(),
				emptyExplanation = stringResource(history.state.emptyExplanationResource),
			)
		}
		if (history.observations.isNotEmpty()) {
			val retainedResults = history.observations.sumOf { it.observationCount.toLong() }
			val partialDeliveries = history.observations.count {
				it.resultCompleteness == WifiHistoryResultCompleteness.PARTIAL
			}
			item {
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
		}
		if (networkReadings.isNotEmpty()) {
			item { SourceDetailHeading(stringResource(R.string.radio_detail_networks)) }
			items(networkReadings, contentType = { "network" }) { reading ->
				RadioWifiReading(reading, formatter)
			}
		}
		item { RadioLocationExclusionNotice() }
	}
}

/** Saved Cell readings describe phone observations rather than tower positions. */
@Composable
internal fun TripDetailCellOverview(
	history: CellHistoryEntry,
	intent: SourceOnlyHistoryIntent,
) {
	val resources = LocalContext.current.resources
	val towerReadings = remember(history.observations) {
		history.observations.flatMap { it.towers }.sortedByDescending { it.observedTimeMs }
	}
	val formatter = radioReadingTimeFormatter()
	RadioDetailList(tag = "trip_detail_cell_only") {
		item {
			SourceDetailHeader(
				title = stringResource(R.string.trip_detail_cell_session),
				startTime = history.startTime,
				endTime = history.endTime,
			)
		}
		item {
			SourceHistoryAuthorityCard(
				source = HistorySource.CELL,
				originLabel = stringResource(history.origin.labelResource),
				intent = intent,
			)
		}
		item {
			RadioHistoryStateCard(
				status = stringResource(history.state.labelResource),
				coverage = stringResource(history.coverage.labelResource),
				empty = history.observations.isEmpty(),
				emptyExplanation = stringResource(history.state.emptyExplanationResource),
			)
		}
		if (history.observations.isNotEmpty()) {
			val retainedRecords = history.observations.sumOf { it.acceptedChildCount.toLong() }
			val registeredRecords = history.observations.sumOf {
				it.registeredObservationCount.toLong()
			}
			val partialDeliveries = history.observations.count {
				it.childCompleteness == CellHistoryChildCompleteness.PARTIAL
			}
			item {
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
		}
		if (towerReadings.isNotEmpty()) {
			item { SourceDetailHeading(stringResource(R.string.radio_detail_towers)) }
			items(towerReadings, contentType = { "tower" }) { reading ->
				RadioCellReading(reading, formatter)
			}
		}
		item { RadioLocationExclusionNotice() }
	}
}

@Composable
private fun RadioDetailList(tag: String, content: LazyListScope.() -> Unit) {
	val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
	LazyColumn(
		modifier = Modifier.fillMaxSize().testTag(tag),
		contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp + navBottom),
		verticalArrangement = Arrangement.spacedBy(16.dp),
		content = content,
	)
}

@Composable
private fun radioReadingTimeFormatter(): DateTimeFormatter {
	val locale = LocalConfiguration.current.locales[0]
	val zone = ZoneId.systemDefault()
	return remember(locale, zone) {
		DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
	}
}

@Composable
private fun RadioWifiReading(network: WifiObservedNetwork, formatter: DateTimeFormatter) {
	val details = listOf(
		formatter.format(Instant.ofEpochMilli(network.observedTimeMs)),
		stringResource(R.string.radio_detail_signal_dbm, network.signalDbm),
		stringResource(R.string.radio_detail_frequency_mhz, network.frequencyMhz),
	).joinToString(" · ")
	GlassCard(modifier = Modifier.fillMaxWidth().testTag("radio_wifi_reading").semantics(mergeDescendants = true) {}) {
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			listOfNotNull(network.ssid?.takeIf(String::isNotBlank), network.bssid)
				.distinct().forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
			Text(details, style = MaterialTheme.typography.bodyMedium)
		}
	}
}

@Composable
private fun RadioCellReading(tower: CellObservedTower, formatter: DateTimeFormatter) {
	val details = listOfNotNull(
		formatter.format(Instant.ofEpochMilli(tower.observedTimeMs)),
		stringResource(tower.technology.labelResource),
		tower.signalDbm?.let { stringResource(R.string.radio_detail_signal_dbm, it) },
	).joinToString(" · ")
	GlassCard(modifier = Modifier.fillMaxWidth().testTag("radio_cell_reading").semantics(mergeDescendants = true) {}) {
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			tower.identity?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
			Text(details, style = MaterialTheme.typography.bodyMedium)
		}
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
