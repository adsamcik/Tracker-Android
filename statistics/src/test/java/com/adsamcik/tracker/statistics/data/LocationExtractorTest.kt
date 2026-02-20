package com.adsamcik.tracker.statistics.data

import com.adsamcik.tracker.shared.base.data.Location
import io.kotest.matchers.doubles.shouldBeExactly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("LocationExtractor")
class LocationExtractorTest {

	private val extractor = LocationExtractor()
	private val multiplier = 1_000_000.0

	private fun location(
		lat: Double = 0.0,
		lon: Double = 0.0,
		alt: Double? = null,
	): Location = Location(
		time = 0L,
		latitude = lat,
		longitude = lon,
		altitude = alt,
		horizontalAccuracy = null,
		verticalAccuracy = null,
		speed = null,
		speedAccuracy = null,
	)

	// =====================================================================
	// X (longitude)
	// =====================================================================

	@Nested
	@DisplayName("getX - longitude extraction")
	inner class GetX {

		@Test
		fun `positive longitude is scaled by multiplication constant`() {
			val loc = location(lon = 14.42)
			extractor.getX(loc) shouldBeExactly 14.42 * multiplier
		}

		@Test
		fun `negative longitude is scaled correctly`() {
			val loc = location(lon = -73.9857)
			extractor.getX(loc) shouldBeExactly -73.9857 * multiplier
		}

		@Test
		fun `zero longitude returns zero`() {
			val loc = location(lon = 0.0)
			extractor.getX(loc) shouldBeExactly 0.0
		}

		@Test
		fun `max longitude 180 is scaled`() {
			val loc = location(lon = 180.0)
			extractor.getX(loc) shouldBeExactly 180.0 * multiplier
		}

		@Test
		fun `min longitude -180 is scaled`() {
			val loc = location(lon = -180.0)
			extractor.getX(loc) shouldBeExactly -180.0 * multiplier
		}
	}

	// =====================================================================
	// Y (latitude)
	// =====================================================================

	@Nested
	@DisplayName("getY - latitude extraction")
	inner class GetY {

		@Test
		fun `positive latitude is scaled by multiplication constant`() {
			val loc = location(lat = 50.08)
			extractor.getY(loc) shouldBeExactly 50.08 * multiplier
		}

		@Test
		fun `negative latitude is scaled correctly`() {
			val loc = location(lat = -33.8688)
			extractor.getY(loc) shouldBeExactly -33.8688 * multiplier
		}

		@Test
		fun `zero latitude returns zero`() {
			val loc = location(lat = 0.0)
			extractor.getY(loc) shouldBeExactly 0.0
		}

		@Test
		fun `north pole latitude 90 is scaled`() {
			val loc = location(lat = 90.0)
			extractor.getY(loc) shouldBeExactly 90.0 * multiplier
		}

		@Test
		fun `south pole latitude -90 is scaled`() {
			val loc = location(lat = -90.0)
			extractor.getY(loc) shouldBeExactly -90.0 * multiplier
		}
	}

	// =====================================================================
	// Z (altitude)
	// =====================================================================

	@Nested
	@DisplayName("getZ - altitude extraction")
	inner class GetZ {

		@Test
		fun `positive altitude is scaled by multiplication constant`() {
			val loc = location(alt = 350.0)
			extractor.getZ(loc) shouldBeExactly 350.0 * multiplier
		}

		@Test
		fun `null altitude returns zero`() {
			val loc = location(alt = null)
			extractor.getZ(loc) shouldBeExactly 0.0
		}

		@Test
		fun `zero altitude returns zero`() {
			val loc = location(alt = 0.0)
			extractor.getZ(loc) shouldBeExactly 0.0
		}

		@Test
		fun `negative altitude is scaled correctly`() {
			// Dead Sea ~-430m below sea level
			val loc = location(alt = -430.0)
			extractor.getZ(loc) shouldBeExactly -430.0 * multiplier
		}

		@Test
		fun `high altitude is scaled correctly`() {
			// Everest summit ~8849m
			val loc = location(alt = 8849.0)
			extractor.getZ(loc) shouldBeExactly 8849.0 * multiplier
		}
	}

	// =====================================================================
	// Combined coordinates
	// =====================================================================

	@Nested
	@DisplayName("Combined coordinate extraction")
	inner class Combined {

		@Test
		fun `all coordinates extracted from single location`() {
			val loc = location(lat = 50.08, lon = 14.42, alt = 350.0)

			extractor.getX(loc) shouldBeExactly 14.42 * multiplier
			extractor.getY(loc) shouldBeExactly 50.08 * multiplier
			extractor.getZ(loc) shouldBeExactly 350.0 * multiplier
		}

		@Test
		fun `origin point returns all zeros`() {
			val loc = location(lat = 0.0, lon = 0.0, alt = 0.0)

			extractor.getX(loc) shouldBeExactly 0.0
			extractor.getY(loc) shouldBeExactly 0.0
			extractor.getZ(loc) shouldBeExactly 0.0
		}

		@Test
		fun `anti-meridian point extracts correctly`() {
			// Near the international date line
			val loc = location(lat = -45.0, lon = 179.99, alt = 10.0)

			extractor.getX(loc) shouldBeExactly 179.99 * multiplier
			extractor.getY(loc) shouldBeExactly -45.0 * multiplier
			extractor.getZ(loc) shouldBeExactly 10.0 * multiplier
		}
	}
}
