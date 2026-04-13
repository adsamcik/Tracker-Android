package com.adsamcik.tracker.shared.base.misc

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Int2 - 2D integer vector")
class Int2Test {

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `default constructor creates zero vector`() {
			val v = Int2()
			v.x shouldBe 0
			v.y shouldBe 0
		}

		@Test
		fun `two-arg constructor sets x and y`() {
			val v = Int2(3, 7)
			v.x shouldBe 3
			v.y shouldBe 7
		}

		@Test
		fun `single-arg constructor sets both components`() {
			val v = Int2(5)
			v.x shouldBe 5
			v.y shouldBe 5
		}

		@Test
		fun `copy constructor creates independent copy`() {
			val original = Int2(10, 20)
			val copy = Int2(original)
			copy.x shouldBe 10
			copy.y shouldBe 20
			copy.x = 99
			original.x shouldBe 10
		}

		@Test
		fun `negative values are supported`() {
			val v = Int2(-3, -7)
			v.x shouldBe -3
			v.y shouldBe -7
		}
	}

	@Nested
	@DisplayName("Mutating operators")
	inner class MutatingOperators {
		@Test
		fun `plus Int2 adds component-wise`() {
			val v = Int2(1, 2)
			v + Int2(3, 4)
			v.x shouldBe 4
			v.y shouldBe 6
		}

		@Test
		fun `plus scalar adds to both components`() {
			val v = Int2(1, 2)
			v + 10
			v.x shouldBe 11
			v.y shouldBe 12
		}

		@Test
		fun `minus Int2 subtracts component-wise`() {
			val v = Int2(10, 20)
			v - Int2(3, 7)
			v.x shouldBe 7
			v.y shouldBe 13
		}

		@Test
		fun `minus scalar subtracts from both components`() {
			val v = Int2(10, 20)
			v - 5
			v.x shouldBe 5
			v.y shouldBe 15
		}

		@Test
		fun `times Int2 multiplies component-wise`() {
			val v = Int2(3, 4)
			v * Int2(2, 5)
			v.x shouldBe 6
			v.y shouldBe 20
		}

		@Test
		fun `times scalar multiplies both components`() {
			val v = Int2(3, 4)
			v * 3
			v.x shouldBe 9
			v.y shouldBe 12
		}

		@Test
		fun `div Int2 divides component-wise`() {
			val v = Int2(10, 20)
			v / Int2(2, 5)
			v.x shouldBe 5
			v.y shouldBe 4
		}

		@Test
		fun `div scalar divides both components`() {
			val v = Int2(10, 21)
			v / 5
			v.x shouldBe 2
			v.y shouldBe 4
		}

		@Test
		fun `rem Int2 computes modulo component-wise`() {
			val v = Int2(10, 13)
			v % Int2(3, 5)
			v.x shouldBe 1
			v.y shouldBe 3
		}

		@Test
		fun `rem scalar computes modulo for both components`() {
			val v = Int2(10, 13)
			v % 4
			v.x shouldBe 2
			v.y shouldBe 1
		}
	}

	@Nested
	@DisplayName("Companion static operations (non-mutating)")
	inner class CompanionOps {
		@Test
		fun `add Int2 returns new vector without mutating inputs`() {
			val a = Int2(1, 2)
			val b = Int2(3, 4)
			val result = Int2.add(a, b)
			result shouldBe Int2(4, 6)
			a shouldBe Int2(1, 2)
		}

		@Test
		fun `add scalar returns new vector`() {
			val result = Int2.add(Int2(1, 2), 10)
			result shouldBe Int2(11, 12)
		}

		@Test
		fun `sub Int2 returns new vector`() {
			val result = Int2.sub(Int2(10, 20), Int2(3, 7))
			result shouldBe Int2(7, 13)
		}

		@Test
		fun `sub scalar returns new vector`() {
			val result = Int2.sub(Int2(10, 20), 5)
			result shouldBe Int2(5, 15)
		}

		@Test
		fun `mul Int2 returns new vector`() {
			val result = Int2.mul(Int2(3, 4), Int2(2, 5))
			result shouldBe Int2(6, 20)
		}

		@Test
		fun `mul scalar returns new vector`() {
			val result = Int2.mul(Int2(3, 4), 3)
			result shouldBe Int2(9, 12)
		}

		@Test
		fun `div Int2 returns new vector`() {
			val result = Int2.div(Int2(10, 20), Int2(2, 5))
			result shouldBe Int2(5, 4)
		}

		@Test
		fun `div scalar returns new vector`() {
			val result = Int2.div(Int2(10, 21), 5)
			result shouldBe Int2(2, 4)
		}

		@Test
		fun `mod Int2 returns new vector`() {
			val result = Int2.mod(Int2(10, 13), Int2(3, 5))
			result shouldBe Int2(1, 3)
		}

		@Test
		fun `mod scalar returns new vector`() {
			val result = Int2.mod(Int2(10, 13), 4)
			result shouldBe Int2(2, 1)
		}

		@Test
		fun `companion dotProduct computes correctly`() {
			Int2.dotProduct(Int2(2, 3), Int2(4, 5)) shouldBe 23
		}
	}

	@Nested
	@DisplayName("Dot product")
	inner class DotProduct {
		@Test
		fun `dot product of orthogonal vectors is zero`() {
			Int2(1, 0).dotProduct(Int2(0, 1)) shouldBe 0
		}

		@Test
		fun `dot product of parallel vectors is product of magnitudes squared`() {
			Int2(3, 4).dotProduct(Int2(3, 4)) shouldBe 25
		}

		@Test
		fun `dot product with zero vector is zero`() {
			Int2(5, 10).dotProduct(Int2(0, 0)) shouldBe 0
		}

		@Test
		fun `dot product is commutative`() {
			val a = Int2(2, 3)
			val b = Int2(4, 5)
			a.dotProduct(b) shouldBe b.dotProduct(a)
		}

		@Test
		fun `dot product with negative components`() {
			Int2(-2, 3).dotProduct(Int2(4, -5)) shouldBe -23
		}
	}

	@Nested
	@DisplayName("Negate")
	inner class Negate {
		@Test
		fun `negate flips signs`() {
			val v = Int2(3, -7)
			v.negate()
			v.x shouldBe -3
			v.y shouldBe 7
		}

		@Test
		fun `double negate returns to original`() {
			val v = Int2(5, 10)
			v.negate()
			v.negate()
			v shouldBe Int2(5, 10)
		}

		@Test
		fun `negate zero stays zero`() {
			val v = Int2(0, 0)
			v.negate()
			v shouldBe Int2(0, 0)
		}
	}

	@Nested
	@DisplayName("Index access")
	inner class IndexAccess {
		@Test
		fun `get index 0 returns x`() {
			Int2(3, 7)[0] shouldBe 3
		}

		@Test
		fun `get index 1 returns y`() {
			Int2(3, 7)[1] shouldBe 7
		}

		@Test
		fun `get invalid index throws IndexOutOfBoundsException`() {
			assertThrows<IndexOutOfBoundsException> { Int2(3, 7)[2] }
		}

		@Test
		fun `get negative index throws IndexOutOfBoundsException`() {
			assertThrows<IndexOutOfBoundsException> { Int2(3, 7)[-1] }
		}

		@Test
		fun `setAt index 0 sets x`() {
			val v = Int2()
			v.setAt(0, 42)
			v.x shouldBe 42
		}

		@Test
		fun `setAt index 1 sets y`() {
			val v = Int2()
			v.setAt(1, 42)
			v.y shouldBe 42
		}

		@Test
		fun `setAt invalid index throws`() {
			assertThrows<IndexOutOfBoundsException> { Int2().setAt(2, 0) }
		}

		@Test
		fun `addAt index 0 adds to x`() {
			val v = Int2(10, 20)
			v.addAt(0, 5)
			v.x shouldBe 15
			v.y shouldBe 20
		}

		@Test
		fun `addAt index 1 adds to y`() {
			val v = Int2(10, 20)
			v.addAt(1, 5)
			v.x shouldBe 10
			v.y shouldBe 25
		}

		@Test
		fun `addAt invalid index throws`() {
			assertThrows<IndexOutOfBoundsException> { Int2().addAt(2, 0) }
		}
	}

	@Nested
	@DisplayName("Utility methods")
	inner class Utility {
		@Test
		fun `length is always 2`() {
			Int2(1, 2).length shouldBe 2
		}

		@Test
		fun `elementSum sums components`() {
			Int2(3, 7).elementSum shouldBe 10
		}

		@Test
		fun `elementSum with negative values`() {
			Int2(5, -3).elementSum shouldBe 2
		}

		@Test
		fun `set copies values from another Int2`() {
			val v = Int2()
			v.set(Int2(10, 20))
			v shouldBe Int2(10, 20)
		}

		@Test
		fun `setValues sets x and y`() {
			val v = Int2()
			v.setValues(3, 7)
			v.x shouldBe 3
			v.y shouldBe 7
		}

		@Test
		fun `addMultiple adds scaled vector`() {
			val v = Int2(1, 2)
			v.addMultiple(Int2(3, 4), 2)
			v.x shouldBe 7
			v.y shouldBe 10
		}

		@Test
		fun `copyTo writes to array at offset`() {
			val data = IntArray(5)
			Int2(10, 20).copyTo(data, 2)
			data[2] shouldBe 10
			data[3] shouldBe 20
			data[0] shouldBe 0
		}

		@Test
		fun `toString produces readable representation`() {
			Int2(3, 7).toString() shouldBe "Int2(x=3, y=7)"
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	inner class EqualityAndHashCode {
		@Test
		fun `equal vectors are equal`() {
			Int2(3, 7) shouldBe Int2(3, 7)
		}

		@Test
		fun `different vectors are not equal`() {
			Int2(3, 7) shouldNotBe Int2(7, 3)
		}

		@Test
		fun `same instance is equal`() {
			val v = Int2(1, 2)
			(v == v) shouldBe true
		}

		@Test
		fun `not equal to null`() {
			Int2(1, 2).equals(null) shouldBe false
		}

		@Test
		fun `not equal to different type`() {
			Int2(1, 2).equals("not a vector") shouldBe false
		}

		@Test
		fun `equal vectors have same hashCode`() {
			Int2(3, 7).hashCode() shouldBe Int2(3, 7).hashCode()
		}

		@Test
		fun `different vectors likely have different hashCode`() {
			Int2(3, 7).hashCode() shouldNotBe Int2(7, 3).hashCode()
		}
	}
}
