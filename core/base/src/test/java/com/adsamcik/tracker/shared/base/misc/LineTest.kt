package com.adsamcik.tracker.shared.base.misc

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

@DisplayName("Line - perpendicular distance")
class LineTest {

	private companion object {
		const val EPSILON = 1e-9
	}

	@Nested
	@DisplayName("Point on line")
	inner class PointOnLine {
		@Test
		fun `point at start has zero distance`() {
			val line = Line(Double2(0.0, 0.0), Double2(10.0, 0.0))
			line.perpendicularDistance(Double2(0.0, 0.0)) shouldBeLessThan EPSILON
		}

		@Test
		fun `point at end has zero distance`() {
			val line = Line(Double2(0.0, 0.0), Double2(10.0, 0.0))
			line.perpendicularDistance(Double2(10.0, 0.0)) shouldBeLessThan EPSILON
		}

		@Test
		fun `point on midpoint of horizontal line has zero distance`() {
			val line = Line(Double2(0.0, 0.0), Double2(10.0, 0.0))
			line.perpendicularDistance(Double2(5.0, 0.0)) shouldBeLessThan EPSILON
		}

		@Test
		fun `point on diagonal line has zero distance`() {
			val line = Line(Double2(0.0, 0.0), Double2(10.0, 10.0))
			line.perpendicularDistance(Double2(5.0, 5.0)) shouldBeLessThan EPSILON
		}
	}

	@Nested
	@DisplayName("Point off line")
	inner class PointOffLine {
		@Test
		fun `point above horizontal line has correct distance`() {
			val line = Line(Double2(0.0, 0.0), Double2(10.0, 0.0))
			val dist = line.perpendicularDistance(Double2(5.0, 3.0))
			abs(dist - 3.0) shouldBeLessThan EPSILON
		}

		@Test
		fun `point below horizontal line has positive distance`() {
			val line = Line(Double2(0.0, 0.0), Double2(10.0, 0.0))
			val dist = line.perpendicularDistance(Double2(5.0, -3.0))
			abs(dist - 3.0) shouldBeLessThan EPSILON
		}

		@Test
		fun `point left of vertical line has correct distance`() {
			val line = Line(Double2(0.0, 0.0), Double2(0.0, 10.0))
			val dist = line.perpendicularDistance(Double2(-4.0, 5.0))
			abs(dist - 4.0) shouldBeLessThan EPSILON
		}

		@Test
		fun `point near diagonal line`() {
			// Line from (0,0) to (1,1). Point (0,1) should be sqrt(2)/2 away
			val line = Line(Double2(0.0, 0.0), Double2(1.0, 1.0))
			val dist = line.perpendicularDistance(Double2(0.0, 1.0))
			abs(dist - sqrt(2.0) / 2.0) shouldBeLessThan EPSILON
		}

		@Test
		fun `distance is always non-negative`() {
			val line = Line(Double2(1.0, 1.0), Double2(5.0, 5.0))
			line.perpendicularDistance(Double2(3.0, 0.0)) shouldBeGreaterThan 0.0
		}
	}

	@Nested
	@DisplayName("Degenerate cases")
	inner class DegenerateCases {
		@Test
		fun `zero-length line returns distance from point`() {
			val line = Line(Double2(5.0, 5.0), Double2(5.0, 5.0))
			val dist = line.perpendicularDistance(Double2(8.0, 9.0))
			abs(dist - 5.0) shouldBeLessThan EPSILON
		}

		@Test
		fun `zero-length line with point at same location returns zero`() {
			val line = Line(Double2(5.0, 5.0), Double2(5.0, 5.0))
			line.perpendicularDistance(Double2(5.0, 5.0)) shouldBeLessThan EPSILON
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality works`() {
			val line1 = Line(Double2(0.0, 0.0), Double2(1.0, 1.0))
			val line2 = Line(Double2(0.0, 0.0), Double2(1.0, 1.0))
			line1 shouldBe line2
		}

		@Test
		fun `copy works`() {
			val line = Line(Double2(0.0, 0.0), Double2(1.0, 1.0))
			val copy = line.copy(end = Double2(2.0, 2.0))
			copy.end shouldBe Double2(2.0, 2.0)
			copy.start shouldBe Double2(0.0, 0.0)
		}
	}
}
