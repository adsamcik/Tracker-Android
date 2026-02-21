package com.adsamcik.tracker.shared.utils.style.color

import android.graphics.Color
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import org.robolectric.annotation.Config

/**
 * Tests for [ColorGenerator].
 *
 * Uses Robolectric because generation methods depend on [android.graphics.Color.HSVToColor].
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class ColorGeneratorTest {

	// --- generateWithGolden(startHue, count) ---

	@Test
	fun `generateWithGolden returns empty list for count 0`() {
		val colors = ColorGenerator.generateWithGolden(0.5, 0)
		colors.shouldBeEmpty()
	}

	@Test
	fun `generateWithGolden returns single color for count 1`() {
		val colors = ColorGenerator.generateWithGolden(0.5, 1)
		colors shouldHaveSize 1
	}

	@Test
	fun `generateWithGolden returns correct count`() {
		val colors = ColorGenerator.generateWithGolden(0.5, 10)
		colors shouldHaveSize 10
	}

	@Test
	fun `generateWithGolden produces valid ARGB colors`() {
		val colors = ColorGenerator.generateWithGolden(0.5, 5)
		colors.forEach { color ->
			Color.alpha(color) shouldBe 255
			Color.red(color) shouldBeGreaterThanOrEqual 0
			Color.red(color) shouldBeLessThanOrEqual 255
			Color.green(color) shouldBeGreaterThanOrEqual 0
			Color.green(color) shouldBeLessThanOrEqual 255
			Color.blue(color) shouldBeGreaterThanOrEqual 0
			Color.blue(color) shouldBeLessThanOrEqual 255
		}
	}

	@Test
	fun `generateWithGolden rejects startHue of 0`() {
		shouldThrow<IllegalArgumentException> {
			ColorGenerator.generateWithGolden(0.0, 5)
		}
	}

	@Test
	fun `generateWithGolden rejects startHue of 1`() {
		shouldThrow<IllegalArgumentException> {
			ColorGenerator.generateWithGolden(1.0, 5)
		}
	}

	@Test
	fun `generateWithGolden rejects negative startHue`() {
		shouldThrow<IllegalArgumentException> {
			ColorGenerator.generateWithGolden(-0.5, 5)
		}
	}

	@Test
	fun `generateWithGolden rejects startHue greater than 1`() {
		shouldThrow<IllegalArgumentException> {
			ColorGenerator.generateWithGolden(1.5, 5)
		}
	}

	@Test
	fun `generateWithGolden produces distinct colors`() {
		val colors = ColorGenerator.generateWithGolden(0.3, 8)
		val uniqueColors = colors.toSet()
		uniqueColors shouldHaveSize colors.size
	}

	@Test
	fun `generateWithGolden random overload returns correct count`() {
		val colors = ColorGenerator.generateWithGolden(5)
		colors shouldHaveSize 5
	}

	// --- generateDistinctColors ---

	@Test
	fun `generateDistinctColors returns correct count`() {
		val colors = ColorGenerator.generateDistinctColors(5, 120f)
		colors shouldHaveSize 5
	}

	@Test
	fun `generateDistinctColors returns empty for count 0`() {
		val colors = ColorGenerator.generateDistinctColors(0, 0f)
		colors.shouldBeEmpty()
	}

	@Test
	fun `generateDistinctColors returns single color`() {
		val colors = ColorGenerator.generateDistinctColors(1, 200f)
		colors shouldHaveSize 1
	}

	@Test
	fun `generateDistinctColors produces unique colors`() {
		val colors = ColorGenerator.generateDistinctColors(6, 42f)
		val uniqueColors = colors.toSet()
		uniqueColors shouldHaveSize colors.size
	}

	@Test
	fun `generateDistinctColors is deterministic for same seed`() {
		val colors1 = ColorGenerator.generateDistinctColors(5, 99f)
		val colors2 = ColorGenerator.generateDistinctColors(5, 99f)
		colors1 shouldBe colors2
	}

	@Test
	fun `generateDistinctColors with different seeds differ`() {
		val colors1 = ColorGenerator.generateDistinctColors(5, 10f)
		val colors2 = ColorGenerator.generateDistinctColors(5, 200f)
		(colors1 == colors2) shouldBe false
	}

	// --- rgbToLab ---

	@Test
	fun `rgbToLab of black returns near-zero LAB`() {
		val lab = ColorGenerator.rgbToLab(Color.BLACK)
		lab[0].toDouble() shouldBeLessThan 1.0
		lab[1].toDouble() shouldBeLessThan 1.0
		lab[2].toDouble() shouldBeLessThan 1.0
	}

	@Test
	fun `rgbToLab of white returns L near 100`() {
		val lab = ColorGenerator.rgbToLab(Color.WHITE)
		lab[0].toDouble() shouldBeGreaterThan 99.0
		lab[0].toDouble() shouldBeLessThan 101.0
	}

	@Test
	fun `rgbToLab L is between 0 and 100 for arbitrary color`() {
		val color = Color.rgb(128, 64, 192)
		val lab = ColorGenerator.rgbToLab(color)
		lab[0].toDouble() shouldBeGreaterThan 0.0
		lab[0].toDouble() shouldBeLessThan 100.0
	}

	@Test
	fun `rgbToLab of red has positive a component`() {
		val lab = ColorGenerator.rgbToLab(Color.RED)
		lab[1].toDouble() shouldBeGreaterThan 0.0
	}

	@Test
	fun `rgbToLab of green has negative a component`() {
		val lab = ColorGenerator.rgbToLab(Color.GREEN)
		lab[1].toDouble() shouldBeLessThan 0.0
	}

	@Test
	fun `rgbToLab output has three components`() {
		val lab = ColorGenerator.rgbToLab(Color.BLUE)
		lab.size shouldBe 3
	}

	// --- generatePalette ---

	@Test
	fun `generatePalette returns correct count`() {
		val colors = ColorGenerator.generatePalette(4)
		colors shouldHaveSize 4
	}

	@Test
	fun `generatePalette returns single color`() {
		val colors = ColorGenerator.generatePalette(1)
		colors shouldHaveSize 1
	}

	@Test
	fun `generatePalette produces valid ARGB colors`() {
		val colors = ColorGenerator.generatePalette(3)
		colors.forEach { color ->
			Color.alpha(color) shouldBe 255
		}
	}
}
