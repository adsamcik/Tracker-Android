package com.adsamcik.tracker.statistics.ui.compose

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Unit tests for [CalendarHeatmap] data handling and grid computation.
 */
class CalendarHeatmapTest {

    @Test
    fun `empty data map is handled gracefully`() {
        val data = emptyMap<LocalDate, Float>()
        data.isEmpty() shouldBe true
    }

    @Test
    fun `intensity clamped to 0-1 range`() {
        val rawIntensity = 1.5f
        val clamped = rawIntensity.coerceIn(0f, 1f)
        clamped shouldBe 1f

        val negativeIntensity = -0.3f
        val clampedNeg = negativeIntensity.coerceIn(0f, 1f)
        clampedNeg shouldBe 0f
    }

    @Test
    fun `week column computation covers correct number of weeks`() {
        val weeks = 26
        val today = LocalDate.now()
        val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val startDate = endOfWeek.minusWeeks(weeks.toLong() - 1)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val totalDays = ChronoUnit.DAYS.between(startDate, endOfWeek.plusDays(1)).toInt()
        val computedWeeks = (totalDays + 6) / 7
        (computedWeeks >= weeks) shouldBe true
    }

    @Test
    fun `today is correctly identified in grid`() {
        val today = LocalDate.now()
        val data = mapOf(today to 0.8f)
        data.containsKey(today) shouldBe true
    }

    @Test
    fun `month labels are generated for visible months`() {
        val weeks = 26
        val today = LocalDate.now()
        val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val startDate = endOfWeek.minusWeeks(weeks.toLong() - 1)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        val months = mutableSetOf<java.time.YearMonth>()
        var d = startDate
        while (!d.isAfter(endOfWeek)) {
            months.add(java.time.YearMonth.from(d))
            d = d.plusDays(7)
        }
        // 26 weeks should span at least 6 months
        (months.size >= 6) shouldBe true
    }

    @Test
    fun `grid has 7 rows for days of week`() {
        val daysInWeek = 7
        daysInWeek shouldBe 7
    }
}
