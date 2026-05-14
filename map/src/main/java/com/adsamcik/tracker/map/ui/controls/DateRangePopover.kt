package com.adsamcik.tracker.map.ui.controls

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.adsamcik.tracker.map.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

internal enum class MapDateRangePreset(@StringRes val labelRes: Int) {
	TODAY(R.string.map_date_today),
	YESTERDAY(R.string.map_date_yesterday),
	THIS_WEEK(R.string.map_date_this_week),
	LAST_WEEK(R.string.map_date_preset_week),
	LAST_MONTH(R.string.map_date_preset_month),
	ALL_TIME(R.string.map_date_range_all_time),
}

internal data class MapDateRangePresetOption(
	val preset: MapDateRangePreset,
	val range: LongRange,
)

internal fun mapDateRangePresetOptions(
	nowMillis: Long = System.currentTimeMillis(),
	zoneId: ZoneId = ZoneId.systemDefault(),
): List<MapDateRangePresetOption> {
	val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
	val startOfToday = now.startOfLocalDay()
	val startOfYesterday = startOfToday.minusDays(1)
	val startOfThisWeek = now
		.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
		.startOfLocalDay()
	val startOfLastWeek = startOfThisWeek.minusWeeks(1)
	val startOfThisMonth = now.withDayOfMonth(1).startOfLocalDay()
	val startOfLastMonth = startOfThisMonth.minusMonths(1)

	return listOf(
		MapDateRangePresetOption(MapDateRangePreset.TODAY, startOfToday.toEpochMillis()..nowMillis),
		MapDateRangePresetOption(
			MapDateRangePreset.YESTERDAY,
			startOfYesterday.toEpochMillis()..startOfToday.toEpochMillis(),
		),
		MapDateRangePresetOption(MapDateRangePreset.THIS_WEEK, startOfThisWeek.toEpochMillis()..nowMillis),
		MapDateRangePresetOption(
			MapDateRangePreset.LAST_WEEK,
			startOfLastWeek.toEpochMillis()..startOfThisWeek.toEpochMillis(),
		),
		MapDateRangePresetOption(
			MapDateRangePreset.LAST_MONTH,
			startOfLastMonth.toEpochMillis()..startOfThisMonth.toEpochMillis(),
		),
		MapDateRangePresetOption(MapDateRangePreset.ALL_TIME, 0L..Long.MAX_VALUE),
	)
}

internal fun matchingMapDateRangePreset(
	range: LongRange,
	nowMillis: Long = System.currentTimeMillis(),
	zoneId: ZoneId = ZoneId.systemDefault(),
): MapDateRangePreset? {
	val options = mapDateRangePresetOptions(nowMillis, zoneId)
	val optionByPreset = options.associateBy { it.preset }
	val today = optionByPreset.getValue(MapDateRangePreset.TODAY).range
	val yesterday = optionByPreset.getValue(MapDateRangePreset.YESTERDAY).range
	val thisWeek = optionByPreset.getValue(MapDateRangePreset.THIS_WEEK).range
	val lastWeek = optionByPreset.getValue(MapDateRangePreset.LAST_WEEK).range
	val lastMonth = optionByPreset.getValue(MapDateRangePreset.LAST_MONTH).range
	val allTime = optionByPreset.getValue(MapDateRangePreset.ALL_TIME).range

	return when {
		range == allTime -> MapDateRangePreset.ALL_TIME
		range.first == today.first && range.last in today.first..nowMillis -> MapDateRangePreset.TODAY
		range == yesterday -> MapDateRangePreset.YESTERDAY
		range.first == thisWeek.first && range.last in thisWeek.first..nowMillis -> MapDateRangePreset.THIS_WEEK
		range == lastWeek -> MapDateRangePreset.LAST_WEEK
		range == lastMonth -> MapDateRangePreset.LAST_MONTH
		else -> null
	}
}

private fun ZonedDateTime.startOfLocalDay(): ZonedDateTime = toLocalDate().atStartOfDay(zone)

private fun ZonedDateTime.toEpochMillis(): Long = toInstant().toEpochMilli()

/**
 * Popover for date-range presets. Opens from the Dates chip; the custom range picker
 * is a separate [androidx.compose.material3.DatePickerDialog] that the caller owns —
 * this popover only emits a [onRequestCustom] signal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangePopover(
	currentRange: LongRange,
	onSetRange: (LongRange) -> Unit,
	onRequestCustom: () -> Unit,
	onDismiss: () -> Unit,
) {
	Popup(
		onDismissRequest = onDismiss,
		properties = PopupProperties(
			focusable = true,
			dismissOnBackPress = true,
			dismissOnClickOutside = true,
		),
	) {
		val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.fillMaxHeight()
				.background(Color.Black.copy(alpha = 0.32f))
				.clickable(onClick = onDismiss),
			contentAlignment = Alignment.BottomCenter,
		) {
			Surface(
				modifier = Modifier
					.fillMaxWidth()
					.padding(
						start = 16.dp,
						end = 16.dp,
						bottom = navBarInset + 128.dp,
					)
					.clickable(enabled = false, onClick = {}),
				shape = RoundedCornerShape(32.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				tonalElevation = 4.dp,
				shadowElevation = 3.dp,
			) {
				Column(
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
					verticalArrangement = Arrangement.spacedBy(14.dp),
				) {
					Text(
						text = stringResource(R.string.tips_map_date_range_title),
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.Bold,
					)

					val now = remember { System.currentTimeMillis() }
					val presetOptions = remember(now) { mapDateRangePresetOptions(now) }
					val selectedPreset = remember(currentRange, now) {
						matchingMapDateRangePreset(currentRange, now)
					}
					val isAllTime = selectedPreset == MapDateRangePreset.ALL_TIME
					val isCustom = selectedPreset == null

					FlowRow(
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
						modifier = Modifier.fillMaxWidth(),
					) {
						presetOptions.forEach { option ->
							FilterChip(
								selected = selectedPreset == option.preset,
								onClick = {
									onSetRange(option.range)
									onDismiss()
								},
								label = { Text(stringResource(option.preset.labelRes)) },
								shape = MaterialTheme.shapes.medium,
							)
						}
						FilterChip(
							selected = isCustom,
							onClick = {
								onRequestCustom()
								onDismiss()
							},
							label = { Text(stringResource(R.string.map_date_preset_custom)) },
							trailingIcon = {
								Icon(
									imageVector = Icons.Filled.DateRange,
									contentDescription = null,
									modifier = Modifier.size(18.dp),
								)
							},
							shape = MaterialTheme.shapes.medium,
						)
					}

					if (!isAllTime) {
						val rangeText = remember(currentRange) {
							val fmt = java.text.SimpleDateFormat(
								"MMM dd, yyyy",
								java.util.Locale.getDefault(),
							)
							"${fmt.format(java.util.Date(currentRange.first))} – " +
								fmt.format(java.util.Date(currentRange.last))
						}
						Text(
							text = rangeText,
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
		}
	}
}
