package com.adsamcik.tracker.stats.engine.algorithm

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PolylineEncoderTest {

	@Nested
	inner class EncodeDecodeRoundTrip {
		@Test
		fun `empty list encodes to empty string`() {
			PolylineEncoder.encode(emptyList()) shouldBe ""
		}

		@Test
		fun `empty string decodes to empty list`() {
			PolylineEncoder.decode("") shouldBe emptyList()
		}

		@Test
		fun `single point round-trips`() {
			val points = listOf(Pair(38.5, -120.2))
			val encoded = PolylineEncoder.encode(points)
			encoded.shouldNotBeEmpty()

			val decoded = PolylineEncoder.decode(encoded)
			decoded.size shouldBe 1
			assertCloseEnough(decoded[0].first, 38.5)
			assertCloseEnough(decoded[0].second, -120.2)
		}

		@Test
		fun `Google example polyline round-trips`() {
			// Classic Google example coordinates
			val points = listOf(
				Pair(38.5, -120.2),
				Pair(40.7, -120.95),
				Pair(43.252, -126.453),
			)
			val encoded = PolylineEncoder.encode(points)
			val decoded = PolylineEncoder.decode(encoded)

			decoded.size shouldBe 3
			assertCloseEnough(decoded[0].first, 38.5)
			assertCloseEnough(decoded[0].second, -120.2)
			assertCloseEnough(decoded[1].first, 40.7)
			assertCloseEnough(decoded[1].second, -120.95)
			assertCloseEnough(decoded[2].first, 43.252)
			assertCloseEnough(decoded[2].second, -126.453)
		}

		@Test
		fun `negative coordinates round-trip`() {
			val points = listOf(
				Pair(-33.8688, 151.2093),  // Sydney
				Pair(-37.8136, 144.9631),  // Melbourne
			)
			val encoded = PolylineEncoder.encode(points)
			val decoded = PolylineEncoder.decode(encoded)

			decoded.size shouldBe 2
			assertCloseEnough(decoded[0].first, -33.8688)
			assertCloseEnough(decoded[0].second, 151.2093)
			assertCloseEnough(decoded[1].first, -37.8136)
			assertCloseEnough(decoded[1].second, 144.9631)
		}

		@Test
		fun `many points round-trip`() {
			val points = (0 until 100).map { i ->
				Pair(50.0 + i * 0.001, 14.0 + i * 0.001)
			}
			val encoded = PolylineEncoder.encode(points)
			val decoded = PolylineEncoder.decode(encoded)

			decoded.size shouldBe 100
			for (i in points.indices) {
				assertCloseEnough(decoded[i].first, points[i].first)
				assertCloseEnough(decoded[i].second, points[i].second)
			}
		}
	}

	@Nested
	inner class EncodeE7Tests {
		@Test
		fun `empty arrays encode to empty string`() {
			PolylineEncoder.encodeE7(intArrayOf(), intArrayOf()).shouldBeEmpty()
		}

		@Test
		fun `E7 encoding produces decodable output`() {
			// Prague: 50.0755° N, 14.4378° E → E7: 500755000, 144378000
			val latE7 = intArrayOf(500755000, 500800000)
			val lonE7 = intArrayOf(144378000, 144400000)

			val encoded = PolylineEncoder.encodeE7(latE7, lonE7)
			encoded.shouldNotBeEmpty()

			// Decode and verify (E7→E5 loses 2 digits of precision)
			val decoded = PolylineEncoder.decode(encoded)
			decoded.size shouldBe 2
			assertCloseEnough(decoded[0].first, 50.0755, tolerance = 0.001)
			assertCloseEnough(decoded[0].second, 14.4378, tolerance = 0.001)
		}

		@Test
		fun `mismatched array sizes throw`() {
			try {
				PolylineEncoder.encodeE7(intArrayOf(1, 2), intArrayOf(1))
				throw AssertionError("Should have thrown")
			} catch (e: IllegalArgumentException) {
				// Expected
			}
		}
	}

	@Nested
	inner class CompressionEfficiency {
		@Test
		fun `encoded string is shorter than raw coordinate text`() {
			val points = (0 until 50).map { i ->
				Pair(50.0 + i * 0.01, 14.0 + i * 0.01)
			}
			val encoded = PolylineEncoder.encode(points)
			val rawLength = points.joinToString(",") { "${it.first},${it.second}" }.length

			// Encoded should be significantly shorter
			assert(encoded.length < rawLength) {
				"Encoded (${ encoded.length}) should be shorter than raw ($rawLength)"
			}
		}
	}

	private fun assertCloseEnough(
		actual: Double,
		expected: Double,
		tolerance: Double = 0.00001,
	) {
		assert(kotlin.math.abs(actual - expected) < tolerance) {
			"Expected $expected but got $actual (tolerance: $tolerance)"
		}
	}
}
