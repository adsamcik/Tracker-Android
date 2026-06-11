package com.adsamcik.tracker.map.shared

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MapColor")
class MapColorTest {

    @Nested
    @DisplayName("Construction")
    inner class Construction {

        @Test
        fun `stores progress and color`() {
            val progress = 0.5
            val color = 0xFF0000FF.toInt()

            val mapColor = MapColor(progress = progress, color = color)

            mapColor.progress shouldBe progress
            mapColor.color shouldBe color
        }

        @Test
        fun `with zero progress is valid`() {
            val mapColor = MapColor(progress = 0.0, color = 0xFFFFFFFF.toInt())

            mapColor.progress shouldBe 0.0
        }

        @Test
        fun `with max progress is valid`() {
            val mapColor = MapColor(progress = 1.0, color = 0xFF000000.toInt())

            mapColor.progress shouldBe 1.0
        }
    }

    @Nested
    @DisplayName("Equality")
    inner class Equality {

        @Test
        fun `equality works correctly`() {
            val color1 = MapColor(progress = 0.5, color = 0xFFFF0000.toInt())
            val color2 = MapColor(progress = 0.5, color = 0xFFFF0000.toInt())

            color1 shouldBe color2
            color1.hashCode() shouldBe color2.hashCode()
        }

        @Test
        fun `inequality works for different progress`() {
            val color1 = MapColor(progress = 0.3, color = 0xFFFF0000.toInt())
            val color2 = MapColor(progress = 0.7, color = 0xFFFF0000.toInt())

            color1 shouldNotBe color2
        }

        @Test
        fun `inequality works for different colors`() {
            val color1 = MapColor(progress = 0.5, color = 0xFFFF0000.toInt())
            val color2 = MapColor(progress = 0.5, color = 0xFF00FF00.toInt())

            color1 shouldNotBe color2
        }
    }

    @Nested
    @DisplayName("Copy and Destructuring")
    inner class CopyAndDestructuring {

        @Test
        fun `copy creates modified instance`() {
            val original = MapColor(progress = 0.3, color = 0xFF0000FF.toInt())

            val modified = original.copy(progress = 0.8)

            modified.progress shouldBe 0.8
            modified.color shouldBe original.color
        }

        @Test
        fun `destructuring works`() {
            val mapColor = MapColor(progress = 0.75, color = 0xFFABCDEF.toInt())

            val (progress, color) = mapColor

            progress shouldBe 0.75
            color shouldBe 0xFFABCDEF.toInt()
        }
    }
}
