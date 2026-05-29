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
 * scrolling. A compact day-of-week strip (all seven narrow initials,
 * weekend rows muted) is rendered inside the same canvas so labels never
 * get cropped at the edge.
 *
 * Includes a summary line above the grid and a "Less → More" colour legend
 * below it so the chart reads as a complete, self-contained visualisation
 * inside a dashboard card. When `data` is empty an overlay invites the
 * user to start tracking rather than leaving the user to decode a sea of
 * identically-empty cells.
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
    val emptyOutlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val todayBorderColor = MaterialTheme.colorScheme.tertiary
    val monthDividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val mutedLabelColor = labelColor.copy(alpha = 0.55f)
    val emptyStateBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    val emptyStateColor = MaterialTheme.colorScheme.onSurface

    val today = remember { LocalDate.now() }
    val locale = remember { Locale.getDefault() }

    // Compute grid bounds: end on current week's Sunday, start `weeks` weeks before
    val grid = remember(today, weeks) {
        computeHeatmapGrid(today, weeks)
    }

    val activeDays = remember(data) { data.count { it.value > 0f } }
    val isEmpty = activeDays == 0
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
    val mutedLabelTextStyle = remember(mutedLabelColor) {
        TextStyle(color = mutedLabelColor, fontSize = 10.sp)
    }
    val monthLabelTextStyle = remember(labelColor) {
        TextStyle(color = labelColor, fontSize = 11.sp)
    }
    val emptyStateTextStyle = remember(emptyStateColor) {
        TextStyle(color = emptyStateColor, fontSize = 13.sp)
    }

    // Locale-aware narrow day labels (e.g. en: M T W T F S S; cs: P Ú S Č P S N).
    // Saturday/Sunday are flagged so their row labels can render muted to
    // give the eye a weekly rhythm even when all seven labels are visible.
    val dayLabels = remember(locale) {
        listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY,
        ).map { dow ->
            val narrow = dow.getDisplayName(JavaTextStyle.NARROW, locale).ifBlank { dow.name.take(1) }
            DayLabel(initial = narrow, isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
        }
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

            // Geometry: reserve a left strip wide enough to fit narrow
            // single-letter day labels comfortably, leave breathing room on
            // the right so the last column never butts against the card edge.
            val dayLabelStripDp = 18.dp
            val rightPadDp = 4.dp
            val gapDp = 2.dp
            val minCellDp = 10.dp
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

            val monthLabelHeightDp = 16.dp
            val totalHeightDp = monthLabelHeightDp + cellSizeDp * 7 + gapDp * 6

            val cellSizePx = with(density) { cellSizeDp.toPx() }
            val cellGapPx = with(density) { gapDp.toPx() }
            val cellStride = cellSizePx + cellGapPx
            val dayLabelStripPx = with(density) { dayLabelStripDp.toPx() }
            val monthLabelHeightPx = with(density) { monthLabelHeightDp.toPx() }
            val outlineStrokePx = with(density) { 0.75.dp.toPx() }
            val todayStrokePx = with(density) { 2.dp.toPx() }
            val monthGapPx = with(density) { 28.dp.toPx() }

            // Show only the most recent `renderableWeeks` weeks so the right
            // edge of the chart is always "today" — the part the user cares
            // about most.
            val visibleWeeks = if (grid.weekCount <= renderableWeeks) {
                grid.weeks
            } else {
                grid.weeks.takeLast(renderableWeeks)
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeightDp)
                    .semantics {
                        contentDescription = accessibilityDescription
                    },
            ) {
                // Day-of-week initials, all seven shown. Weekend rows render
                // in a muted tone so the duplicated narrow letters (T/T and
                // S/S in en) read as a rhythm rather than a misprint.
                dayLabels.forEachIndexed { dayIdx, label ->
                    val style = if (label.isWeekend) mutedLabelTextStyle else labelTextStyle
                    val measured = textMeasurer.measure(label.initial, style = style)
                    val y = monthLabelHeightPx + dayIdx * cellStride + cellSizePx / 2f - measured.size.height / 2f
                    val labelX = (dayLabelStripPx - cellGapPx - measured.size.width).coerceAtLeast(0f)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(labelX, y),
                    )
                }

                // Month labels — drawn at the first week whose majority of
                // days (≥4 of 7) fall in the new month, with a faint vertical
                // divider in the same column so the eye can group weeks. Min
                // gap prevents adjacent months from overlapping when several
                // start back-to-back in early-week positions.
                var lastMonth = -1
                var lastLabelEndX = Float.NEGATIVE_INFINITY
                visibleWeeks.forEachIndexed { weekIdx, week ->
                    val dominantMonth = week
                        .groupingBy { it.monthValue }
                        .eachCount()
                        .maxByOrNull { it.value }
                        ?.key ?: return@forEachIndexed
                    if (dominantMonth == lastMonth) return@forEachIndexed
                    lastMonth = dominantMonth

                    val monthName = YearMonth.of(week.first().year, dominantMonth)
                        .month
                        .getDisplayName(JavaTextStyle.SHORT, locale)
                    val result = textMeasurer.measure(monthName, style = monthLabelTextStyle)
                    val x = dayLabelStripPx + weekIdx * cellStride

                    // Always draw the divider when there's room — it helps
                    // even when the label itself gets dropped for spacing.
                    if (x in dayLabelStripPx..size.width && weekIdx > 0) {
                        drawLine(
                            color = monthDividerColor,
                            start = Offset(x - cellGapPx / 2f, monthLabelHeightPx),
                            end = Offset(x - cellGapPx / 2f, size.height),
                            strokeWidth = outlineStrokePx,
                        )
                    }

                    val labelFits = x + result.size.width <= size.width
                    val notTooClose = x >= lastLabelEndX + monthGapPx
                    if (labelFits && notTooClose) {
                        drawText(
                            textLayoutResult = result,
                            topLeft = Offset(x, 0f),
                        )
                        lastLabelEndX = x + result.size.width
                    }
                }

                // Grid cells
                visibleWeeks.forEachIndexed { weekIdx, week ->
                    week.forEach { date ->
                        val dayOfWeekIndex = date.dayOfWeek.value - 1 // Monday=0
                        val intensity = (data[date] ?: 0f).coerceIn(0f, 1f)
                        val isFilled = intensity > 0f
                        val cellColor = if (isFilled) {
                            baseColor.copy(alpha = 0.35f + intensity * 0.65f)
                        } else {
                            emptyColor
                        }

                        val x = dayLabelStripPx + weekIdx * cellStride
                        val y = monthLabelHeightPx + dayOfWeekIndex * cellStride
                        val cornerRadius = CornerRadius(2.dp.toPx())

                        drawRoundRect(
                            color = cellColor,
                            topLeft = Offset(x, y),
                            size = Size(cellSizePx, cellSizePx),
                            cornerRadius = cornerRadius,
                        )

                        // Faint outline on empty cells so the grid is legible
                        // even on cards whose container colour blends into
                        // surfaceContainerHigh (the previous design's
                        // empty cells were nearly invisible there).
                        if (!isFilled) {
                            drawRoundRect(
                                color = emptyOutlineColor,
                                topLeft = Offset(x, y),
                                size = Size(cellSizePx, cellSizePx),
                                cornerRadius = cornerRadius,
                                style = Stroke(width = outlineStrokePx),
                            )
                        }

                        // Highlight today with a tertiary-tinted border so
                        // it never reads as "another active day" — the
                        // primary-tinted fills already use baseColor.
                        if (date == today) {
                            drawRoundRect(
                                color = todayBorderColor,
                                topLeft = Offset(x, y),
                                size = Size(cellSizePx, cellSizePx),
                                cornerRadius = cornerRadius,
                                style = Stroke(width = todayStrokePx),
                            )
                        }
                    }
                }

                // Empty-state overlay: when there is literally nothing to
                // display, the grid is just a wall of identical cells.
                // Cover it with a centred call to action so the chart
                // communicates *something*.
                if (isEmpty) {
                    val message = "Start tracking to fill in your calendar"
                    val measured = textMeasurer.measure(message, style = emptyStateTextStyle)
                    val pillPadX = with(density) { 12.dp.toPx() }
                    val pillPadY = with(density) { 6.dp.toPx() }
                    val pillWidth = measured.size.width + pillPadX * 2f
                    val pillHeight = measured.size.height + pillPadY * 2f
                    val pillX = (size.width - pillWidth) / 2f
                    val pillY = monthLabelHeightPx + (size.height - monthLabelHeightPx - pillHeight) / 2f
                    drawRoundRect(
                        color = emptyStateBackground,
                        topLeft = Offset(pillX, pillY),
                        size = Size(pillWidth, pillHeight),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(pillX + pillPadX, pillY + pillPadY),
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        HeatmapLegend(
            baseColor = baseColor,
            emptyColor = emptyColor,
            emptyOutlineColor = emptyOutlineColor,
            labelColor = labelColor,
            todayBorderColor = todayBorderColor,
        )
    }
}

