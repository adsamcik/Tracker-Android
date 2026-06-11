package com.adsamcik.tracker.shared.base.constant

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs

@DisplayName("GeometryConstants - circle and radian values")
class GeometryConstantsTest {

	@Nested
	@DisplayName("Degree constants")
	inner class DegreeConstants {
		@Test
		fun `half circle is 180 degrees`() {
			GeometryConstants.HALF_CIRCLE_IN_DEGREES shouldBe 180.0
		}

		@Test
		fun `full circle is 360 degrees`() {
			GeometryConstants.CIRCLE_IN_DEGREES shouldBe 360.0
		}

		@Test
		fun `full circle is double half circle`() {
			GeometryConstants.CIRCLE_IN_DEGREES shouldBe 2 * GeometryConstants.HALF_CIRCLE_IN_DEGREES
		}
	}

	@Nested
	@DisplayName("Radian constants")
	inner class RadianConstants {
		@Test
		fun `half circle in radians equals PI`() {
			GeometryConstants.HALF_CIRCLE_IN_RADIANS shouldBe PI
		}

		@Test
		fun `full circle in radians equals 2 PI`() {
			GeometryConstants.CIRCLE_IN_RADIANS shouldBe 2 * PI
		}

		@Test
		fun `full circle is double half circle in radians`() {
			GeometryConstants.CIRCLE_IN_RADIANS shouldBe 2 * GeometryConstants.HALF_CIRCLE_IN_RADIANS
		}

		@Test
		fun `PI approximation is accurate`() {
			abs(GeometryConstants.HALF_CIRCLE_IN_RADIANS - 3.14159265358979) shouldBeLessThan 1e-10
		}

		@Test
		fun `2 PI approximation is accurate`() {
			abs(GeometryConstants.CIRCLE_IN_RADIANS - 6.28318530717959) shouldBeLessThan 1e-10
		}
	}

	@Nested
	@DisplayName("Cross-unit relationships")
	inner class CrossUnit {
		@Test
		fun `degree to radian ratio is PI over 180`() {
			val ratio = GeometryConstants.HALF_CIRCLE_IN_RADIANS / GeometryConstants.HALF_CIRCLE_IN_DEGREES
			abs(ratio - PI / 180.0) shouldBeLessThan 1e-15
		}
	}
}
