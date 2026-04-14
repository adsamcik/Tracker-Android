package com.adsamcik.tracker.shared.base.constant

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

@DisplayName("LabConstants - CIE LAB color space constants")
class LabConstantsTest {

	@Nested
	@DisplayName("Brightness constant")
	inner class Brightness {
		@Test
		fun `Kn is 18`() {
			LabConstants.Kn shouldBe 18
		}
	}

	@Nested
	@DisplayName("D65 standard illuminant")
	inner class D65Illuminant {
		@Test
		fun `Xn tristimulus value`() {
			LabConstants.Xn shouldBe 0.950470
		}

		@Test
		fun `Yn tristimulus value is 1`() {
			LabConstants.Yn shouldBe 1.0
		}

		@Test
		fun `Zn tristimulus value`() {
			LabConstants.Zn shouldBe 1.088830
		}
	}

	@Nested
	@DisplayName("Derived constants mathematical relationships")
	inner class DerivedConstants {
		@Test
		fun `t0 equals 4 divided by 29`() {
			abs(LabConstants.t0 - 4.0 / 29.0) shouldBeLessThan 1e-6
		}

		@Test
		fun `t1 equals 6 divided by 29`() {
			abs(LabConstants.t1 - 6.0 / 29.0) shouldBeLessThan 1e-6
		}

		@Test
		fun `t2 equals 3 times t1 squared`() {
			val expected = 3.0 * LabConstants.t1 * LabConstants.t1
			abs(LabConstants.t2 - expected) shouldBeLessThan 1e-6
		}

		@Test
		fun `t3 equals t1 cubed`() {
			val expected = LabConstants.t1 * LabConstants.t1 * LabConstants.t1
			abs(LabConstants.t3 - expected) shouldBeLessThan 1e-6
		}
	}
}
