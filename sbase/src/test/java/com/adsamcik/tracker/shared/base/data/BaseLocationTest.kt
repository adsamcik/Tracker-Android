package com.adsamcik.tracker.shared.base.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("BaseLocation - simple lat/lon/alt data class")
class BaseLocationTest {

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores latitude and longitude`() {
			val loc = BaseLocation(50.0, 14.0)
			loc.latitude shouldBe 50.0
			loc.longitude shouldBe 14.0
		}

		@Test
		fun `altitude defaults to null`() {
			val loc = BaseLocation(50.0, 14.0)
			loc.altitude shouldBe null
		}

		@Test
		fun `altitude can be set`() {
			val loc = BaseLocation(50.0, 14.0, 300.0)
			loc.altitude shouldBe 300.0
		}

		@Test
		fun `copy from Location preserves values`() {
			val source = Location(100L, 50.0, 14.0, 300.0, null, null, null, null)
			val base = BaseLocation(source)
			base.latitude shouldBe 50.0
			base.longitude shouldBe 14.0
			base.altitude shouldBe 300.0
		}
	}

	@Nested
	@DisplayName("isValid property")
	inner class IsValid {
		@Test
		fun `valid coordinates are valid`() {
			BaseLocation(50.0, 14.0).isValid shouldBe true
		}

		@Test
		fun `boundary latitude 90 is valid`() {
			BaseLocation(90.0, 0.0).isValid shouldBe true
		}

		@Test
		fun `boundary latitude minus 90 is valid`() {
			BaseLocation(-90.0, 0.0).isValid shouldBe true
		}

		@Test
		fun `boundary longitude 180 is valid`() {
			BaseLocation(0.0, 180.0).isValid shouldBe true
		}

		@Test
		fun `boundary longitude minus 180 is valid`() {
			BaseLocation(0.0, -180.0).isValid shouldBe true
		}

		@Test
		fun `origin is valid`() {
			BaseLocation(0.0, 0.0).isValid shouldBe true
		}

		@Test
		fun `latitude over 90 is invalid`() {
			BaseLocation(90.1, 0.0).isValid shouldBe false
		}

		@Test
		fun `latitude under minus 90 is invalid`() {
			BaseLocation(-90.1, 0.0).isValid shouldBe false
		}

		@Test
		fun `longitude over 180 is invalid`() {
			BaseLocation(0.0, 180.1).isValid shouldBe false
		}

		@Test
		fun `longitude under minus 180 is invalid`() {
			BaseLocation(0.0, -180.1).isValid shouldBe false
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality`() {
			BaseLocation(50.0, 14.0, 100.0) shouldBe BaseLocation(50.0, 14.0, 100.0)
		}

		@Test
		fun `inequality`() {
			BaseLocation(50.0, 14.0) shouldNotBe BaseLocation(51.0, 14.0)
		}

		@Test
		fun `hashCode consistent`() {
			BaseLocation(50.0, 14.0).hashCode() shouldBe BaseLocation(50.0, 14.0).hashCode()
		}
	}
}
