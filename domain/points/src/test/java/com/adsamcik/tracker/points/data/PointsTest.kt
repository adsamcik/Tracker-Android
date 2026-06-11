package com.adsamcik.tracker.points.data

import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PointsTest {

	@Nested
	inner class Construction {
		@Test
		fun `zero value creates valid Points`() {
			val points = Points(0.0)
			points.value shouldBeExactly 0.0
		}

		@Test
		fun `positive value is stored correctly`() {
			val points = Points(42.5)
			points.value shouldBeExactly 42.5
		}

		@Test
		fun `negative value is stored correctly`() {
			val points = Points(-10.0)
			points.value shouldBeExactly -10.0
		}

		@Test
		fun `very large value is stored correctly`() {
			val points = Points(Double.MAX_VALUE)
			points.value shouldBeExactly Double.MAX_VALUE
		}

		@Test
		fun `very small positive value is stored correctly`() {
			val points = Points(Double.MIN_VALUE)
			points.value shouldBeExactly Double.MIN_VALUE
		}
	}

	@Nested
	inner class Equality {
		@Test
		fun `points with same value are equal`() {
			Points(10.0) shouldBe Points(10.0)
		}

		@Test
		fun `points with different values are not equal`() {
			Points(10.0) shouldNotBe Points(20.0)
		}

		@Test
		fun `copy preserves value`() {
			val original = Points(15.0)
			val copy = original.copy()
			copy shouldBe original
		}

		@Test
		fun `copy with new value changes value`() {
			val original = Points(15.0)
			val modified = original.copy(value = 30.0)
			modified.value shouldBeExactly 30.0
			modified shouldNotBe original
		}
	}

	@Nested
	inner class HashCodeAndToString {
		@Test
		fun `equal points have same hashCode`() {
			Points(5.0).hashCode() shouldBe Points(5.0).hashCode()
		}

		@Test
		fun `toString contains value`() {
			val points = Points(99.9)
			points.toString() shouldBe "Points(value=99.9)"
		}
	}
}
