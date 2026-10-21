package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPagingItem
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.PressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The same lazy measured-data list serves Timeline and the selected Calendar day. */
@Composable
internal fun MeasuredHistoryContent(
	pages: LazyPagingItems<HistoryPagingItem>,
	viewZone: ZoneId,
	modifier: Modifier = Modifier,
) {
	val locale = LocalConfiguration.current.locales[0]
	val refresh = pages.loadState.refresh
	val append = pages.loadState.append
	val emptyComplete = refresh is LoadState.NotLoading && append is LoadState.NotLoading &&
		append.endOfPaginationReached
	if (hasInitialHistoryLoad(pages, emptyComplete)) {
		Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
		return
	}
	val firstProblems = firstHistoryProblemPages(pages)
	LazyColumn(
		modifier.fillMaxSize().testTag("measured_history"),
		contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		if (pages.itemCount == 0 && emptyComplete) {
			item("empty") { Text(stringResource(R.string.history_measurements_empty)) }
		}
		items(count = pages.itemCount, key = { index -> measuredHistoryKey(pages.peek(index), index) }) { index ->
			MeasuredHistoryItem(pages, index, viewZone, locale, firstProblems)
		}
		measuredHistoryLoadState(pages)
	}
}

private fun hasInitialHistoryLoad(pages: LazyPagingItems<HistoryPagingItem>, emptyComplete: Boolean): Boolean =
	pages.itemCount == 0 && pages.loadState.refresh !is LoadState.Error &&
		pages.loadState.append !is LoadState.Error && !emptyComplete

private fun firstHistoryProblemPages(pages: LazyPagingItems<HistoryPagingItem>): Map<HistorySource?, Int> {
	val firstProblems = mutableMapOf<HistorySource?, Int>()
	pages.itemSnapshotList.forEachIndexed { index, item ->
		if (item is HistoryPagingItem.PageProblems) {
			item.problems.forEach { firstProblems.putIfAbsent(it.source, index) }
		}
	}
	return firstProblems
}

private fun measuredHistoryKey(item: HistoryPagingItem?, index: Int): String =
	when (item) {
		is HistoryPagingItem.Row -> "${item.snapshot.collectedDataEpoch}:${item.row.position}"
		is HistoryPagingItem.PageProblems -> "problems:$index:${item.snapshot.collectedDataEpoch}"
		null -> "placeholder:$index"
	}

@Composable
private fun MeasuredHistoryItem(
	pages: LazyPagingItems<HistoryPagingItem>, index: Int, viewZone: ZoneId, locale: Locale,
	firstProblems: Map<HistorySource?, Int>,
) {
	when (val item = pages[index]) {
		is HistoryPagingItem.Row -> MeasuredHistoryRow(item, pages, index, viewZone, locale)
		is HistoryPagingItem.PageProblems -> MeasuredHistoryProblems(item, index, firstProblems, pages::refresh)
		null -> Unit
	}
}

@Composable
private fun MeasuredHistoryRow(
	item: HistoryPagingItem.Row, pages: LazyPagingItems<HistoryPagingItem>, index: Int, viewZone: ZoneId, locale: Locale,
) {
	val date = measuredDate(item.row, viewZone)
	var previousIndex = index - 1
	var previousDate: LocalDate? = null
	while (previousIndex >= 0 && previousDate == null) {
		val previous = pages.peek(previousIndex--)
		if (previous is HistoryPagingItem.Row) {
			previousDate = measuredDate(previous.row, viewZone)
		}
	}
	if (date != previousDate) {
		Text(
		date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
		modifier = Modifier.padding(top = 8.dp).semantics { heading() },
		style = MaterialTheme.typography.titleSmall,
	)
	}
	MeasuredHistoryCard(item.row, viewZone, locale)
}

@Composable
private fun MeasuredHistoryProblems(
	item: HistoryPagingItem.PageProblems, index: Int, firstProblems: Map<HistorySource?, Int>, onRetry: () -> Unit,
) {
	item.problems.distinctBy { it.source }.filter { firstProblems[it.source] == index }
	.sortedBy { it.source?.ordinal ?: -1 }
	.forEach { problem ->
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = problem.source?.let { stringResource(it.labelResource) }
					?: stringResource(R.string.history_screen_title),
				modifier = Modifier.weight(1f),
			)
			Text(stringResource(R.string.history_value_unavailable))
			TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
		}
	}
}

