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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.statistics.data.ChartPoint

/**
 * Lightweight speed sparkline chart.
 * Draws a thin line without fill, with an optional dot on the maximum speed point.
 *
 * @param points Ordered speed samples. Requires >= 2 points to draw.
 * @param modifier Layout modifier. Recommended height ~64.dp.
 * @param lineColor Stroke color for the sparkline.
 * @param showMaxDot When true, draws a small circle at the maximum value.
 * @param maxDotColor Color for the max-speed indicator dot.
 */
@Composable
fun SpeedSparklineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.tertiary,
    showMaxDot: Boolean = true,
    maxDotColor: Color = MaterialTheme.colorScheme.error,
) {
    Canvas(modifier = modifier.height(64.dp).fillMaxWidth()) {
        if (points.size < 2) return@Canvas

        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val yRange = (maxY - minY).coerceAtLeast(1f)
        val verticalPadding = size.height * 0.1f

        fun xPos(index: Int): Float = (index.toFloat() / (points.size - 1)) * size.width
        fun yPos(value: Float): Float =
            size.height - verticalPadding - ((value - minY) / yRange) * (size.height - 2 * verticalPadding)

        val linePath = Path().apply {
            points.forEachIndexed { i, pt ->
                val x = xPos(i)
                val y = yPos(pt.y)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        drawPath(
            linePath,
            lineColor,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
        )

        // Max-speed indicator dot
        if (showMaxDot) {
            val maxIndex = points.indices.maxBy { points[it].y }
            val dotX = xPos(maxIndex)
            val dotY = yPos(points[maxIndex].y)
            drawCircle(
                color = maxDotColor,
                radius = 3.dp.toPx(),
                center = Offset(dotX, dotY),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SpeedSparklinePreview() {
    AppTheme(useDynamicColor = false) {
        SpeedSparklineChart(
            points = listOf(
                ChartPoint(0f, 5f),
                ChartPoint(1f, 8f),
                ChartPoint(2f, 12f),
                ChartPoint(3f, 7f),
                ChartPoint(4f, 15f),
                ChartPoint(5f, 10f),
                ChartPoint(6f, 6f),
            ),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SpeedSparklineEmptyPreview() {
    AppTheme(useDynamicColor = false) {
        SpeedSparklineChart(
            points = emptyList(),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        )
    }
}
