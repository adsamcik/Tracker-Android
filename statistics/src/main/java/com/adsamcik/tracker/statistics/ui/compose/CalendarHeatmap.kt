package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * GitHub-style contribution calendar heatmap.
 * Displays a grid of small rounded squares colored by activity intensity.
 *
 * @param data Map of date to intensity (0.0 = no activity, 1.0 = max activity).
 *   Values outside 0–1 are clamped.
 * @param weeks Number of weeks to display (default 26 ≈ 6 months).
 * @param modifier Layout modifier.
 * @param baseColor Primary color used for intensity scaling.
 */
@Composable
fun CalendarHeatmap(
    data: Map<LocalDate, Float>,
    weeks: Int = 26,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.primary,
) {
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val todayBorderColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall

    val today = remember { LocalDate.now() }

    // Compute grid bounds: end on current week's Sunday, start `weeks` weeks before
    val grid = remember(today, weeks) {
        computeHeatmapGrid(today, weeks)
    }

    val density = LocalDensity.current
    val cellSizeDp = 12.dp
    val cellGapDp = 2.dp
    val dayLabelWidth = 24.dp
    val monthLabelHeight = 16.dp

    val cellSizePx = with(density) { cellSizeDp.toPx() }
    val cellGapPx = with(density) { cellGapDp.toPx() }
    val cellStride = cellSizePx + cellGapPx

    val totalWidthDp = dayLabelWidth + cellSizeDp * grid.weekCount + cellGapDp * (grid.weekCount - 1)
    val totalHeightDp = monthLabelHeight + cellSizeDp * 7 + cellGapDp * 6

    val textMeasurer = rememberTextMeasurer()
    val dayLabels = remember {
        val shortWeekdays = java.text.DateFormatSymbols.getInstance().shortWeekdays
        listOf(
            shortWeekdays[java.util.Calendar.MONDAY].take(2),
            shortWeekdays[java.util.Calendar.TUESDAY].take(2),
            shortWeekdays[java.util.Calendar.WEDNESDAY].take(2),
            shortWeekdays[java.util.Calendar.THURSDAY].take(2),
            shortWeekdays[java.util.Calendar.FRIDAY].take(2),
            shortWeekdays[java.util.Calendar.SATURDAY].take(2),
            shortWeekdays[java.util.Calendar.SUNDAY].take(2),
        )
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            // Day labels column
            Column(modifier = Modifier.width(dayLabelWidth)) {
                Spacer(Modifier.height(monthLabelHeight))
                dayLabels.forEach { label ->
                    Text(
                        text = label,
                        style = labelStyle,
                        color = labelColor,
                        modifier = Modifier.height(cellSizeDp + cellGapDp),
                    )
                }
            }

            // Grid canvas
            val activeDays = data.count { it.value > 0f }
            val heatmapDescription = if (activeDays > 0) {
                "$activeDays active day${if (activeDays != 1) "s" else ""} in the last $weeks weeks"
            } else {
                "No tracked activity in the last $weeks weeks"
            }
            Canvas(
                modifier = Modifier
                    .width(totalWidthDp - dayLabelWidth)
                    .height(totalHeightDp)
                    .semantics {
                        contentDescription = heatmapDescription
                    },
            ) {
                val monthLabelHeightPx = with(density) { monthLabelHeight.toPx() }

                // Month labels
                var lastMonth = -1
                grid.weeks.forEachIndexed { weekIdx, week ->
                    val firstDayOfWeek = week.firstOrNull() ?: return@forEachIndexed
                    val month = firstDayOfWeek.monthValue
                    if (month != lastMonth) {
                        lastMonth = month
                        val monthName = YearMonth.of(firstDayOfWeek.year, month)
                            .month
                            .getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
                        val result = textMeasurer.measure(
                            monthName,
                            style = TextStyle.Default,
                        )
                        val x = weekIdx * cellStride
                        if (x + result.size.width <= size.width) {
                            drawText(
                                textLayoutResult = result,
                                color = labelColor,
                                topLeft = Offset(x, 0f),
                            )
                        }
                    }
                }

                // Grid cells
                grid.weeks.forEachIndexed { weekIdx, week ->
                    week.forEachIndexed { dayIdx, date ->
                        val dayOfWeekIndex = date.dayOfWeek.value - 1 // Monday=0
                        val intensity = (data[date] ?: 0f).coerceIn(0f, 1f)
                        val cellColor = if (intensity > 0f) {
                            baseColor.copy(alpha = 0.2f + intensity * 0.8f)
                        } else {
                            emptyColor
                        }

                        val x = weekIdx * cellStride
                        val y = monthLabelHeightPx + dayOfWeekIndex * cellStride

                        drawRoundRect(
                            color = cellColor,
                            topLeft = Offset(x, y),
                            size = Size(cellSizePx, cellSizePx),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )

                        // Highlight today with a border
                        if (date == today) {
                            drawRoundRect(
                                color = todayBorderColor,
                                topLeft = Offset(x, y),
                                size = Size(cellSizePx, cellSizePx),
                                cornerRadius = CornerRadius(2.dp.toPx()),
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pre-computed heatmap grid data.
 */
internal data class HeatmapGrid(
    val weeks: List<List<LocalDate>>,
    val weekCount: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

/**
 * Computes the grid of dates for the heatmap.
 * Each inner list is a week (Mon–Sun), with dates filled only within range.
 */
internal fun computeHeatmapGrid(today: LocalDate, weeks: Int): HeatmapGrid {
    val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    val startDate = endOfWeek.minusWeeks(weeks.toLong() - 1)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    val weeksList = mutableListOf<List<LocalDate>>()
    var weekStart = startDate
    while (!weekStart.isAfter(endOfWeek)) {
        val week = (0 until 7).mapNotNull { dayOffset ->
            val date = weekStart.plusDays(dayOffset.toLong())
            if (!date.isAfter(today)) date else null
        }
        if (week.isNotEmpty()) {
            weeksList.add(week)
        }
        weekStart = weekStart.plusWeeks(1)
    }

    return HeatmapGrid(
        weeks = weeksList,
        weekCount = weeksList.size,
        startDate = startDate,
        endDate = endOfWeek,
    )
}

@Preview(showBackground = true)
@Composable
private fun CalendarHeatmapPreview() {
    val today = LocalDate.now()
    val sampleData = buildMap {
        for (i in 0L until 180L) {
            val date = today.minusDays(i)
            val intensity = when {
                i % 7 == 0L -> 1.0f
                i % 3 == 0L -> 0.6f
                i % 2 == 0L -> 0.3f
                else -> 0f
            }
            put(date, intensity)
        }
    }

    AppTheme(useDynamicColor = false) {
        CalendarHeatmap(
            data = sampleData,
            weeks = 26,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CalendarHeatmapEmptyPreview() {
    AppTheme(useDynamicColor = false) {
        CalendarHeatmap(
            data = emptyMap(),
            weeks = 26,
            modifier = Modifier.padding(16.dp),
        )
    }
}
