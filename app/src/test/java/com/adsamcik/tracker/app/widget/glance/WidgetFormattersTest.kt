package com.adsamcik.tracker.app.widget.glance

import com.adsamcik.tracker.shared.model.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("WidgetFormatters")
class WidgetFormattersTest {

    @Nested
    @DisplayName("formatDuration")
    inner class FormatDuration {

        @Test
        fun `zero duration returns 0 colon 00`() {
            assertEquals("0:00", WidgetFormatters.formatDuration(0L))
        }

        @Test
        fun `seconds only formats as m colon ss`() {
            assertEquals("0:45", WidgetFormatters.formatDuration(45_000L))
        }

        @Test
        fun `minutes and seconds formats correctly`() {
            assertEquals("5:30", WidgetFormatters.formatDuration(330_000L))
        }

        @Test
        fun `exactly one hour uses h colon mm colon ss format`() {
            assertEquals("1:00:00", WidgetFormatters.formatDuration(3_600_000L))
        }

        @Test
        fun `hours minutes seconds all nonzero`() {
            // 2h 15m 30s = 8130000ms
            assertEquals("2:15:30", WidgetFormatters.formatDuration(8_130_000L))
        }

        @Test
        fun `large duration formats correctly`() {
            // 25h 0m 0s
            assertEquals("25:00:00", WidgetFormatters.formatDuration(90_000_000L))
        }

        @ParameterizedTest(name = "{0}ms → {1}")
        @CsvSource(
            "0, '0:00'",
            "1000, '0:01'",
            "59000, '0:59'",
            "60000, '1:00'",
            "3599000, '59:59'",
            "3600000, '1:00:00'",
            "3661000, '1:01:01'",
        )
        fun `parameterized duration formatting`(ms: Long, expected: String) {
            assertEquals(expected, WidgetFormatters.formatDuration(ms))
        }
    }

    @Nested
    @DisplayName("formatSteps")
    inner class FormatSteps {

        @Test
        fun `zero steps`() {
            assertEquals("0", WidgetFormatters.formatSteps(0))
        }

        @Test
        fun `small number no separator`() {
            assertEquals("999", WidgetFormatters.formatSteps(999))
        }

        @Test
        fun `thousands get separator`() {
            val result = WidgetFormatters.formatSteps(1_234)
            // Locale-dependent separator; just verify the digits are present
            assert(result.contains("1")) { "Expected '1' in $result" }
            assert(result.contains("234")) { "Expected '234' in $result" }
        }

        @Test
        fun `large number formats with separators`() {
            val result = WidgetFormatters.formatSteps(12_345_678)
            assert(result.contains("12")) { "Expected '12' in $result" }
            assert(result.contains("345")) { "Expected '345' in $result" }
            assert(result.contains("678")) { "Expected '678' in $result" }
        }
    }

    @Nested
    @DisplayName("formatSessionSteps")
    inner class FormatSessionSteps {

        @Test
        fun `positive session progress remains visible`() {
            assertEquals(
                WidgetFormatters.formatSteps(1_234),
                WidgetFormatters.formatSessionSteps(1_234, unavailableText = "Unavailable"),
            )
        }

        @Test
        fun `ambiguous zero is unavailable`() {
            assertEquals(
                "Unavailable",
                WidgetFormatters.formatSessionSteps(0, unavailableText = "Unavailable"),
            )
        }

        @Test
        fun `negative legacy value is unavailable`() {
            assertEquals(
                "missing",
                WidgetFormatters.formatSessionSteps(-1, unavailableText = "missing"),
            )
        }
    }

    @Nested
    @DisplayName("formatGoalProgress")
    inner class FormatGoalProgress {

        @Test
        fun `zero progress`() {
            assertEquals("0%", WidgetFormatters.formatGoalProgress(0f))
        }

        @Test
        fun `half progress`() {
            assertEquals("50%", WidgetFormatters.formatGoalProgress(0.5f))
        }

        @Test
        fun `full progress`() {
            assertEquals("100%", WidgetFormatters.formatGoalProgress(1.0f))
        }

        @Test
        fun `over 100 percent clamped`() {
            assertEquals("100%", WidgetFormatters.formatGoalProgress(1.5f))
        }

        @Test
        fun `negative clamped to zero`() {
            assertEquals("0%", WidgetFormatters.formatGoalProgress(-0.1f))
        }

        @Test
        fun `fractional progress rounds down`() {
            assertEquals("33%", WidgetFormatters.formatGoalProgress(0.333f))
        }
    }

    @Nested
    @DisplayName("formatPathPreview")
    inner class FormatPathPreview {

        @Test
        fun `not enough points returns dot`() {
            assertEquals("•", WidgetFormatters.formatPathPreview(emptyList()))
        }

        @Test
        fun `diagonal route renders arrow sequence`() {
            val points = listOf(
                testLocation(50.0, 14.0),
                testLocation(50.001, 14.001),
                testLocation(50.002, 14.001),
            )

            assertEquals("↗ ↑", WidgetFormatters.formatPathPreview(points))
        }
    }

    private fun testLocation(latitude: Double, longitude: Double) =
        Location(
            time = 0L,
            latitude = latitude,
            longitude = longitude,
            altitude = null,
            horizontalAccuracy = null,
            verticalAccuracy = null,
            speed = null,
            speedAccuracy = null,
        )
}
