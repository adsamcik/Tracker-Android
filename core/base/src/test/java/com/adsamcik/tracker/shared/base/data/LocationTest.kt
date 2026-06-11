package com.adsamcik.tracker.shared.base.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Location - geographic coordinate data class")
class LocationTest {

	private fun location(
		time: Long = 1000L,
		lat: Double = 50.0,
		lon: Double = 14.0,
		alt: Double? = null,
		hAcc: Float? = null,
		vAcc: Float? = null,
		speed: Float? = null,
		sAcc: Float? = null
	) = Location(time, lat, lon, alt, hAcc, vAcc, speed, sAcc)

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val loc = Location(100L, 50.0, 14.0, 300.0, 5.0f, 3.0f, 1.5f, 0.5f)
			loc.time shouldBe 100L
			loc.latitude shouldBe 50.0
			loc.longitude shouldBe 14.0
			loc.altitude shouldBe 300.0
			loc.horizontalAccuracy shouldBe 5.0f
			loc.verticalAccuracy shouldBe 3.0f
			loc.speed shouldBe 1.5f
			loc.speedAccuracy shouldBe 0.5f
		}

		@Test
		fun `nullable fields default to null`() {
			val loc = location()
			loc.altitude shouldBe null
			loc.horizontalAccuracy shouldBe null
			loc.verticalAccuracy shouldBe null
			loc.speed shouldBe null
			loc.speedAccuracy shouldBe null
		}

		@Test
		fun `copy constructor preserves all fields`() {
			val original = Location(100L, 50.0, 14.0, 300.0, 5.0f, 3.0f, 1.5f, 0.5f)
			val copy = Location(original)
			copy shouldBe original
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality for identical locations`() {
			location(time = 1L, lat = 50.0, lon = 14.0) shouldBe
					location(time = 1L, lat = 50.0, lon = 14.0)
		}

		@Test
		fun `inequality for different coordinates`() {
			location(lat = 50.0) shouldNotBe location(lat = 51.0)
		}

		@Test
		fun `hashCode consistent for equal objects`() {
			val a = location(time = 1L, lat = 50.0, lon = 14.0)
			val b = location(time = 1L, lat = 50.0, lon = 14.0)
			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `copy with modified field`() {
			val original = location(lat = 50.0)
			val modified = original.copy(latitude = 60.0)
			modified.latitude shouldBe 60.0
			modified.longitude shouldBe original.longitude
		}
	}

	@Nested
	@DisplayName("Distance calculations")
	inner class DistanceCalculations {
		@Test
		fun `distance to same point is zero`() {
			val loc = location(lat = 50.0, lon = 14.0)
			loc.distanceFlat(loc, LengthUnit.Meter) shouldBe 0.0
		}

		@Test
		fun `flat distance returns positive for different points`() {
			val a = location(lat = 50.0, lon = 14.0)
			val b = location(lat = 50.1, lon = 14.1)
			val dist = a.distanceFlat(b, LengthUnit.Meter)
			(dist > 0) shouldBe true
		}

		@Test
		fun `distance with altitude falls back to flat when altitude is null`() {
			val a = location(lat = 50.0, lon = 14.0, alt = null)
			val b = location(lat = 50.1, lon = 14.1, alt = 300.0)
			val distWithAlt = a.distance(b, LengthUnit.Meter)
			val distFlat = a.distanceFlat(b, LengthUnit.Meter)
			distWithAlt shouldBe distFlat
		}

		@Test
		fun `distance with altitude is greater than or equal to flat distance`() {
			val a = location(lat = 50.0, lon = 14.0, alt = 0.0)
			val b = location(lat = 50.1, lon = 14.1, alt = 1000.0)
			val distWithAlt = a.distance(b, LengthUnit.Meter)
			val distFlat = a.distanceFlat(b, LengthUnit.Meter)
			(distWithAlt >= distFlat) shouldBe true
		}

		@Test
		fun `kilometer distance is meter distance divided by 1000`() {
			val a = location(lat = 50.0, lon = 14.0)
			val b = location(lat = 51.0, lon = 15.0)
			val meters = a.distanceFlat(b, LengthUnit.Meter)
			val km = a.distanceFlat(b, LengthUnit.Kilometer)
			kotlin.math.abs(km - meters / 1000.0) / meters shouldBe io.kotest.matchers.doubles.lt(0.001)
		}
	}

	@Nested
	@DisplayName("Companion distance functions")
	inner class CompanionDistance {
		@Test
		fun `static 2D distance for same point is zero`() {
			Location.distance(50.0, 14.0, 50.0, 14.0, LengthUnit.Meter) shouldBe 0.0
		}

		@Test
		fun `static 3D distance includes altitude`() {
			val flat = Location.distance(50.0, 14.0, 50.0, 14.0, LengthUnit.Meter)
			val with3d = Location.distance(50.0, 14.0, 0.0, 50.0, 14.0, 100.0, LengthUnit.Meter)
			flat shouldBe 0.0
			with3d shouldBe 100.0
		}
	}
}
