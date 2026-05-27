package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * GitHub-style contribution calendar heatmap that adapts to the available
 * width: cells are sized so the full `weeks` range fits without horizontal
 * scrolling. A compact day-of-week strip (M / W / F initials) is rendered
 * inside the same canvas so labels never get cropped at the edge.
 *
 * Includes a summary line above the grid and a "Less → More" colour legend
 * below it so the chart reads as a complete, self-contained visualisation
 * inside a dashboard card.
 *
 * @param data Map of date to intensity (0.0 = no activity, 1.0 = max activity).
 *   Values outside 0–1 are clamped.
 * @param weeks Number of weeks to display (default 18 ≈ 4 months). The grid
 *   shrinks the requested range down to whatever fits at the minimum cell
 *   size so very narrow viewports still render cleanly.
 * @param modifier Layout modifier.
 * @param baseColor Primary color used for intensity scaling.
 */
@Composable
fun CalendarHeatmap(
    data: Map<LocalDate, Float>,
    weeks: Int = 18,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.primary,
) {
    val emptyColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val todayBorderColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val today = remember { LocalDate.now() }

    // Compute grid bounds: end on current week's Sunday, start `weeks` weeks before
    val grid = remember(today, weeks) {
        computeHeatmapGrid(today, weeks)
    }

    val activeDays = remember(data) { data.count { it.value > 0f } }
    val accessibilityDescription = if (activeDays > 0) {
        "$activeDays active day${if (activeDays != 1) "s" else ""} in the last $weeks weeks"
    } else {
        "No tracked activity in the last $weeks weeks"
    }

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelTextStyle = remember(labelColor) {
        TextStyle(color = labelColor, fontSize = 10.sp)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Compact summary that immediately tells the user what the grid means
        // even before the colour scale is decoded.
        Text(
            text = accessibilityDescription,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val availableWidthDp = maxWidth

            // Geometry: reserve a tiny strip on the left for day-of-week
            // initials, leave a touch of breathing room on the right so the
            // last column never butts against the card edge.
            val dayLabelStripDp = 14.dp
            val rightPadDp = 4.dp
            val gapDp = 2.dp
            val minCellDp = 8.dp
            val maxCellDp = 18.dp

            // Choose how many weeks we can actually render at >= minCellDp,
            // capped to the requested count. This keeps the grid readable on
            // narrow viewports without changing the public API.
            val gridWidthDp = (availableWidthDp - dayLabelStripDp - rightPadDp).coerceAtLeast(0.dp)
            val maxFitWeeks = if (gridWidthDp <= 0.dp) {
                weeks
            } else {
                val perCell = minCellDp + gapDp
                ((gridWidthDp + gapDp) / perCell).toInt().coerceAtLeast(1)
            }
            val renderableWeeks = min(grid.weekCount, max(1, maxFitWeeks))

            val cellSizeDp: Dp = if (renderableWeeks > 0 && gridWidthDp > 0.dp) {
                val totalGap = gapDp * (renderableWeeks - 1)
                ((gridWidthDp - totalGap) / renderableWeeks).coerceIn(minCellDp, maxCellDp)
            } else {
                12.dp
            }

            val monthLabelHeightDp = 14.dp
            val totalHeightDp = monthLabelHeightDp + cellSizeDp * 7 + gapDp * 6

            val cellSizePx = with(density) { cellSizeDp.toPx() }
            val cellGapPx = with(density) { gapDp.toPx() }
            val cellStride = cellSizePx + cellGapPx
            val dayLabelStripPx = with(density) { dayLabelStripDp.toPx() }
            val monthLabelHeightPx = with(density) { monthLabelHeightDp.toPx() }

            // Show only the most recent `renderableWeeks` weeks so the right
            // edge of the chart is always "today" — the part the user cares
            // about most.
            val visibleWeeks = if (grid.weekCount <= renderableWeeks) {
                grid.weeks
            } else {
                grid.weeks.takeLast(renderableWeeks)
            }

            // Day-label drawing: only every other day to avoid clutter at small
            // cell sizes; rendered as single-letter initials.
            val shortWeekdays = java.text.DateFormatSymbols.getInstance().shortWeekdays
            val dayInitials = listOf(
                0 to shortWeekdays[java.util.Calendar.MONDAY].take(1),
                2 to shortWeekdays[java.util.Calendar.WEDNESDAY].take(1),
                4 to shortWeekdays[java.util.Calendar.FRIDAY].take(1),
            )

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeightDp)
                    .semantics {
                        contentDescription = accessibilityDescription
                    },
            ) {
                // Day-of-week initials, centred next to their row.
                dayInitials.forEach { (dayIdx, label) ->
                    val measured = textMeasurer.measure(label, style = labelTextStyle)
                    val y = monthLabelHeightPx + dayIdx * cellStride + cellSizePx / 2f - measured.size.height / 2f
                    drawText(
                        textLayoutResult = measured,
                        color = labelColor,
                        topLeft = Offset(0f, y),
                    )
                }

                // Month labels — only emitted when there's room for the full
                // abbreviation; otherwise skipped so we never get a cropped
                // half-word like "Ma" at the edge.
                var lastMonth = -1
                visibleWeeks.forEachIndexed { weekIdx, week ->
                    val firstDayOfWeek = week.firstOrNull() ?: return@forEachIndexed
                    val month = firstDayOfWeek.monthValue
                    if (month != lastMonth) {
                        lastMonth = month
                        val monthName = YearMonth.of(firstDayOfWeek.year, month)
                            .month
                            .getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
                        val result = textMeasurer.measure(monthName, style = labelTextStyle)
                        val x = dayLabelStripPx + weekIdx * cellStride
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
                visibleWeeks.forEachIndexed { weekIdx, week ->
                    week.forEach { date ->
                        val dayOfWeekIndex = date.dayOfWeek.value - 1 // Monday=0
                        val intensity = (data[date] ?: 0f).coerceIn(0f, 1f)
                        val cellColor = if (intensity > 0f) {
                            baseColor.copy(alpha = 0.25f + intensity * 0.75f)
                        } else {
                            emptyColor
                        }

                        val x = dayLabelStripPx + weekIdx * cellStride
                        val y = monthLabelHeightPx + dayOfWeekIndex * cellStride

                        drawRoundRect(
                            color = cellColor,
                            topLeft = Offset(x, y),
                            size = Size(cellSizePx, cellSizePx),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )

                        // Highlight today with a primary-tinted border.
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

        Spacer(Modifier.height(6.dp))
        HeatmapLegend(
            baseColor = baseColor,
            emptyColor = emptyColor,
            labelColor = labelColor,
        )
    }
}

@Composable
private fun HeatmapLegend(
    baseColor: Color,
    emptyColor: Color,
    labelColor: Color,
) {
    val swatchSize = 10.dp
    val swatchShape = RoundedCornerShape(2.dp)
    val swatches = remember(baseColor, emptyColor) {
        listOf(
            emptyColor,
            baseColor.copy(alpha = 0.35f),
            baseColor.copy(alpha = 0.55f),
            baseColor.copy(alpha = 0.75f),
            baseColor.copy(alpha = 0.95f),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Less",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )
        Spacer(Modifier.width(6.dp))
        swatches.forEach { color ->
            Box(
                modifier = Modifier
                    .size(swatchSize)
                    .clip(swatchShape)
                    .background(color),
            )
            Spacer(Modifier.width(2.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "More",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )
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
            weeks = 18,
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
            weeks = 18,
            modifier = Modifier.padding(16.dp),
        )
    }
}
