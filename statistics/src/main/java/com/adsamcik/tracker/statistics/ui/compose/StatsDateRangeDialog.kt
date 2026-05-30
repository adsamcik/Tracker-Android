package com.adsamcik.tracker.statistics.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.adsamcik.tracker.statistics.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters

/**
 * Quick-access preset rows above the calendar-grid picker. Most users want one of
 * these spans and never need to tap into a month grid; promoting them to chips
 * turns a three-tap interaction (open picker → tap start day → tap end day) into
 * a single tap for the common case.
 */
internal enum class StatsDateRangePreset(@StringRes val labelRes: Int) {
    TODAY(R.string.stats_date_preset_today),
    YESTERDAY(R.string.stats_date_preset_yesterday),
    LAST_SEVEN_DAYS(R.string.stats_date_preset_last_7_days),
    LAST_THIRTY_DAYS(R.string.stats_date_preset_last_30_days),
    THIS_MONTH(R.string.stats_date_preset_this_month),
    LAST_MONTH(R.string.stats_date_preset_last_month),
    THIS_YEAR(R.string.stats_date_preset_this_year),
    ALL_TIME(R.string.stats_date_preset_all_time),
}

internal data class StatsDateRangeOption(
    val preset: StatsDateRangePreset,
    val startMs: Long,
    val endMs: Long,
)

