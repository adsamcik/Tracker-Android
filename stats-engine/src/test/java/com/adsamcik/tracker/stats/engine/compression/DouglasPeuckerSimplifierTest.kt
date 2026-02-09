package com.adsamcik.tracker.stats.engine.compression

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DouglasPeuckerSimplifierTest {

	@Nested
	inner class EdgeCases {
		@Test
		fun `empty input returns empty`() {
			val result = DouglasPeuckerSimplifier.simplify(emptyList(), 10.0)
			result shouldHaveSize 0
		}

		@Test
		fun `single point returns single point`() {
			val points = listOf(LatLng(50.0, 14.0))
			val result = DouglasPeuckerSimplifier.simplify(points, 10.0)
			result shouldHaveSize 1
			result[0] shouldBe LatLng(50.0, 14.0)
		}

		@Test
		fun `two points returns both`() {
			val points = listOf(LatLng(50.0, 14.0), LatLng(50.001, 14.001))
			val result = DouglasPeuckerSimplifier.simplify(points, 10.0)
			result shouldHaveSize 2
		}

		@Test
		fun `negative epsilon throws IllegalArgumentException`() {
			val points = listOf(LatLng(50.0, 14.0), LatLng(50.001, 14.001), LatLng(50.002, 14.0))
			assertThrows<IllegalArgumentException> {
				DouglasPeuckerSimplifier.simplify(points, -5.0)
			}
		}

		@Test
		fun `zero epsilon throws IllegalArgumentException`() {
			val points = listOf(LatLng(50.0, 14.0), LatLng(50.001, 14.001), LatLng(50.002, 14.0))
			assertThrows<IllegalArgumentException> {
				DouglasPeuckerSimplifier.simplify(points, 0.0)
			}
		}
	}

	@Nested
	inner class StraightLine {
		@Test
		fun `straight north-south line simplifies to endpoints`() {
			// Points along constant longitude, varying latitude
			val points = (0..20).map { i -> LatLng(50.0 + i * 0.0001, 14.0) }
			val result = DouglasPeuckerSimplifier.simplify(points, 10.0)

			result shouldHaveSize 2
			result.first().lat shouldBe 50.0
			result.last().lat shouldBe points.last().lat
		}

		@Test
		fun `straight east-west line simplifies to endpoints`() {
			val points = (0..20).map { i -> LatLng(50.0, 14.0 + i * 0.0001) }
			val result = DouglasPeuckerSimplifier.simplify(points, 10.0)

			result shouldHaveSize 2
		}

		@Test
		fun `diagonal line simplifies to endpoints`() {
			val points = (0..15).map { i ->
				LatLng(50.0 + i * 0.0001, 14.0 + i * 0.0001)
			}
			val result = DouglasPeuckerSimplifier.simplify(points, 10.0)

			result shouldHaveSize 2
		}
	}

	@Nested
	inner class ShapePreservation {
		@Test
		fun `L-shaped path preserves corner`() {
			// Go north then east
			val goNorth = (0..10).map { i -> LatLng(50.0 + i * 0.001, 14.0) }
			val goEast = (1..10).map { i -> LatLng(50.01, 14.0 + i * 0.001) }
			val points = goNorth + goEast

			val result = DouglasPeuckerSimplifier.simplify(points, 5.0)

			// Must keep start, corner, end at minimum
			result.size shouldBeGreaterThanOrEqual 3
			result.first() shouldBe LatLng(50.0, 14.0)
			result.last() shouldBe goEast.last()
			// Corner point should be preserved
			val hasCorner = result.any {
				kotlin.math.abs(it.lat - 50.01) < 0.0001 &&
						kotlin.math.abs(it.lng - 14.0) < 0.0001
			}
			assert(hasCorner) { "Corner point near (50.01, 14.0) should be retained" }
		}

		@Test
		fun `zigzag pattern preserves peaks`() {
			val points = listOf(
				LatLng(50.0, 14.0),
				LatLng(50.001, 14.001),  // Peak 1
				LatLng(50.0, 14.002),
				LatLng(50.001, 14.003),  // Peak 2
				LatLng(50.0, 14.004),
			)
			// Small epsilon to keep most detail
			val result = DouglasPeuckerSimplifier.simplify(points, 1.0)

			// With 1m tolerance, the ~111m zigzag peaks should be preserved
			result.size shouldBeGreaterThanOrEqual 3
		}

		@Test
		fun `circle-like path retains shape`() {
			// Generate roughly circular path (16 points)
			val center = LatLng(50.0, 14.0)
			val radius = 0.001 // ~111m
			val points = (0..16).map { i ->
				val angle = Math.toRadians(i * 360.0 / 16)
				LatLng(
					center.lat + radius * kotlin.math.cos(angle),
					center.lng + radius * kotlin.math.sin(angle),
				)
			}

			val result = DouglasPeuckerSimplifier.simplify(points, 5.0)

			// Circle should keep multiple points to retain curvature
			result.size shouldBeGreaterThanOrEqual 4
		}
	}

	@Nested
	inner class EpsilonBehavior {
		@Test
		fun `larger epsilon produces fewer points`() {
			val points = (0..50).map { i ->
				LatLng(
					50.0 + i * 0.0001 + (if (i % 3 == 0) 0.00005 else 0.0),
					14.0 + i * 0.0001,
				)
			}

			val tight = DouglasPeuckerSimplifier.simplify(points, 1.0)
			val loose = DouglasPeuckerSimplifier.simplify(points, 50.0)

			assert(loose.size <= tight.size) {
				"Loose epsilon (${loose.size}) should produce <= points than tight (${tight.size})"
			}
		}

		@Test
		fun `very small epsilon retains most points`() {
			// Non-collinear points with slight variations
			val points = (0..10).map { i ->
				LatLng(50.0 + i * 0.001, 14.0 + (i % 2) * 0.0005)
			}

			val result = DouglasPeuckerSimplifier.simplify(points, 0.1) // 0.1m very tight
			// Should keep most of the zigzag points
			result.size shouldBeGreaterThanOrEqual 5
		}

		@Test
		fun `very large epsilon collapses to endpoints`() {
			// Any path should collapse to just endpoints with huge epsilon
			val points = (0..20).map { i ->
				LatLng(50.0 + i * 0.0001, 14.0 + (i % 2) * 0.0001)
			}

			val result = DouglasPeuckerSimplifier.simplify(points, 100_000.0) // 100km
			result shouldHaveSize 2
		}
	}

	@Nested
	inner class LargeInput {
		@Test
		fun `thousand point straight line simplifies efficiently`() {
			val points = (0 until 1000).map { i ->
				LatLng(50.0 + i * 0.00001, 14.0 + i * 0.00001)
			}

			val result = DouglasPeuckerSimplifier.simplify(points, 10.0)
			result shouldHaveSize 2
		}

		@Test
		fun `thousand point noisy track compresses significantly`() {
			// Simulate a GPS track with jitter
			val rng = java.util.Random(42)
			val points = (0 until 1000).map { i ->
				LatLng(
					50.0 + i * 0.00001 + rng.nextGaussian() * 0.000005,
					14.0 + i * 0.00001 + rng.nextGaussian() * 0.000005,
				)
			}

			val result = DouglasPeuckerSimplifier.simplify(points, 10.0)

			// Should achieve significant compression on noisy straight-ish path
			assert(result.size < points.size / 2) {
				"Expected significant compression, got ${result.size} from ${points.size}"
			}
		}
	}

	@Nested
	inner class SimplifyIndices {
		@Test
		fun `empty input returns empty indices`() {
			DouglasPeuckerSimplifier.simplifyIndices(emptyList(), 10.0) shouldHaveSize 0
		}

		@Test
		fun `two points return both indices`() {
			val points = listOf(LatLng(50.0, 14.0), LatLng(50.001, 14.001))
			val indices = DouglasPeuckerSimplifier.simplifyIndices(points, 10.0)
			indices shouldBe listOf(0, 1)
		}

		@Test
		fun `indices correspond to original points`() {
			val points = listOf(
				LatLng(50.0, 14.0),       // 0
				LatLng(50.0005, 14.0005), // 1 (on line)
				LatLng(50.001, 14.001),   // 2
			)
			val indices = DouglasPeuckerSimplifier.simplifyIndices(points, 100.0)
			indices shouldBe listOf(0, 2) // middle point on line should be removed
		}

		@Test
		fun `negative epsilon throws`() {
			val points = listOf(LatLng(50.0, 14.0), LatLng(50.001, 14.001), LatLng(50.002, 14.0))
			assertThrows<IllegalArgumentException> {
				DouglasPeuckerSimplifier.simplifyIndices(points, -1.0)
			}
		}
	}
}
