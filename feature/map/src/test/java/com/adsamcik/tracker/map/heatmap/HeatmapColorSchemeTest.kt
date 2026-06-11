package com.adsamcik.tracker.map.heatmap

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

/**
 * Unit tests for [HeatmapColorScheme].
 *
 * gradient256 uses pure bit manipulation — no Android deps needed.
 * fromArray uses [androidx.core.graphics.ColorUtils.blendARGB] which depends on
 * Android Color stubs, so blended values are not asserted directly.
 */
@DisplayName("HeatmapColorScheme")
class HeatmapColorSchemeTest {

    // ── Color extraction helpers (pure bit math, no android.graphics.Color) ──

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    private fun red(argb: Int): Int = (argb ushr 16) and 0xFF
    private fun green(argb: Int): Int = (argb ushr 8) and 0xFF
    private fun blue(argb: Int): Int = argb and 0xFF

    // ── gradient256 (viridis / inferno) ──────────────────────────────────────

    @Nested
    @DisplayName("gradient256 via viridis()")
    inner class Viridis {

        private val scheme = HeatmapColorScheme.viridis()

        @Test
        fun `produces exactly 256 colors`() {
            scheme.colors shouldHaveSize 256
        }

        @Test
        fun `first color matches first control point (68,1,84)`() {
            red(scheme.colors[0]) shouldBe 68
            green(scheme.colors[0]) shouldBe 1
            blue(scheme.colors[0]) shouldBe 84
        }

        @Test
        fun `last color matches last control point (253,231,37)`() {
            red(scheme.colors[255]) shouldBe 253
            green(scheme.colors[255]) shouldBe 231
            blue(scheme.colors[255]) shouldBe 37
        }

        @Test
        fun `all colors are fully opaque`() {
            scheme.colors.forEach { alpha(it) shouldBe 255 }
        }

        @Test
        fun `gradient is smooth — adjacent channel deltas le 10`() {
            for (i in 1 until scheme.colors.size) {
                val prev = scheme.colors[i - 1]
                val curr = scheme.colors[i]
                abs(red(curr) - red(prev)) shouldBeLessThanOrEqual 10
                abs(green(curr) - green(prev)) shouldBeLessThanOrEqual 10
                abs(blue(curr) - blue(prev)) shouldBeLessThanOrEqual 10
            }
        }

        @Test
        fun `channel values stay in 0-255 range`() {
            scheme.colors.forEach {
                red(it) shouldBeGreaterThanOrEqual 0
                red(it) shouldBeLessThanOrEqual 255
                green(it) shouldBeGreaterThanOrEqual 0
                green(it) shouldBeLessThanOrEqual 255
                blue(it) shouldBeGreaterThanOrEqual 0
                blue(it) shouldBeLessThanOrEqual 255
            }
        }
    }

    @Nested
    @DisplayName("gradient256 via inferno()")
    inner class Inferno {

        private val scheme = HeatmapColorScheme.inferno()

        @Test
        fun `produces exactly 256 colors`() {
            scheme.colors shouldHaveSize 256
        }

        @Test
        fun `first color matches first control point (0,0,4)`() {
            red(scheme.colors[0]) shouldBe 0
            green(scheme.colors[0]) shouldBe 0
            blue(scheme.colors[0]) shouldBe 4
        }

        @Test
        fun `last color matches last control point (252,255,164)`() {
            red(scheme.colors[255]) shouldBe 252
            green(scheme.colors[255]) shouldBe 255
            blue(scheme.colors[255]) shouldBe 164
        }

        @Test
        fun `all colors are fully opaque`() {
            scheme.colors.forEach { alpha(it) shouldBe 255 }
        }
    }

    // ── default / mixed_data ─────────────────────────────────────────────────

    @Nested
    @DisplayName("default scheme (mixed_data)")
    inner class DefaultScheme {

        @Test
        fun `mixed_data has 1025 entries`() {
            HeatmapColorScheme.mixed_data.size shouldBe 1025
        }

        @Test
        fun `default wraps mixed_data`() {
            HeatmapColorScheme.default.colors shouldBe HeatmapColorScheme.mixed_data
        }
    }

    // ── fromArray(List<Int>, transitionSteps) ────────────────────────────────

    @Nested
    @DisplayName("fromArray with transition steps")
    inner class FromArrayTransitionSteps {

        @Test
        fun `output size follows formula n + (n-1) x steps`() {
            val colors = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
            val steps = 4
            val scheme = HeatmapColorScheme.fromArray(colors, steps)
            // 3 + (3-1)*4 = 11
            scheme.colors.size shouldBe 11
        }

        @Test
        fun `first color is directly assigned`() {
            val first = 0xFFFF0000.toInt()
            val scheme = HeatmapColorScheme.fromArray(listOf(first, 0xFF00FF00.toInt()), 3)
            scheme.colors.first() shouldBe first
        }

        @Test
        fun `last color is directly assigned`() {
            val last = 0xFF0000FF.toInt()
            val scheme = HeatmapColorScheme.fromArray(listOf(0xFFFF0000.toInt(), last), 3)
            scheme.colors.last() shouldBe last
        }

        @Test
        fun `zero transition steps yields exact input colors`() {
            val colors = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
            val scheme = HeatmapColorScheme.fromArray(colors, 0)
            scheme.colors.size shouldBe 3
            scheme.colors[0] shouldBe colors[0]
            scheme.colors[2] shouldBe colors[2]
        }
    }

    // ── fromArray(Collection<Pair<Double,Int>>, totalSteps) ──────────────────

    @Nested
    @DisplayName("fromArray with position pairs")
    inner class FromArrayPositionPairs {

        @Test
        fun `output has exactly totalSteps entries`() {
            val pairs = listOf(0.0 to 0xFFFF0000.toInt(), 1.0 to 0xFF0000FF.toInt())
            val scheme = HeatmapColorScheme.fromArray(pairs, 100)
            scheme.colors.size shouldBe 100
        }

        @Test
        fun `last color is filled via repeat for full-range input`() {
            val lastColor = 0xFF00FF00.toInt()
            val pairs = listOf(0.0 to 0xFFFF0000.toInt(), 1.0 to lastColor)
            val scheme = HeatmapColorScheme.fromArray(pairs, 50)
            // The last element is filled by repeat() with next.second = lastColor
            scheme.colors.last() shouldBe lastColor
        }

        @Test
        fun `throws for fewer than 2 elements`() {
            assertThrows<IllegalArgumentException> {
                HeatmapColorScheme.fromArray(listOf(0.0 to 0xFFFF0000.toInt()), 10)
            }
        }

        @Test
        fun `three-stop gradient has correct total size`() {
            val pairs = listOf(
                0.0 to 0xFFFF0000.toInt(),
                0.5 to 0xFF00FF00.toInt(),
                1.0 to 0xFF0000FF.toInt()
            )
            val scheme = HeatmapColorScheme.fromArray(pairs, 200)
            scheme.colors.size shouldBe 200
        }
    }

    // ── Equality ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("equality")
    inner class Equality {

        @Test
        fun `two viridis instances are equal (contentEquals)`() {
            HeatmapColorScheme.viridis() shouldBe HeatmapColorScheme.viridis()
        }

        @Test
        fun `viridis and inferno are not equal`() {
            HeatmapColorScheme.viridis() shouldNotBe HeatmapColorScheme.inferno()
        }

        @Test
        fun `hashCode uses contentHashCode`() {
            HeatmapColorScheme.viridis().hashCode() shouldBe HeatmapColorScheme.viridis().hashCode()
        }
    }
}
