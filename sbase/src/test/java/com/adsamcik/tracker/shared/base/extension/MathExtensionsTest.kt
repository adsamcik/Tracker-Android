package com.adsamcik.tracker.shared.base.extension

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.PI

class MathExtensionsTest {

	@Nested
	@DisplayName("toRadians")
	inner class ToRadians {
		@Test
		fun `0 degrees is 0 radians`() {
			0.0.toRadians() shouldBe 0.0
		}

		@Test
		fun `180 degrees is PI radians`() {
			180.0.toRadians() shouldBe PI
		}

		@Test
		fun `90 degrees is half PI`() {
			90.0.toRadians() shouldBe PI / 2
		}

		@Test
		fun `360 degrees is 2 PI`() {
			360.0.toRadians() shouldBe 2 * PI
		}

		@Test
		fun `negative degrees converts correctly`() {
			(-90.0).toRadians() shouldBe -(PI / 2)
		}
	}

	@Nested
	@DisplayName("toDegrees")
	inner class ToDegrees {
		@Test
		fun `0 radians is 0 degrees`() {
			0.0.toDegrees() shouldBe 0.0
		}

		@Test
		fun `PI radians is 180 degrees`() {
			PI.toDegrees() shouldBe 180.0
		}

		@Test
		fun `half PI is 90 degrees`() {
			(PI / 2).toDegrees() shouldBe 90.0
		}

		@Test
		fun `negative radians converts correctly`() {
			(-PI).toDegrees() shouldBe -180.0
		}

		@Test
		fun `toRadians and toDegrees are inverse operations`() {
			val original = 45.0
			original.toRadians().toDegrees() shouldBe original
		}
	}

	@Nested
	@DisplayName("round")
	inner class Round {
		@Test
		fun `round to 0 decimals`() {
			3.14159.round(0) shouldBe 3.0
		}

		@Test
		fun `round to 2 decimals`() {
			3.14159.round(2) shouldBe 3.14
		}

		@Test
		fun `round to 3 decimals`() {
			3.14159.round(3) shouldBe 3.142
		}

		@Test
		fun `round up at boundary`() {
			2.555.round(2) shouldBe 2.56
		}

		@Test
		fun `round negative value`() {
			(-2.567).round(1) shouldBe -2.6
		}

		@Test
		fun `round exact value unchanged`() {
			1.5.round(1) shouldBe 1.5
		}

		@Test
		fun `round zero`() {
			0.0.round(5) shouldBe 0.0
		}
	}

	@Nested
	@DisplayName("normalize")
	inner class Normalize {
		@Test
		fun `start of range normalizes to 0`() {
			5.0.normalize(5.0..10.0) shouldBe 0.0
		}

		@Test
		fun `end of range normalizes to 1`() {
			10.0.normalize(5.0..10.0) shouldBe 1.0
		}

		@Test
		fun `midpoint normalizes to 0 point 5`() {
			7.5.normalize(5.0..10.0) shouldBe 0.5
		}

		@Test
		fun `below range normalizes to negative`() {
			0.0.normalize(5.0..10.0) shouldBe -1.0
		}

		@Test
		fun `above range normalizes to greater than 1`() {
			15.0.normalize(5.0..10.0) shouldBe 2.0
		}
	}

	@Nested
	@DisplayName("rescale Double with original and new range")
	inner class RescaleDoubleOriginalToNew {
		@Test
		fun `rescale midpoint`() {
			5.0.rescale(0.0..10.0, 0.0..100.0) shouldBe 50.0
		}

		@Test
		fun `rescale start maps to new start`() {
			0.0.rescale(0.0..10.0, 20.0..30.0) shouldBe 20.0
		}

		@Test
		fun `rescale end maps to new end`() {
			10.0.rescale(0.0..10.0, 20.0..30.0) shouldBe 30.0
		}

		@Test
		fun `rescale quarter maps proportionally`() {
			25.0.rescale(0.0..100.0, 0.0..1.0) shouldBe 0.25
		}
	}

	@Nested
	@DisplayName("rescale Double from 0-1 to new range")
	inner class RescaleDoubleNormalized {
		@Test
		fun `0 maps to range start`() {
			0.0.rescale(10.0..20.0) shouldBe 10.0
		}

		@Test
		fun `1 maps to range end`() {
			1.0.rescale(10.0..20.0) shouldBe 20.0
		}

		@Test
		fun `0 point 5 maps to midpoint`() {
			0.5.rescale(10.0..20.0) shouldBe 15.0
		}

		@Test
		fun `value above 1 extrapolates`() {
			2.0.rescale(0.0..10.0) shouldBe 20.0
		}
	}

