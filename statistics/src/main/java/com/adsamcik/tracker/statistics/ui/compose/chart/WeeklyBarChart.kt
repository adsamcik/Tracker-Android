package com.adsamcik.tracker.statistics.ui.compose.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.statistics.viewmodel.DayBar
import java.time.LocalDate

/**
 * Canvas-based weekly bar chart showing 7 days of activity.
 * Today's bar is highlighted with a distinct color.
 *
 * @param bars List of [DayBar] entries (typically 7 for Mon–Sun).
 * @param modifier Layout modifier. Recommended height ~140.dp.
 * @param barColor Default bar fill color.
 * @param todayColor Highlight color for today's bar.
 */
@Composable
fun WeeklyBarChart(
    bars: List<DayBar>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primaryContainer,
    todayColor: Color = MaterialTheme.colorScheme.primary,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val todayEpochDay = LocalDate.now().toEpochDay()

    Canvas(modifier = modifier.height(140.dp).fillMaxWidth()) {
        if (bars.isEmpty()) return@Canvas

        val maxVal = bars.maxOf { it.distanceM }.coerceAtLeast(1f)
        val barCount = bars.size
        val totalGap = 6.dp.toPx() * (barCount - 1)
        val barWidth = (size.width - totalGap) / barCount
        val labelArea = 20.dp.toPx()
        val chartHeight = size.height - labelArea - 16.dp.toPx() // top padding for value labels
        val topPadding = 16.dp.toPx()

        bars.forEachIndexed { i, dayBar ->
            val isToday = dayBar.epochDay == todayEpochDay
            val fraction = dayBar.distanceM / maxVal
            val barHeight = fraction * chartHeight
            val x = i * (barWidth + 6.dp.toPx())
            val y = topPadding + chartHeight - barHeight

            // Bar
            val color = if (isToday) todayColor else barColor
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight.coerceAtLeast(2.dp.toPx())),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )

            // Today border indicator
            if (isToday) {
                drawRoundRect(
                    color = todayColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight.coerceAtLeast(2.dp.toPx())),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }

            // Day label below bar
            val dayResult = textMeasurer.measure(
                dayBar.dayLabel,
                style = TextStyle(textAlign = TextAlign.Center),
            )
            drawText(
                textLayoutResult = dayResult,
                color = if (isToday) todayColor else labelColor,
                topLeft = Offset(
                    x + (barWidth - dayResult.size.width) / 2f,
                    topPadding + chartHeight + 4.dp.toPx(),
                ),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WeeklyBarChartPreview() {
    val todayEpoch = LocalDate.now().toEpochDay()
    AppTheme(useDynamicColor = false) {
        WeeklyBarChart(
            bars = listOf(
                DayBar("Mon", 1200f, 3000, todayEpoch - 6),
                DayBar("Tue", 800f, 2000, todayEpoch - 5),
                DayBar("Wed", 2000f, 5000, todayEpoch - 4),
                DayBar("Thu", 0f, 0, todayEpoch - 3),
                DayBar("Fri", 1500f, 4000, todayEpoch - 2),
                DayBar("Sat", 3000f, 8000, todayEpoch - 1),
                DayBar("Sun", 500f, 1200, todayEpoch),
            ),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WeeklyBarChartEmptyPreview() {
    AppTheme(useDynamicColor = false) {
        WeeklyBarChart(
            bars = emptyList(),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }
}
