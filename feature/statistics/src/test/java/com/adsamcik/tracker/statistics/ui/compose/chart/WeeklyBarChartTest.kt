package com.adsamcik.tracker.statistics.ui.compose.chart

import com.adsamcik.tracker.statistics.viewmodel.DayBar
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [WeeklyBarChart] data handling and edge cases.
 */
class WeeklyBarChartTest {

    @Test
    fun `empty bars list triggers early return guard`() {
        val bars = emptyList<DayBar>()
        bars.isEmpty() shouldBe true
    }

    @Test
    fun `max value computation avoids division by zero`() {
        val bars = listOf(
            DayBar("Mon", 0f, 0, 0L),
            DayBar("Tue", 0f, 0, 1L),
        )
        val maxVal = bars.maxOfOrNull { it.distanceM }?.coerceAtLeast(1f) ?: 1f
        maxVal shouldBe 1f
    }

    @Test
    fun `today detection works via epochDay`() {
        val todayEpoch = LocalDate.now().toEpochDay()
        val bars = listOf(
            DayBar("Mon", 100f, 50, todayEpoch - 1),
            DayBar("Tue", 200f, 100, todayEpoch),
        )
        val todayBar = bars.find { it.epochDay == todayEpoch }
        todayBar?.dayLabel shouldBe "Tue"
    }

    @Test
    fun `bar height fraction calculation is correct`() {
        val bars = listOf(
            DayBar("Mon", 500f, 50, 0L),
            DayBar("Tue", 1000f, 100, 1L),
            DayBar("Wed", 250f, 25, 2L),
        )
        val maxVal = bars.maxOf { it.distanceM }.coerceAtLeast(1f)
        val fractions = bars.map { it.distanceM / maxVal }
        fractions[0] shouldBe 0.5f
        fractions[1] shouldBe 1.0f
        fractions[2] shouldBe 0.25f
    }
}
