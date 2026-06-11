package com.adsamcik.tracker.statistics.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ChartPoint")
class ChartPointTest {

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `stores x and y`() {
			val point = ChartPoint(x = 1.0f, y = 2.5f)
			point.x shouldBe 1.0f
			point.y shouldBe 2.5f
		}

		@Test
		fun `zero values`() {
			val point = ChartPoint(x = 0f, y = 0f)
			point.x shouldBe 0f
			point.y shouldBe 0f
		}

		@Test
		fun `negative values`() {
			val point = ChartPoint(x = -1.5f, y = -3.0f)
			point.x shouldBe -1.5f
			point.y shouldBe -3.0f
		}
	}

	@Nested
	@DisplayName("Equality")
	inner class Equality {

		@Test
		fun `equality works`() {
			val p1 = ChartPoint(1f, 2f)
			val p2 = ChartPoint(1f, 2f)
			p1 shouldBe p2
			p1.hashCode() shouldBe p2.hashCode()
		}

		@Test
		fun `inequality for different x`() {
			val p1 = ChartPoint(1f, 2f)
			val p2 = ChartPoint(3f, 2f)
			p1 shouldNotBe p2
		}

		@Test
		fun `inequality for different y`() {
			val p1 = ChartPoint(1f, 2f)
			val p2 = ChartPoint(1f, 5f)
			p1 shouldNotBe p2
		}
	}

	@Nested
	@DisplayName("Copy and Destructuring")
	inner class CopyAndDestructuring {

		@Test
		fun `copy works`() {
			val original = ChartPoint(1f, 2f)
			val copy = original.copy(y = 10f)
			copy.x shouldBe 1f
			copy.y shouldBe 10f
		}

		@Test
		fun `destructuring works`() {
			val point = ChartPoint(x = 3.14f, y = 2.72f)
			val (x, y) = point
			x shouldBe 3.14f
			y shouldBe 2.72f
		}
	}
}
