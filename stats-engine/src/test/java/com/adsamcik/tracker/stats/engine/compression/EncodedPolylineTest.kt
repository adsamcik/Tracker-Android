package com.adsamcik.tracker.stats.engine.compression

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EncodedPolylineTest {

	private fun assertCloseEnough(
		actual: Double,
		expected: Double,
		tolerance: Double = 0.00002,
	) {
		assert(kotlin.math.abs(actual - expected) < tolerance) {
			"Expected $expected but got $actual (tolerance: $tolerance)"
		}
	}

	@Nested
	inner class Encode {
		@Test
		fun `empty list encodes to empty string`() {
			EncodedPolyline.encode(emptyList()).shouldBeEmpty()
		}

		@Test
		fun `single point encodes to non-empty string`() {
			val encoded = EncodedPolyline.encode(listOf(LatLng(50.0, 14.0)))
			encoded.shouldNotBeEmpty()
		}

		@Test
		fun `multiple points encode to non-empty string`() {
			val points = listOf(
				LatLng(38.5, -120.2),
				LatLng(40.7, -120.95),
				LatLng(43.252, -126.453),
			)
			val encoded = EncodedPolyline.encode(points)
			encoded.shouldNotBeEmpty()
		}
	}

	@Nested
	inner class Decode {
		@Test
		fun `empty string decodes to empty list`() {
			EncodedPolyline.decode("") shouldHaveSize 0
		}

		@Test
		fun `known Google example decodes correctly`() {
			// The classic Google example polyline: _p~iF~ps|U_ulLnnqC_mqNvxq`@
			val decoded = EncodedPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
			decoded shouldHaveSize 3
			assertCloseEnough(decoded[0].lat, 38.5)
			assertCloseEnough(decoded[0].lng, -120.2)
			assertCloseEnough(decoded[1].lat, 40.7)
			assertCloseEnough(decoded[1].lng, -120.95)
			assertCloseEnough(decoded[2].lat, 43.252)
			assertCloseEnough(decoded[2].lng, -126.453)
		}
	}

	@Nested
	inner class RoundTrip {
		@Test
		fun `single point round-trips`() {
			val original = listOf(LatLng(38.5, -120.2))
			val roundTripped = EncodedPolyline.decode(EncodedPolyline.encode(original))

			roundTripped shouldHaveSize 1
			assertCloseEnough(roundTripped[0].lat, original[0].lat)
			assertCloseEnough(roundTripped[0].lng, original[0].lng)
		}

		@Test
		fun `Google example coordinates round-trip`() {
			val original = listOf(
				LatLng(38.5, -120.2),
				LatLng(40.7, -120.95),
				LatLng(43.252, -126.453),
			)
			val roundTripped = EncodedPolyline.decode(EncodedPolyline.encode(original))

			roundTripped shouldHaveSize 3
			for (i in original.indices) {
				assertCloseEnough(roundTripped[i].lat, original[i].lat)
				assertCloseEnough(roundTripped[i].lng, original[i].lng)
			}
		}

		@Test
		fun `negative coordinates round-trip`() {
			val original = listOf(
				LatLng(-33.8688, 151.2093),  // Sydney
				LatLng(-37.8136, 144.9631),  // Melbourne
			)
			val roundTripped = EncodedPolyline.decode(EncodedPolyline.encode(original))

			roundTripped shouldHaveSize 2
			assertCloseEnough(roundTripped[0].lat, -33.8688)
			assertCloseEnough(roundTripped[0].lng, 151.2093)
			assertCloseEnough(roundTripped[1].lat, -37.8136)
			assertCloseEnough(roundTripped[1].lng, 144.9631)
		}

		@Test
		fun `high-precision coordinates round-trip within tolerance`() {
			// Polyline precision is 1e-5, so 5 decimal places
			val original = listOf(LatLng(50.07553, 14.43780))
			val roundTripped = EncodedPolyline.decode(EncodedPolyline.encode(original))

			roundTripped shouldHaveSize 1
			assertCloseEnough(roundTripped[0].lat, 50.07553)
			assertCloseEnough(roundTripped[0].lng, 14.43780)
		}

		@Test
		fun `zero coordinates round-trip`() {
			val original = listOf(LatLng(0.0, 0.0))
			val roundTripped = EncodedPolyline.decode(EncodedPolyline.encode(original))

			roundTripped shouldHaveSize 1
			assertCloseEnough(roundTripped[0].lat, 0.0)
			assertCloseEnough(roundTripped[0].lng, 0.0)
		}

		@Test
		fun `many points round-trip`() {
			val original = (0 until 200).map { i ->
				LatLng(50.0 + i * 0.001, 14.0 + i * 0.001)
			}
			val roundTripped = EncodedPolyline.decode(EncodedPolyline.encode(original))

			roundTripped shouldHaveSize 200
			for (i in original.indices) {
				assertCloseEnough(roundTripped[i].lat, original[i].lat)
				assertCloseEnough(roundTripped[i].lng, original[i].lng)
			}
		}

		@Test
		fun `extreme latitude values round-trip`() {
			val original = listOf(
				LatLng(89.99999, 0.0),   // Near north pole
				LatLng(-89.99999, 0.0),  // Near south pole
			)
			val roundTripped = EncodedPolyline.decode(EncodedPolyline.encode(original))

			roundTripped shouldHaveSize 2
			assertCloseEnough(roundTripped[0].lat, 89.99999)
			assertCloseEnough(roundTripped[1].lat, -89.99999)
		}
	}

	@Nested
	inner class Compression {
		@Test
		fun `encoded string is shorter than raw text representation`() {
			val points = (0 until 100).map { i ->
				LatLng(50.0 + i * 0.01, 14.0 + i * 0.01)
			}
			val encoded = EncodedPolyline.encode(points)
			val rawText = points.joinToString(",") { "${it.lat},${it.lng}" }

			assert(encoded.length < rawText.length) {
				"Encoded (${encoded.length}) should be shorter than raw (${rawText.length})"
			}
		}
	}
}