	@Nested
	@DisplayName("rescale Int")
	inner class RescaleInt {
		@Test
		fun `rescale midpoint`() {
			5.rescale(0..10, 0..100) shouldBe 50
		}

		@Test
		fun `rescale start`() {
			0.rescale(0..10, 20..30) shouldBe 20
		}

		@Test
		fun `rescale end`() {
			10.rescale(0..10, 20..30) shouldBe 30
		}

		@Test
		fun `rescale rounds to nearest int`() {
			1.rescale(0..3, 0..10) shouldBe 3 // 10/3 = 3.33 rounds to 3
		}
	}

	@Nested
	@DisplayName("coerceIn LongRange")
	inner class CoerceInLongRange {
		@Test
		fun `range fully inside stays unchanged`() {
			(5L..10L).coerceIn(0L..20L) shouldBe (5L..10L)
		}

		@Test
		fun `start below is clamped`() {
			(-5L..10L).coerceIn(0L..20L) shouldBe (0L..10L)
		}

		@Test
		fun `end above is clamped`() {
			(5L..25L).coerceIn(0L..20L) shouldBe (5L..20L)
		}

		@Test
		fun `both bounds outside are clamped`() {
			(-10L..30L).coerceIn(0L..20L) shouldBe (0L..20L)
		}

		@Test
		fun `exact match stays unchanged`() {
			(0L..20L).coerceIn(0L..20L) shouldBe (0L..20L)
		}
	}

	@Nested
	@DisplayName("additiveInverse")
	inner class AdditiveInverse {
		@Test
		fun `4 in 2 to 5 gives 3`() {
			4.0.additiveInverse(2.0..5.0) shouldBe 3.0
		}

		@Test
		fun `start of range gives end`() {
			2.0.additiveInverse(2.0..5.0) shouldBe 5.0
		}

		@Test
		fun `end of range gives start`() {
			5.0.additiveInverse(2.0..5.0) shouldBe 2.0
		}

		@Test
		fun `midpoint stays at midpoint`() {
			3.5.additiveInverse(2.0..5.0) shouldBe 3.5
		}
	}

	@Nested
	@DisplayName("isPowerOfTwo")
	inner class IsPowerOfTwo {
		@Test
		fun `1 is power of two`() {
			1.isPowerOfTwo() shouldBe true
		}

		@Test
		fun `2 is power of two`() {
			2.isPowerOfTwo() shouldBe true
		}

		@Test
		fun `4 is power of two`() {
			4.isPowerOfTwo() shouldBe true
		}

		@Test
		fun `8 is power of two`() {
			8.isPowerOfTwo() shouldBe true
		}

		@Test
		fun `1024 is power of two`() {
			1024.isPowerOfTwo() shouldBe true
		}

		@Test
		fun `0 is not power of two`() {
			0.isPowerOfTwo() shouldBe false
		}

		@Test
		fun `3 is not power of two`() {
			3.isPowerOfTwo() shouldBe false
		}

		@Test
		fun `6 is not power of two`() {
			6.isPowerOfTwo() shouldBe false
		}

		@Test
		fun `negative is not power of two`() {
			(-4).isPowerOfTwo() shouldBe false
		}
	}

	@Nested
	@DisplayName("lerp")
	inner class Lerp {
		@Test
		fun `fraction 0 returns from`() {
			MathExtensions.lerp(0.0, 10.0, 20.0) shouldBe 10.0
		}

		@Test
		fun `fraction 1 returns to`() {
			MathExtensions.lerp(1.0, 10.0, 20.0) shouldBe 20.0
		}

		@Test
		fun `fraction 0 point 5 returns midpoint`() {
			MathExtensions.lerp(0.5, 10.0, 20.0) shouldBe 15.0
		}

		@Test
		fun `fraction above 1 extrapolates beyond to`() {
			MathExtensions.lerp(2.0, 10.0, 20.0) shouldBe 30.0
		}

		@Test
		fun `fraction below 0 extrapolates before from`() {
			MathExtensions.lerp(-1.0, 10.0, 20.0) shouldBe 0.0
		}

		@Test
		fun `equal from and to always returns that value`() {
			MathExtensions.lerp(0.5, 5.0, 5.0) shouldBe 5.0
		}

		@Test
		fun `reverse direction works`() {
			MathExtensions.lerp(0.5, 20.0, 10.0) shouldBe 15.0
		}
	}
}
