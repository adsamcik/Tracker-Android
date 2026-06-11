package com.adsamcik.tracker.stats.engine.algorithm

import com.adsamcik.tracker.stats.engine.algorithm.DouglasPeucker.Point
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DouglasPeuckerTest {

	@Nested
	inner class SimplifyTests {
		@Test
		fun `empty list returns empty`() {
			DouglasPeucker.simplify(emptyList(), 1.0) shouldHaveSize 0
		}

		@Test
		fun `single point returns single point`() {
			val points = listOf(Point(0.0, 0.0))
			DouglasPeucker.simplify(points, 1.0) shouldHaveSize 1
		}

		@Test
		fun `two points returns both`() {
			val points = listOf(Point(0.0, 0.0), Point(10.0, 10.0))
			DouglasPeucker.simplify(points, 1.0) shouldHaveSize 2
		}

		@Test
		fun `collinear points simplified to endpoints`() {
			// All points on the line y = x
			val points = (0..10).map { Point(it.toDouble(), it.toDouble()) }
			val simplified = DouglasPeucker.simplify(points, 0.1)

			// Should keep only first and last
			simplified shouldHaveSize 2
			simplified.first() shouldBe Point(0.0, 0.0)
			simplified.last() shouldBe Point(10.0, 10.0)
		}

		@Test
		fun `significant deviation keeps midpoint`() {
			val points = listOf(
				Point(0.0, 0.0),
				Point(5.0, 10.0),  // Far from the line (0,0)→(10,0)
				Point(10.0, 0.0),
			)
			val simplified = DouglasPeucker.simplify(points, 1.0)

			// The middle point deviates by 10 units, well above epsilon=1
			simplified shouldHaveSize 3
		}

		@Test
		fun `small deviation removes midpoint`() {
			val points = listOf(
				Point(0.0, 0.0),
				Point(5.0, 0.01),  // Very close to line
				Point(10.0, 0.0),
			)
			val simplified = DouglasPeucker.simplify(points, 0.1)

			simplified shouldHaveSize 2
		}

		@Test
		fun `complex path retains shape`() {
			// L-shaped path
			val points = listOf(
				Point(0.0, 0.0),
				Point(0.0, 1.0),
				Point(0.0, 2.0),
				Point(0.0, 3.0),
				Point(0.0, 4.0),
				Point(0.0, 5.0),  // Corner
				Point(1.0, 5.0),
				Point(2.0, 5.0),
				Point(3.0, 5.0),
				Point(4.0, 5.0),
				Point(5.0, 5.0),
			)
			val simplified = DouglasPeucker.simplify(points, 0.01)

			// Should keep at least start, corner, and end
			assert(simplified.size >= 3) { "Expected >= 3 points, got ${simplified.size}" }
			simplified.first() shouldBe Point(0.0, 0.0)
			simplified.last() shouldBe Point(5.0, 5.0)
			// Corner should be retained
			assert(simplified.contains(Point(0.0, 5.0))) {
				"Corner point (0,5) should be retained"
			}
		}

		@Test
		fun `negative epsilon throws`() {
			val points = listOf(Point(0.0, 0.0), Point(1.0, 1.0), Point(2.0, 0.0))
			try {
				DouglasPeucker.simplify(points, -1.0)
				throw AssertionError("Should have thrown")
			} catch (e: IllegalArgumentException) {
				// Expected
			}
		}

		@Test
		fun `zero epsilon throws`() {
			val points = listOf(Point(0.0, 0.0), Point(1.0, 1.0), Point(2.0, 0.0))
			try {
				DouglasPeucker.simplify(points, 0.0)
				throw AssertionError("Should have thrown")
			} catch (e: IllegalArgumentException) {
				// Expected
			}
		}
	}

	@Nested
	inner class SimplifyE7Tests {
		@Test
		fun `empty arrays return empty indices`() {
			DouglasPeucker.simplifyE7(intArrayOf(), intArrayOf(), 10.0) shouldHaveSize 0
		}

		@Test
		fun `two points return both indices`() {
			val lat = intArrayOf(500000000, 500100000) // ~50.0, ~50.01
			val lon = intArrayOf(140000000, 140100000)
			val indices = DouglasPeucker.simplifyE7(lat, lon, 10.0)

			indices shouldBe listOf(0, 1)
		}

		@Test
		fun `straight line simplified to endpoints`() {
			// Points along a straight line
			val lat = IntArray(10) { 500000000 + it * 10000 } // Incrementing lat
			val lon = IntArray(10) { 140000000 + it * 10000 } // Incrementing lon

			val indices = DouglasPeucker.simplifyE7(lat, lon, 100.0) // 100m tolerance

			// Straight line → only endpoints
			indices shouldBe listOf(0, 9)
		}

		@Test
		fun `mismatched arrays throw`() {
			try {
				DouglasPeucker.simplifyE7(intArrayOf(1, 2, 3), intArrayOf(1, 2), 10.0)
				throw AssertionError("Should have thrown")
			} catch (e: IllegalArgumentException) {
				// Expected
			}
		}
	}

	@Nested
	inner class PerpendicularDistanceTests {
		@Test
		fun `point on line has zero distance`() {
			val dist = DouglasPeucker.perpendicularDistance(
				Point(5.0, 5.0),
				Point(0.0, 0.0),
				Point(10.0, 10.0),
			)
			dist shouldBeLessThan 0.0001
		}

		@Test
		fun `point perpendicular to horizontal line`() {
			val dist = DouglasPeucker.perpendicularDistance(
				Point(5.0, 3.0),
				Point(0.0, 0.0),
				Point(10.0, 0.0),
			)
			// Distance from (5,3) to line y=0 should be 3
			assert(kotlin.math.abs(dist - 3.0) < 0.0001) {
				"Expected distance ~3.0, got $dist"
			}
		}

		@Test
		fun `degenerate line segment (point)`() {
			val dist = DouglasPeucker.perpendicularDistance(
				Point(3.0, 4.0),
				Point(0.0, 0.0),
				Point(0.0, 0.0),
			)
			// Distance from (3,4) to (0,0) = 5
			assert(kotlin.math.abs(dist - 5.0) < 0.0001) {
				"Expected distance ~5.0, got $dist"
			}
		}
	}
}
