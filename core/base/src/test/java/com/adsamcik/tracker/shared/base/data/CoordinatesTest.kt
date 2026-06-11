package com.adsamcik.tracker.shared.base.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Coordinates - simple lat/lon pair")
class CoordinatesTest {

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores latitude and longitude`() {
			val c = Coordinates(50.0, 14.0)
			c.latitude shouldBe 50.0
			c.longitude shouldBe 14.0
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality`() {
			Coordinates(50.0, 14.0) shouldBe Coordinates(50.0, 14.0)
		}

		@Test
		fun `inequality`() {
			Coordinates(50.0, 14.0) shouldNotBe Coordinates(50.0, 15.0)
		}

		@Test
		fun `hashCode consistent`() {
			Coordinates(1.0, 2.0).hashCode() shouldBe Coordinates(1.0, 2.0).hashCode()
		}

		@Test
		fun `destructuring`() {
			val (lat, lon) = Coordinates(50.0, 14.0)
			lat shouldBe 50.0
			lon shouldBe 14.0
		}

		@Test
		fun `copy with modification`() {
			val c = Coordinates(50.0, 14.0).copy(longitude = 15.0)
			c.latitude shouldBe 50.0
			c.longitude shouldBe 15.0
		}
	}
}