private fun androidx.compose.foundation.lazy.LazyListScope.measuredHistoryLoadState(
	pages: LazyPagingItems<HistoryPagingItem>,
) {
	val failure = pages.loadState.refresh as? LoadState.Error ?: pages.loadState.append as? LoadState.Error
	if (failure != null) {
		item("load_error") {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.history_value_unavailable), modifier = Modifier.weight(1f))
			TextButton(onClick = pages::retry) { Text(stringResource(R.string.action_retry)) }
		}
	}
	}
	if (pages.loadState.append is LoadState.Loading ||
		pages.itemCount > 0 && pages.loadState.refresh is LoadState.Loading) {
		item("loading") {
		Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
	}
	}
}

internal fun measuredDate(row: UnifiedHistoryRow, zone: ZoneId): LocalDate =
	Instant.ofEpochMilli(row.position.startTimeMs).atZone(zone).toLocalDate()

/** A real measured window is visible even before expanding, including a crossing date. */
internal fun measuredTimeLabel(row: UnifiedHistoryRow, zone: ZoneId, locale: Locale): String {
	val start = Instant.ofEpochMilli(row.position.startTimeMs).atZone(zone)
	val end = row.toExclusiveMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
	val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
	return start.format(time) + (end?.let {
		val endFormat = if (it.toLocalDate() == start.toLocalDate()) {
			time
		}
			else {
			DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
		}
		" → " + it.format(endFormat)
	} ?: "")
}

@Composable
private fun MeasuredHistoryCard(row: UnifiedHistoryRow, zone: ZoneId, locale: Locale) {
	val measurement = row.measurement ?: return
	var expanded by rememberSaveable(row.position) { mutableStateOf(false) }
	val expansion = stringResource(if (expanded) {
		R.string.history_hide_details
	} else {
		R.string.history_show_details
	})
	Card(
		onClick = { expanded = !expanded },
		modifier = Modifier.fillMaxWidth().testTag("history_measurement_${row.position.stableId}")
			.semantics { stateDescription = expansion },
	) {
		Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(stringResource(measurement.source.labelResource), Modifier.weight(1f),
					style = MaterialTheme.typography.titleSmall)
				Text(measuredTimeLabel(row, zone, locale), style = MaterialTheme.typography.bodySmall)
			}
			MeasurementValue(measurement, locale, expanded)
			if (HistoryOrigin.IMPORTED in row.origins) {
				Text(stringResource(R.string.radio_detail_imported),
				style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			if (expanded) {
				row.sessionNames.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
				val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,
                    FormatStyle.SHORT).withLocale(locale)
				Text(Instant.ofEpochMilli(row.position.startTimeMs).atZone(zone).format(format) +
					(row.toExclusiveMs?.let { " \u2192 " + Instant.ofEpochMilli(it).atZone(zone).format(format) } ?:
                        ""),
					style = MaterialTheme.typography.bodySmall)
			}
		}
	}
}

