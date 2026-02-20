package com.adsamcik.tracker.shared.utils.style.color

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ColorFunctions")
class ColorFunctionsTest {

	/** Create a packed ARGB int without android.graphics.Color dependency. */
	private fun packArgb(a: Int, r: Int, g: Int, b: Int): Int =
		(a shl 24) or (r shl 16) or (g shl 8) or b

	private fun packRgb(r: Int, g: Int, b: Int): Int = packArgb(255, r, g, b)

	@Nested
	@DisplayName("brightenComponent")
	inner class BrightenComponentTest {

		@Test
		fun `adds value to component`() {
			brightenComponent(100, 50) shouldBe 150
		}

		@Test
		fun `clamps to 255 on overflow`() {
			brightenComponent(200, 100) shouldBe 255
		}

		@Test
		fun `clamps to 0 on underflow`() {
			brightenComponent(0, -50) shouldBe 0
		}

		@Test
		fun `zero change returns same value`() {
			brightenComponent(128, 0) shouldBe 128
		}

		@Test
		fun `exact maximum is preserved`() {
			brightenComponent(255, 0) shouldBe 255
		}

		@Test
		fun `exact minimum is preserved`() {
			brightenComponent(0, 0) shouldBe 0
		}

		@Test
		fun `negative value darkens component`() {
			brightenComponent(100, -30) shouldBe 70
		}

		@Test
		fun `max positive value on max component clamps`() {
			brightenComponent(255, 255) shouldBe 255
		}

		@Test
		fun `large negative on zero clamps to zero`() {
			brightenComponent(0, -255) shouldBe 0
		}

		@Test
		fun `just below overflow boundary`() {
			brightenComponent(200, 55) shouldBe 255
		}

		@Test
		fun `just above underflow boundary`() {
			brightenComponent(50, -50) shouldBe 0
		}
	}

	@Nested
	@DisplayName("validateLab")
	inner class ValidateLabTest {

		@Test
		fun `black is valid`() {
			ColorFunctions.validateLab(doubleArrayOf(0.0, 0.0, 0.0)) shouldBe true
		}

		@Test
		fun `white is valid`() {
			ColorFunctions.validateLab(doubleArrayOf(100.0, 0.0, 0.0)) shouldBe true
		}

		@Test
		fun `neutral gray is valid`() {
			ColorFunctions.validateLab(doubleArrayOf(50.0, 0.0, 0.0)) shouldBe true
		}

		@Test
		fun `extreme chromatic value is invalid`() {
			// Very high a* and b* push out of sRGB gamut
			ColorFunctions.validateLab(doubleArrayOf(50.0, 200.0, 200.0)) shouldBe false
		}

		@Test
		fun `another out of gamut value is invalid`() {
			ColorFunctions.validateLab(doubleArrayOf(10.0, -150.0, 150.0)) shouldBe false
		}

		@Test
		fun `moderate chromatic value is valid`() {
			// Moderate a* and b* should still be in gamut
			ColorFunctions.validateLab(doubleArrayOf(50.0, 20.0, 10.0)) shouldBe true
		}

		@Test
		fun `near-black with slight chroma is valid`() {
			ColorFunctions.validateLab(doubleArrayOf(5.0, 5.0, -5.0)) shouldBe true
		}

		@Test
		fun `near-white with slight chroma is valid`() {
			ColorFunctions.validateLab(doubleArrayOf(95.0, -5.0, 5.0)) shouldBe true
		}

		@Test
		fun `negative L with extreme chroma is invalid`() {
			// L < 0 is unusual; lab-to-xyz math should produce out-of-range
			ColorFunctions.validateLab(doubleArrayOf(-10.0, 100.0, 100.0)) shouldBe false
		}
	}

	@Nested
	@DisplayName("distance (Manhattan RGB)")
	inner class DistanceTest {

		@Test
		fun `identical colors have zero distance`() {
			val color = packRgb(100, 150, 200)
			ColorFunctions.distance(color, color) shouldBe 0
		}

		@Test
		fun `black to white distance is maximum`() {
			val black = packRgb(0, 0, 0)
			val white = packRgb(255, 255, 255)
			ColorFunctions.distance(black, white) shouldBe 765 // 255 * 3
		}

		@Test
		fun `distance is symmetric`() {
			val colorA = packRgb(255, 0, 0)
			val colorB = packRgb(0, 0, 255)
			ColorFunctions.distance(colorA, colorB) shouldBe ColorFunctions.distance(colorB, colorA)
		}

		@Test
		fun `red to blue distance`() {
			val red = packRgb(255, 0, 0)
			val blue = packRgb(0, 0, 255)
			// |0-255| + |0-0| + |255-0| = 510
			ColorFunctions.distance(red, blue) shouldBe 510
		}

		@Test
		fun `single channel difference`() {
			val colorA = packRgb(100, 50, 50)
			val colorB = packRgb(150, 50, 50)
			ColorFunctions.distance(colorA, colorB) shouldBe 50
		}

		@Test
		fun `distance is non-negative`() {
			val colorA = packRgb(200, 100, 50)
			val colorB = packRgb(50, 200, 100)
			ColorFunctions.distance(colorA, colorB) shouldBeGreaterThanOrEqual 0
		}

		@Test
		fun `maximum possible distance`() {
			val colorA = packRgb(0, 0, 0)
			val colorB = packRgb(255, 255, 255)
			ColorFunctions.distance(colorA, colorB) shouldBeLessThanOrEqual 765
		}

		@Test
		fun `distance satisfies triangle inequality`() {
			val a = packRgb(0, 0, 0)
			val b = packRgb(128, 128, 128)
			val c = packRgb(255, 255, 255)
			val dAC = ColorFunctions.distance(a, c)
			val dAB = ColorFunctions.distance(a, b)
			val dBC = ColorFunctions.distance(b, c)
			dAC shouldBeLessThanOrEqual (dAB + dBC)
		}
	}

	@Nested
	@DisplayName("LIGHTNESS_PER_LEVEL constant")
	inner class LightnessPerLevel {

		@Test
		fun `lightness per level is 17`() {
			ColorFunctions.LIGHTNESS_PER_LEVEL shouldBe 17
		}
	}
}
