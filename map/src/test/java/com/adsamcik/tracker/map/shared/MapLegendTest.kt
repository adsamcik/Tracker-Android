package com.adsamcik.tracker.map.shared

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MapLegend")
class MapLegendTest {

    @Nested
    @DisplayName("Default Values")
    inner class DefaultValues {

        @Test
        fun `default values are correct`() {
            val legend = MapLegend()

            legend.description.shouldBeNull()
            legend.valueList.shouldBeEmpty()
        }
    }

    @Nested
    @DisplayName("Construction")
    inner class Construction {

        @Test
        fun `with description stores value`() {
            val descriptionRes = 12345

            val legend = MapLegend(description = descriptionRes)

            legend.description shouldBe descriptionRes
        }

        @Test
        fun `with values stores list`() {
            val values = listOf(
                MapLegendValue(nameRes = 1, color = 0xFF0000),
                MapLegendValue(nameRes = 2, color = 0x00FF00),
                MapLegendValue(nameRes = 3, color = 0x0000FF)
            )

            val legend = MapLegend(valueList = values)

            legend.valueList shouldHaveSize 3
            legend.valueList shouldBe values
        }
    }

    @Nested
    @DisplayName("MapLegendValue")
    inner class MapLegendValueTests {

        @Test
        fun `stores name and color`() {
            val nameRes = 100
            val color = 0xFFABCDEF.toInt()

            val value = MapLegendValue(nameRes = nameRes, color = color)

            value.nameRes shouldBe nameRes
            value.color shouldBe color
        }

        @Test
        fun `equality works`() {
            val value1 = MapLegendValue(nameRes = 1, color = 0xFF0000)
            val value2 = MapLegendValue(nameRes = 1, color = 0xFF0000)

            value1 shouldBe value2
            value1.hashCode() shouldBe value2.hashCode()
        }

        @Test
        fun `destructuring works`() {
            val value = MapLegendValue(nameRes = 42, color = 0xDEADBEEF.toInt())

            val (nameRes, color) = value

            nameRes shouldBe 42
            color shouldBe 0xDEADBEEF.toInt()
        }
    }

    @Nested
    @DisplayName("Equality")
    inner class Equality {

        @Test
        fun `equality works`() {
            val values = listOf(MapLegendValue(nameRes = 1, color = 0xFF0000))
            val legend1 = MapLegend(description = 100, valueList = values)
            val legend2 = MapLegend(description = 100, valueList = values)

            legend1 shouldBe legend2
        }

        @Test
        fun `inequality for different descriptions`() {
            val legend1 = MapLegend(description = 100)
            val legend2 = MapLegend(description = 200)

            legend1 shouldNotBe legend2
        }
    }
}
