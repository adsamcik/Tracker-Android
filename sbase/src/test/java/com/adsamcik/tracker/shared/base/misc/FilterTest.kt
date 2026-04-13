package com.adsamcik.tracker.shared.base.misc

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Filter - Ramer-Douglas-Peucker simplification")
class FilterTest {

	@Nested
	@DisplayName("Edge cases")
	inner class EdgeCases {
		@Test
		fun `empty list returns empty list`() {
			Filter.rdpSimplify(emptyList(), 1.0) shouldHaveSize 0
		}

		@Test
		fun `single point returns single point`() {
			val points = listOf(Double2(1.0, 1.0))
			val result = Filter.rdpSimplify(points, 1.0)
			result shouldHaveSize 1
			result[0] shouldBe Double2(1.0, 1.0)
		}

		@Test
		fun `two points returns both points`() {
			val points = listOf(Double2(0.0, 0.0), Double2(10.0, 10.0))
			val result = Filter.rdpSimplify(points, 1.0)
			result shouldHaveSize 2
			result shouldContainExactly points
		}
	}

	@Nested
	@DisplayName("Collinear points")
	inner class CollinearPoints {
		@Test
		fun `collinear points on horizontal line reduce to endpoints`() {
			val points = listOf(
				Double2(0.0, 0.0),
				Double2(1.0, 0.0),
				Double2(2.0, 0.0),
				Double2(3.0, 0.0),
				Double2(4.0, 0.0)
			)
			val result = Filter.rdpSimplify(points, 0.1)
			result shouldHaveSize 2
			result.first() shouldBe points.first()
			result.last() shouldBe points.last()
		}

		@Test
		fun `collinear points on diagonal line reduce to endpoints`() {
			val points = (0..10).map { Double2(it.toDouble(), it.toDouble()) }
			val result = Filter.rdpSimplify(points, 0.1)
			result shouldHaveSize 2
		}
	}

	@Nested
	@DisplayName("Complex paths")
	inner class ComplexPaths {
		@Test
		fun `L-shaped path keeps corner point`() {
			val points = listOf(
				Double2(0.0, 0.0),
				Double2(5.0, 0.0),
				Double2(5.0, 5.0)
			)
			val result = Filter.rdpSimplify(points, 0.5)
			result shouldHaveSize 3
			result shouldContainExactly points
		}

		@Test
		fun `zigzag path with small threshold keeps more points`() {
			val points = listOf(
				Double2(0.0, 0.0),
				Double2(1.0, 5.0),
				Double2(2.0, 0.0),
				Double2(3.0, 5.0),
				Double2(4.0, 0.0)
			)
			val smallThreshold = Filter.rdpSimplify(points, 0.1)
			val largeThreshold = Filter.rdpSimplify(points, 10.0)
			(smallThreshold.size >= largeThreshold.size) shouldBe true
		}

		@Test
		fun `high threshold reduces to just endpoints`() {
			val points = listOf(
				Double2(0.0, 0.0),
				Double2(1.0, 2.0),
				Double2(2.0, 1.0),
				Double2(3.0, 3.0),
				Double2(10.0, 10.0)
			)
			val result = Filter.rdpSimplify(points, 100.0)
			result shouldHaveSize 2
			result.first() shouldBe points.first()
			result.last() shouldBe points.last()
		}

		@Test
		fun `zero threshold keeps all points in non-collinear path`() {
			val points = listOf(
				Double2(0.0, 0.0),
				Double2(1.0, 5.0),
				Double2(2.0, 0.0),
				Double2(3.0, 5.0),
				Double2(4.0, 0.0)
			)
			val result = Filter.rdpSimplify(points, 0.0)
			result shouldHaveSize points.size
		}

		@Test
		fun `result always contains first and last point`() {
			val points = listOf(
				Double2(0.0, 0.0),
				Double2(5.0, 10.0),
				Double2(10.0, 0.0),
				Double2(15.0, 10.0),
				Double2(20.0, 0.0)
			)
			val result = Filter.rdpSimplify(points, 2.0)
			result.first() shouldBe points.first()
			result.last() shouldBe points.last()
		}
	}

	@Nested
	@DisplayName("Extension functions")
	inner class ExtensionFunctions {
		@Test
		fun `List simplifyRDP delegates to Filter`() {
			val points = listOf(
				Double2(0.0, 0.0),
				Double2(1.0, 0.0),
				Double2(2.0, 0.0)
			)
			val result = points.simplifyRDP(0.1)
			result shouldBe Filter.rdpSimplify(points, 0.1)
		}

		@Test
		fun `Sequence simplifyRDP delegates to Filter`() {
			val points = listOf(
				Double2(0.0, 0.0),
				Double2(1.0, 0.0),
				Double2(2.0, 0.0)
			)
			val result = points.asSequence().simplifyRDP(0.1)
			result shouldBe Filter.rdpSimplify(points, 0.1)
		}
	}
}
