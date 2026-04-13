package com.adsamcik.tracker.shared.base.misc

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Double2 - 2D double vector")
class Double2Test {

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `default constructor creates zero vector`() {
			val v = Double2()
			v.x shouldBe 0.0
			v.y shouldBe 0.0
		}

		@Test
		fun `two-arg constructor sets x and y`() {
			val v = Double2(1.5, 2.5)
			v.x shouldBe 1.5
			v.y shouldBe 2.5
		}

		@Test
		fun `negative values are supported`() {
			val v = Double2(-1.5, -2.5)
			v.x shouldBe -1.5
			v.y shouldBe -2.5
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equal vectors are equal`() {
			Double2(1.0, 2.0) shouldBe Double2(1.0, 2.0)
		}

		@Test
		fun `different vectors are not equal`() {
			Double2(1.0, 2.0) shouldNotBe Double2(2.0, 1.0)
		}

		@Test
		fun `copy creates independent duplicate`() {
			val original = Double2(1.0, 2.0)
			val copy = original.copy()
			copy shouldBe original
			copy.x = 99.0
			original.x shouldBe 1.0
		}

		@Test
		fun `copy with override changes specified field`() {
			val v = Double2(1.0, 2.0).copy(x = 5.0)
			v.x shouldBe 5.0
			v.y shouldBe 2.0
		}

		@Test
		fun `mutable x can be updated`() {
			val v = Double2(1.0, 2.0)
			v.x = 10.0
			v.x shouldBe 10.0
		}

		@Test
		fun `mutable y can be updated`() {
			val v = Double2(1.0, 2.0)
			v.y = 10.0
			v.y shouldBe 10.0
		}

		@Test
		fun `toString contains both values`() {
			val s = Double2(1.5, 2.5).toString()
			s shouldBe "Double2(x=1.5, y=2.5)"
		}

		@Test
		fun `hashCode is consistent for equal objects`() {
			Double2(1.0, 2.0).hashCode() shouldBe Double2(1.0, 2.0).hashCode()
		}

		@Test
		fun `destructuring works`() {
			val (x, y) = Double2(3.0, 4.0)
			x shouldBe 3.0
			y shouldBe 4.0
		}
	}
}