private data class DayLabel(val initial: String, val isWeekend: Boolean)

@Composable
private fun HeatmapLegend(
    baseColor: Color,
    emptyColor: Color,
    emptyOutlineColor: Color,
    labelColor: Color,
    todayBorderColor: Color,
) {
    val swatchSize = 10.dp
    val swatchShape = RoundedCornerShape(2.dp)
    val swatches = remember(baseColor) {
        listOf(
            baseColor.copy(alpha = 0.35f),
            baseColor.copy(alpha = 0.55f),
            baseColor.copy(alpha = 0.75f),
            baseColor.copy(alpha = 1.0f),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Today" indicator legend swatch — same tertiary border the grid
        // uses, so users can map it back without trial and error.
        Box(
            modifier = Modifier
                .size(swatchSize)
                .clip(swatchShape)
                .background(emptyColor),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(swatchSize)) {
                drawRoundRect(
                    color = todayBorderColor,
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Today",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )

        Spacer(Modifier.width(12.dp))

        Text(
            text = "Less",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )
        Spacer(Modifier.width(6.dp))
        // Empty swatch first, so the scale clearly starts from "nothing"
        // and ramps up to "max" without leaving readers to guess where
        // the bottom of the colour ramp lives.
        Box(
            modifier = Modifier
                .size(swatchSize)
                .clip(swatchShape)
                .background(emptyColor),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(swatchSize)) {
                drawRoundRect(
                    color = emptyOutlineColor,
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(width = 0.75.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.width(2.dp))
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
