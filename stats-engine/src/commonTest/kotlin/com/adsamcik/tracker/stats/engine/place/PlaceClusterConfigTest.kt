package com.adsamcik.tracker.stats.engine.place

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlaceClusterConfigTest {

	@Nested
	inner class Defaults {

		@Test
		fun `default matchRadiusM is 150`() {
			PlaceClusterConfig().matchRadiusM shouldBe 150f
		}

		@Test
		fun `default initialRadiusM is 100`() {
			PlaceClusterConfig().initialRadiusM shouldBe 100f
		}

		@Test
		fun `default minVisitsForStable is 3`() {
			PlaceClusterConfig().minVisitsForStable shouldBe 3
		}

		@Test
		fun `no-arg constructor uses all defaults`() {
			val config = PlaceClusterConfig()
			config shouldBe PlaceClusterConfig(
				matchRadiusM = 150f,
				initialRadiusM = 100f,
				minVisitsForStable = 3,
			)
		}
	}

	@Nested
	inner class CustomValues {

		@Test
		fun `custom matchRadiusM overrides default`() {
			val config = PlaceClusterConfig(matchRadiusM = 300f)
			config.matchRadiusM shouldBe 300f
			config.initialRadiusM shouldBe 100f
			config.minVisitsForStable shouldBe 3
		}

		@Test
		fun `custom initialRadiusM overrides default`() {
			val config = PlaceClusterConfig(initialRadiusM = 50f)
			config.matchRadiusM shouldBe 150f
			config.initialRadiusM shouldBe 50f
			config.minVisitsForStable shouldBe 3
		}

		@Test
		fun `custom minVisitsForStable overrides default`() {
			val config = PlaceClusterConfig(minVisitsForStable = 10)
			config.matchRadiusM shouldBe 150f
			config.initialRadiusM shouldBe 100f
			config.minVisitsForStable shouldBe 10
		}

		@Test
		fun `all custom values override defaults`() {
			val config = PlaceClusterConfig(
				matchRadiusM = 500f,
				initialRadiusM = 200f,
				minVisitsForStable = 7,
			)
			config.matchRadiusM shouldBe 500f
			config.initialRadiusM shouldBe 200f
			config.minVisitsForStable shouldBe 7
		}
	}

	@Nested
	inner class Equality {

		@Test
		fun `two default configs are equal`() {
			PlaceClusterConfig() shouldBe PlaceClusterConfig()
		}

		@Test
		fun `configs with different matchRadiusM are not equal`() {
			val a = PlaceClusterConfig(matchRadiusM = 100f)
			val b = PlaceClusterConfig(matchRadiusM = 200f)
			a shouldNotBe b
		}

		@Test
		fun `configs with different initialRadiusM are not equal`() {
			val a = PlaceClusterConfig(initialRadiusM = 50f)
			val b = PlaceClusterConfig(initialRadiusM = 75f)
			a shouldNotBe b
		}

		@Test
		fun `configs with different minVisitsForStable are not equal`() {
			val a = PlaceClusterConfig(minVisitsForStable = 3)
			val b = PlaceClusterConfig(minVisitsForStable = 5)
			a shouldNotBe b
		}

		@Test
		fun `equal configs have same hashCode`() {
			val a = PlaceClusterConfig(matchRadiusM = 250f, minVisitsForStable = 5)
			val b = PlaceClusterConfig(matchRadiusM = 250f, minVisitsForStable = 5)
			a.hashCode() shouldBe b.hashCode()
		}
	}

	@Nested
	inner class Copy {

		@Test
		fun `copy with updated matchRadiusM preserves other fields`() {
			val original = PlaceClusterConfig(
				matchRadiusM = 100f,
				initialRadiusM = 50f,
				minVisitsForStable = 5,
			)
			val updated = original.copy(matchRadiusM = 200f)

			updated.matchRadiusM shouldBe 200f
			updated.initialRadiusM shouldBe 50f
			updated.minVisitsForStable shouldBe 5
		}
	}

	@Nested
	inner class EdgeCases {

		@Test
		fun `zero matchRadiusM is valid`() {
			val config = PlaceClusterConfig(matchRadiusM = 0f)
			config.matchRadiusM shouldBe 0f
		}

		@Test
		fun `zero initialRadiusM is valid`() {
			val config = PlaceClusterConfig(initialRadiusM = 0f)
			config.initialRadiusM shouldBe 0f
		}

		@Test
		fun `minVisitsForStable of 1 is valid`() {
			val config = PlaceClusterConfig(minVisitsForStable = 1)
			config.minVisitsForStable shouldBe 1
		}

		@Test
		fun `very large matchRadiusM is preserved`() {
			val config = PlaceClusterConfig(matchRadiusM = 100_000f)
			config.matchRadiusM shouldBe 100_000f
		}
	}
}
