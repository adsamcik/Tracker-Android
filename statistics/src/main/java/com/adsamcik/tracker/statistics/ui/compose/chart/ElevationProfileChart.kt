package com.adsamcik.tracker.statistics.ui.compose.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.statistics.data.ChartPoint

/**
 * Canvas-based elevation profile chart.
 * Draws a filled area line chart suitable for showing elevation over distance.
 *
 * @param points Ordered elevation samples. Requires >= 2 points to draw.
 * @param modifier Layout modifier. Recommended height ~120.dp.
 * @param lineColor Stroke color for the elevation line.
 * @param fillColor Semi-transparent fill below the line.
 */
@Composable
fun ElevationProfileChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()
    val minY = points.minOfOrNull { it.y } ?: 0f
    val maxY = points.maxOfOrNull { it.y } ?: 0f

    Canvas(modifier = modifier.height(120.dp).fillMaxWidth()) {
        if (points.size < 2) return@Canvas

        val yRange = (maxY - minY).coerceAtLeast(1f)
        val verticalPadding = size.height * 0.05f

        fun yPos(value: Float): Float =
            size.height - ((value - minY) / yRange) * (size.height * 0.9f) - verticalPadding

        // Build line path
        val linePath = Path().apply {
            points.forEachIndexed { i, pt ->
                val x = (i.toFloat() / (points.size - 1)) * size.width
                val y = yPos(pt.y)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        // Build fill path (close to bottom)
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }

        drawPath(fillPath, fillColor)
        drawPath(
            linePath,
            lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )

        // Min/Max labels
        val minLabel = "%.0f".format(minY)
        val maxLabel = "%.0f".format(maxY)
        val minResult = textMeasurer.measure(minLabel, style = TextStyle.Default)
        val maxResult = textMeasurer.measure(maxLabel, style = TextStyle.Default)

        drawText(
            textLayoutResult = maxResult,
            color = labelColor,
            topLeft = Offset(4.dp.toPx(), yPos(maxY) - maxResult.size.height),
        )
        drawText(
            textLayoutResult = minResult,
            color = labelColor,
            topLeft = Offset(4.dp.toPx(), yPos(minY) + 2.dp.toPx()),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ElevationProfilePreview() {
    AppTheme(useDynamicColor = false) {
        ElevationProfileChart(
            points = listOf(
                ChartPoint(0f, 100f),
                ChartPoint(1f, 150f),
                ChartPoint(2f, 120f),
                ChartPoint(3f, 200f),
                ChartPoint(4f, 180f),
                ChartPoint(5f, 220f),
                ChartPoint(6f, 170f),
                ChartPoint(7f, 130f),
            ),
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ElevationProfileEmptyPreview() {
    AppTheme(useDynamicColor = false) {
        ElevationProfileChart(
            points = emptyList(),
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
    }
}