internal fun statsDateRangePresetOptions(
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<StatsDateRangeOption> {
    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
    val today = now.toLocalDate()

    val startOfToday = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val endOfTodayExclusive = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L

    val startOfYesterday = today.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val endOfYesterday = startOfToday - 1L

    // 7d / 30d include today: e.g. "Last 7 days" = today plus the previous 6.
    val startOfLast7 = today.minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val startOfLast30 = today.minusDays(29).atStartOfDay(zoneId).toInstant().toEpochMilli()

    val startOfThisMonth = today.withDayOfMonth(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val startOfLastMonth = today.minusMonths(1).withDayOfMonth(1)
        .atStartOfDay(zoneId).toInstant().toEpochMilli()
    val endOfLastMonth = startOfThisMonth - 1L

    val startOfThisYear = today.with(TemporalAdjusters.firstDayOfYear())
        .atStartOfDay(zoneId).toInstant().toEpochMilli()

    return listOf(
        StatsDateRangeOption(StatsDateRangePreset.TODAY, startOfToday, endOfTodayExclusive),
        StatsDateRangeOption(StatsDateRangePreset.YESTERDAY, startOfYesterday, endOfYesterday),
        StatsDateRangeOption(StatsDateRangePreset.LAST_SEVEN_DAYS, startOfLast7, endOfTodayExclusive),
        StatsDateRangeOption(StatsDateRangePreset.LAST_THIRTY_DAYS, startOfLast30, endOfTodayExclusive),
        StatsDateRangeOption(StatsDateRangePreset.THIS_MONTH, startOfThisMonth, endOfTodayExclusive),
        StatsDateRangeOption(StatsDateRangePreset.LAST_MONTH, startOfLastMonth, endOfLastMonth),
        StatsDateRangeOption(StatsDateRangePreset.THIS_YEAR, startOfThisYear, endOfTodayExclusive),
        // ALL_TIME uses sentinel "no filter" — caller maps to clearDateRange().
        StatsDateRangeOption(StatsDateRangePreset.ALL_TIME, Long.MIN_VALUE, Long.MAX_VALUE),
    )
}

/**
 * Heuristic that finds which preset (if any) the currently-active range matches.
 * Used to highlight the active chip when the dialog re-opens.
 */
internal fun matchingStatsDateRangePreset(
    startMs: Long?,
    endMs: Long?,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): StatsDateRangePreset? {
    if (startMs == null || endMs == null) return StatsDateRangePreset.ALL_TIME
    val options = statsDateRangePresetOptions(nowMillis, zoneId)
    // Exact-match presets first so e.g. yesterday doesn't get bucketed into "last 7 days".
    return options.firstOrNull { option ->
        option.preset != StatsDateRangePreset.ALL_TIME &&
            option.startMs == startMs && option.endMs == endMs
    }?.preset
}

private val readableFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun millisToLocalDate(ms: Long, zoneId: ZoneId): LocalDate =
    Instant.ofEpochMilli(ms).atZone(zoneId).toLocalDate()

/**
 * Custom date-range dialog. Sits above the Material 3 [DateRangePicker] with a
 * preset chip row so the common cases ("Last 7 days", "This month", ...) are
 * a single tap. The calendar grid stays available for custom spans.
 *
 * @param onConfirm receives the resolved start/end millis. For [StatsDateRangePreset.ALL_TIME]
 *   the caller should clear the date filter instead of recording the sentinel values.
 * @param onClear invoked when the user picks "All time" or taps the clear action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsDateRangeDialog(
    initialStartMs: Long?,
    initialEndMs: Long?,
    onConfirm: (startMs: Long, endMs: Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val now = remember { System.currentTimeMillis() }
    val presetOptions = remember(now, zoneId) { statsDateRangePresetOptions(now, zoneId) }
    val initialPreset = remember(initialStartMs, initialEndMs, now, zoneId) {
        matchingStatsDateRangePreset(initialStartMs, initialEndMs, now, zoneId)
    }

    var selectedPreset by remember { mutableStateOf(initialPreset) }
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStartMs,
        initialSelectedEndDateMillis = initialEndMs?.let { it - MILLIS_PER_DAY + 1L },
        initialDisplayMode = DisplayMode.Picker,
    )

    val pickerStart = pickerState.selectedStartDateMillis
    val pickerEnd = pickerState.selectedEndDateMillis
    val rangeValid = pickerStart != null && pickerEnd != null
    val rangeIsAllTime = selectedPreset == StatsDateRangePreset.ALL_TIME

    val summaryText = when {
        rangeIsAllTime -> stringResource(R.string.stats_date_preset_all_time)
        pickerStart != null && pickerEnd != null -> {
            val s = millisToLocalDate(pickerStart, zoneId)
            val e = millisToLocalDate(pickerEnd, zoneId)
            if (s == e) {
                readableFormatter.format(s)
            } else {
                "${readableFormatter.format(s)} → ${readableFormatter.format(e)}"
            }
        }
        else -> stringResource(R.string.stats_date_summary_placeholder)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Use unlimited width on phones so the calendar grid doesn't get cramped.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.stats_date_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 36.dp),
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Preset chip row — horizontally scrollable so adding more presets
                // later doesn't force the dialog to grow wider than the screen.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presetOptions.forEach { option ->
                        FilterChip(
                            selected = selectedPreset == option.preset,
                            onClick = {
                                selectedPreset = option.preset
                                if (option.preset != StatsDateRangePreset.ALL_TIME) {
                                    // Push preset into picker so the calendar grid
                                    // visually reflects the chosen span.
                                    pickerState.setSelection(
                                        startDateMillis = option.startMs,
                                        endDateMillis = option.endMs - MILLIS_PER_DAY + 1L,
                                    )
                                }
                            },
                            label = { Text(stringResource(option.preset.labelRes)) },
                        )
                    }
                }

                HorizontalDivider()

                // Calendar grid for custom spans. We deliberately keep this — power
                // users still want it — but it's now optional rather than the only
                // way to pick a date.
                DateRangePicker(
                    state = pickerState,
                    showModeToggle = false,
                    title = null,
                    headline = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (rangeIsAllTime) {
                        onClear()
                    } else if (rangeValid) {
                        val s = pickerStart!!
                        val e = pickerEnd!!
                        onConfirm(s, e + MILLIS_PER_DAY - 1L)
                    }
                },
                enabled = rangeIsAllTime || rangeValid,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (initialStartMs != null && initialEndMs != null && !rangeIsAllTime) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.stats_filter_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
