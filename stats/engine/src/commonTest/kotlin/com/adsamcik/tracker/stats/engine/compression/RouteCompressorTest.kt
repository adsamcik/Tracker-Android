package com.adsamcik.tracker.stats.engine.compression

import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RouteCompressorTest {

	private fun point(lat: Double, lng: Double, timeMs: Long, accuracyM: Float = 5f) =
		LocationPoint(lat, lng, timeMs, accuracyM)

	@Nested
	inner class EmptyAndSingle {
		@Test
		fun `empty sequence returns null`() {
			val result = RouteCompressor.compress(emptySequence())
			result.shouldBeNull()
		}

		@Test
		fun `single point produces valid result`() {
			val points = sequenceOf(point(50.0, 14.0, 1000L))
			val result = RouteCompressor.compress(points)

			result.shouldNotBeNull()
			result.pointCount shouldBe 1
			result.simplifiedCount shouldBe 1
			result.startTimeMs shouldBe 1000L
			result.endTimeMs shouldBe 1000L
			result.distanceMeters shouldBe 0.0
			result.encodedPolyline.shouldNotBeEmpty()
		}

		@Test
		fun `two points produce valid result`() {
			val points = sequenceOf(
				point(50.0, 14.0, 1000L),
				point(50.001, 14.0, 2000L),
			)
			val result = RouteCompressor.compress(points)

			result.shouldNotBeNull()
			result.pointCount shouldBe 2
			result.simplifiedCount shouldBe 2
			result.startTimeMs shouldBe 1000L
			result.endTimeMs shouldBe 2000L
			result.distanceMeters shouldBeGreaterThan 0.0
		}
	}

	@Nested
	inner class StraightPath {
		@Test
		fun `straight path compresses to two points`() {
			// 50 points along a straight north-south line
			val points = (0 until 50).map { i ->
				point(50.0 + i * 0.0001, 14.0, 1000L + i * 1000L)
			}.asSequence()

			val result = RouteCompressor.compress(points)

			result.shouldNotBeNull()
			result.pointCount shouldBe 50
			result.simplifiedCount shouldBe 2 // straight line -> 2 endpoints
		}

		@Test
		fun `compression ratio is significant for straight paths`() {
			val points = (0 until 100).map { i ->
				point(50.0 + i * 0.00005, 14.0 + i * 0.00005, 1000L + i * 1000L)
			}.asSequence()

			val result = RouteCompressor.compress(points)

			result.shouldNotBeNull()
			assert(result.simplifiedCount < result.pointCount / 5) {
				"Expected 5x+ compression, got ${result.pointCount}/${result.simplifiedCount}"
			}
		}
	}

	@Nested
	inner class DistanceCalculation {
		@Test
		fun `known distance Prague to Brno approximately 185km`() {
			// Prague: 50.0755, 14.4378
			// Brno: 49.1951, 16.6068
			val distance = RouteCompressor.haversineDistance(
				LatLng(50.0755, 14.4378),
				LatLng(49.1951, 16.6068),
			)

			// Prague to Brno is about 185 km straight line
			distance.shouldBeBetween(180_000.0, 190_000.0, 0.0)
		}

		@Test
		fun `same point has zero distance`() {
			val distance = RouteCompressor.haversineDistance(
				LatLng(50.0, 14.0),
				LatLng(50.0, 14.0),
			)
			distance shouldBe 0.0
		}

		@Test
		fun `distance is symmetric`() {
			val a = LatLng(50.0, 14.0)
			val b = LatLng(51.0, 15.0)
			val d1 = RouteCompressor.haversineDistance(a, b)
			val d2 = RouteCompressor.haversineDistance(b, a)

			assert(kotlin.math.abs(d1 - d2) < 0.001) {
				"Distance should be symmetric: $d1 vs $d2"
			}
		}

		@Test
		fun `path distance accumulates correctly`() {
			val points = listOf(
				LatLng(50.0, 14.0),
				LatLng(50.001, 14.0),
				LatLng(50.002, 14.0),
			)
			val totalDist = RouteCompressor.calculatePathDistance(points)
			val segDist1 = RouteCompressor.haversineDistance(points[0], points[1])
			val segDist2 = RouteCompressor.haversineDistance(points[1], points[2])

			assert(kotlin.math.abs(totalDist - (segDist1 + segDist2)) < 0.001) {
				"Total distance should equal sum of segments"
			}
		}
	}

	@Nested
	inner class TimeRange {
		@Test
		fun `time range extracted from first and last points`() {
			val points = listOf(
				point(50.0, 14.0, 100L),
				point(50.001, 14.001, 200L),
				point(50.002, 14.002, 500L),
				point(50.003, 14.003, 900L),
			).asSequence()

			val result = RouteCompressor.compress(points)

			result.shouldNotBeNull()
			result.startTimeMs shouldBe 100L
			result.endTimeMs shouldBe 900L
		}
	}

	@Nested
	inner class RealWorldScenario {
		@Test
		fun `noisy GPS track compresses well`() {
			// Simulate GPS walk with noise
			val rng = java.util.Random(123)
			val points = (0 until 300).map { i ->
				point(
					lat = 50.0 + i * 0.00002 + rng.nextGaussian() * 0.000005,
					lng = 14.0 + i * 0.00002 + rng.nextGaussian() * 0.000005,
					timeMs = 1_000_000L + i * 5000L,
					accuracyM = (5 + rng.nextFloat() * 10),
				)
			}.asSequence()

			val result = RouteCompressor.compress(points)

			result.shouldNotBeNull()
			result.pointCount shouldBe 300
			result.simplifiedCount shouldBeGreaterThanOrEqual 2
			assert(result.simplifiedCount < 100) {
				"Expected significant compression, got ${result.simplifiedCount} from 300"
			}
			result.distanceMeters shouldBeGreaterThan 0.0
			result.encodedPolyline.shouldNotBeEmpty()
		}

		@Test
		fun `custom epsilon changes compression ratio`() {
			val pointList = (0 until 100).map { i ->
				point(
					lat = 50.0 + i * 0.0001 + (if (i % 5 == 0) 0.00005 else 0.0),
					lng = 14.0 + i * 0.0001,
					timeMs = 1000L + i * 1000L,
				)
			}

			val tight = RouteCompressor.compress(pointList.asSequence(), epsilonMeters = 1.0)
			val loose = RouteCompressor.compress(pointList.asSequence(), epsilonMeters = 100.0)

			tight.shouldNotBeNull()
			loose.shouldNotBeNull()
			assert(loose.simplifiedCount <= tight.simplifiedCount) {
				"Loose epsilon should produce <= points than tight"
			}
		}
	}
}
