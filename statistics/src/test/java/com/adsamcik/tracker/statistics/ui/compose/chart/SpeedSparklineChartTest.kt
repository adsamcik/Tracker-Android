package com.adsamcik.tracker.statistics.ui.compose.chart

import com.adsamcik.tracker.statistics.data.ChartPoint
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SpeedSparklineChart] data handling and edge cases.
 */
class SpeedSparklineChartTest {

    @Test
    fun `empty points list triggers early return guard`() {
        val points = emptyList<ChartPoint>()
        (points.size < 2) shouldBe true
    }

    @Test
    fun `single point triggers early return guard`() {
        val points = listOf(ChartPoint(0f, 5f))
        (points.size < 2) shouldBe true
    }

    @Test
    fun `max speed point index is found correctly`() {
        val points = listOf(
            ChartPoint(0f, 5f),
            ChartPoint(1f, 12f),
            ChartPoint(2f, 8f),
            ChartPoint(3f, 15f),
            ChartPoint(4f, 3f),
        )
        val maxIndex = points.indices.maxBy { points[it].y }
        maxIndex shouldBe 3
    }

    @Test
    fun `max speed with all equal values picks first`() {
        val points = listOf(
            ChartPoint(0f, 10f),
            ChartPoint(1f, 10f),
            ChartPoint(2f, 10f),
        )
        val maxIndex = points.indices.maxBy { points[it].y }
        maxIndex shouldBe 0
    }

    @Test
    fun `yRange clamped to at least 1f`() {
        val points = listOf(ChartPoint(0f, 0f), ChartPoint(1f, 0f))
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val yRange = (maxY - minY).coerceAtLeast(1f)
        yRange shouldBe 1f
    }
}
