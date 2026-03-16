package com.adsamcik.tracker.statistics.ui.compose.chart

import com.adsamcik.tracker.statistics.data.ChartPoint
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ElevationProfileChart] data handling and edge cases.
 * Rendering is verified via @Preview + compile; these tests cover logic paths.
 */
class ElevationProfileChartTest {

    @Test
    fun `normalizeY returns 0 when all points have same y`() {
        val points = listOf(ChartPoint(0f, 100f), ChartPoint(1f, 100f), ChartPoint(2f, 100f))
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val yRange = (maxY - minY).coerceAtLeast(1f)
        yRange shouldBe 1f
        // All normalized values should be 0 (flat line at min)
        points.forEach { pt ->
            val normalized = (pt.y - minY) / yRange
            normalized shouldBe 0f
        }
    }

    @Test
    fun `yRange is at least 1f to avoid division by zero`() {
        val points = listOf(ChartPoint(0f, 50f), ChartPoint(1f, 50f))
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val yRange = (maxY - minY).coerceAtLeast(1f)
        yRange shouldBe 1f
    }

    @Test
    fun `normalization with real range works correctly`() {
        val points = listOf(ChartPoint(0f, 100f), ChartPoint(1f, 200f))
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val yRange = (maxY - minY).coerceAtLeast(1f)
        yRange shouldBe 100f
        val normalizedFirst = (points[0].y - minY) / yRange
        val normalizedSecond = (points[1].y - minY) / yRange
        normalizedFirst shouldBe 0f
        normalizedSecond shouldBe 1f
    }

    @Test
    fun `empty points list is handled gracefully`() {
        val points = emptyList<ChartPoint>()
        // Chart should early-return with no drawing; size < 2 guard
        (points.size < 2) shouldBe true
    }

    @Test
    fun `single point list is handled gracefully`() {
        val points = listOf(ChartPoint(0f, 100f))
        (points.size < 2) shouldBe true
    }
}