@Composable
// Keep this display-only sealed measurement table together; loading and qualification stay outside it.
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun MeasurementValue(value: HistoryMeasurement, locale: Locale, expanded: Boolean) {
	val numbers = NumberFormat.getNumberInstance(locale)
	when (value) {
		is HistoryMeasurement.Location -> {
			Text(String.format(locale, "%.5f, %.5f", value.point.latitudeE7 / COORDINATE_E7_SCALE,
				value.point.longitudeE7 / COORDINATE_E7_SCALE))
			if (expanded) {
				value.point.accuracyMeters?.let { Text("\u00b1 " + numbers.format(it) + " m") }
			}
		}
		is HistoryMeasurement.Wifi -> {
			value.network.ssid?.let { Text(it) }
			if (expanded) {
				value.network.bssid?.let { Text(it) }
			}
			Text(stringResource(R.string.radio_detail_signal_dbm, value.network.signalDbm))
			if (expanded) {
				Text(stringResource(R.string.radio_detail_frequency_mhz, value.network.frequencyMhz))
			}
			if (value.partial) {
				IncompleteMeasurement()
			}
		}
		is HistoryMeasurement.Cell -> {
			value.tower.identity?.let { Text(it) }
			value.tower.signalDbm?.let { Text(stringResource(R.string.radio_detail_signal_dbm, it)) }
			if (expanded) {
				Text(stringResource(value.tower.technology.labelResource))
			}
			if (value.partial) {
				IncompleteMeasurement()
			}
		}
		is HistoryMeasurement.LegacyWifi -> {
			value.ssid.takeIf(String::isNotBlank)?.let { Text(it) }
			Text(stringResource(R.string.radio_detail_signal_dbm, value.signalDbm))
			if (expanded) {
				value.bssid.takeIf(String::isNotBlank)?.let { Text(it) }
				Text(stringResource(R.string.radio_detail_frequency_mhz, value.frequencyMhz))
				value.capabilities.takeIf(String::isNotBlank)?.let { Text(it) }
			}
		}
		is HistoryMeasurement.LegacyActivity -> {
			val activity = DetectedActivity.entries.firstOrNull { it.value == value.activityType }
				?: DetectedActivity.UNKNOWN
			val name = stringResource(activity.nameRes)
			Text(when (value.transitionType.takeIf { value.isTransition }) {
				0 -> stringResource(R.string.history_activity_started, name)
				1 -> stringResource(R.string.history_activity_ended, name)
				else -> name
			})
			if (expanded && !value.isTransition) {
				Text(NumberFormat.getPercentInstance(locale).format(value.confidencePercent / PERCENT_SCALE))
			}
		}
		is HistoryMeasurement.LegacyPressure -> {
			Text(String.format(locale, "%.1f hPa", value.meanHectopascals))
			if (expanded && value.minimumHectopascals != null && value.maximumHectopascals != null) {
				Text(String.format(locale, "%.1f\u2013%.1f hPa", value.minimumHectopascals, value.maximumHectopascals))
			}
			if (value.isPartial) {
				IncompleteMeasurement()
			}
		}
		is HistoryMeasurement.LegacyCell -> {
			Text(numbers.format(value.cellId))
			if (expanded) {
				Text("${value.mcc}:${value.mnc}:${value.lac}")
				FactRow(stringResource(R.string.stats_cell_signal_chip_label), numbers.format(value.signalStrength))
			}
			// Released Cell signalStrength is not guaranteed to be dBm.
		}
		is HistoryMeasurement.WifiSummary -> FactRow(stringResource(R.string.radio_detail_networks),
			numbers.format(value.observation.observationCount))
		is HistoryMeasurement.CellSummary -> FactRow(stringResource(R.string.radio_detail_towers),
			numbers.format(value.observation.acceptedChildCount))
		is HistoryMeasurement.LegacyWifiCount -> FactRow(stringResource(R.string.radio_detail_networks),
			value.count.takeIf { it >= 0 }?.let(numbers::format) ?: stringResource(R.string.history_value_unavailable))
		is HistoryMeasurement.BackgroundWifiSummary -> FactRow(stringResource(R.string.radio_detail_networks),
			numbers.format(value.fact.observationCount))
		is HistoryMeasurement.BackgroundCellSummary -> FactRow(stringResource(R.string.radio_detail_towers),
			numbers.format(value.fact.observationCount))
		is HistoryMeasurement.Activity -> Text(stringResource(value.band.activity.labelResource))
		is HistoryMeasurement.Pressure -> {
			Text(String.format(locale, "%.1f hPa", value.window.meanHectopascals))
			if (expanded) {
				Text(String.format(locale, "%.1f\u2013%.1f hPa", value.window.minimumHectopascals,
                    value.window.maximumHectopascals))
			}
			if (value.window.qualification != PressureWindowQualification.COMPLETE) {
				IncompleteMeasurement()
			}
		}
		is HistoryMeasurement.Steps -> {
			Text(value.history.count?.let(numbers::format) ?: stringResource(R.string.history_value_unavailable))
			if (!value.history.hasCompleteValue) {
				IncompleteMeasurement()
			}
		}
		is HistoryMeasurement.Gap -> Text(stringResource(R.string.history_value_unavailable))
	}
}

@Composable
private fun IncompleteMeasurement() {
	Text(stringResource(R.string.history_value_partial), style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val COORDINATE_E7_SCALE = 1e7
private const val PERCENT_SCALE = 100.0
