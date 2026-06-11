package com.adsamcik.tracker.stats.engine.place

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlaceClusterTest {

	private fun cluster(
		id: Long = 1L,
		centerLatE7: Int = 407128000,
		centerLonE7: Int = -740060000,
		radiusM: Float = 100f,
		visitCount: Int = 5,
	) = PlaceCluster(
		id = id,
		centerLatE7 = centerLatE7,
		centerLonE7 = centerLonE7,
		radiusM = radiusM,
		visitCount = visitCount,
	)

	@Nested
	inner class Construction {

		@Test
		fun `stores all fields correctly`() {
			val c = cluster(
				id = 42L,
				centerLatE7 = 123456789,
				centerLonE7 = -987654321,
				radiusM = 250f,
				visitCount = 17,
			)

			c.id shouldBe 42L
			c.centerLatE7 shouldBe 123456789
			c.centerLonE7 shouldBe -987654321
			c.radiusM shouldBe 250f
			c.visitCount shouldBe 17
		}

		@Test
		fun `zero visit count is valid`() {
			val c = cluster(visitCount = 0)
			c.visitCount shouldBe 0
		}

		@Test
		fun `zero radius is valid`() {
			val c = cluster(radiusM = 0f)
			c.radiusM shouldBe 0f
		}

		@Test
		fun `negative coordinates are valid`() {
			val c = cluster(centerLatE7 = -339200000, centerLonE7 = -703500000)
			c.centerLatE7 shouldBe -339200000
			c.centerLonE7 shouldBe -703500000
		}
	}

	@Nested
	inner class Equality {

		@Test
		fun `equal clusters have same hashCode`() {
			val a = cluster()
			val b = cluster()
			a shouldBe b
			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `clusters with different id are not equal`() {
			val a = cluster(id = 1L)
			val b = cluster(id = 2L)
			a shouldNotBe b
		}

		@Test
		fun `clusters with different center lat are not equal`() {
			val a = cluster(centerLatE7 = 100)
			val b = cluster(centerLatE7 = 200)
			a shouldNotBe b
		}

		@Test
		fun `clusters with different center lon are not equal`() {
			val a = cluster(centerLonE7 = 100)
			val b = cluster(centerLonE7 = 200)
			a shouldNotBe b
		}

		@Test
		fun `clusters with different radius are not equal`() {
			val a = cluster(radiusM = 50f)
			val b = cluster(radiusM = 150f)
			a shouldNotBe b
		}

		@Test
		fun `clusters with different visit count are not equal`() {
			val a = cluster(visitCount = 3)
			val b = cluster(visitCount = 7)
			a shouldNotBe b
		}
	}

	@Nested
	inner class Copy {

		@Test
		fun `copy with updated visit count preserves other fields`() {
			val original = cluster(id = 10L, visitCount = 5)
			val updated = original.copy(visitCount = 6)

			updated.id shouldBe 10L
			updated.centerLatE7 shouldBe original.centerLatE7
			updated.centerLonE7 shouldBe original.centerLonE7
			updated.radiusM shouldBe original.radiusM
			updated.visitCount shouldBe 6
		}

		@Test
		fun `copy with updated center preserves id and radius`() {
			val original = cluster(id = 7L, radiusM = 200f)
			val updated = original.copy(centerLatE7 = 0, centerLonE7 = 0)

			updated.id shouldBe 7L
			updated.radiusM shouldBe 200f
			updated.centerLatE7 shouldBe 0
			updated.centerLonE7 shouldBe 0
		}

		@Test
		fun `copy with updated radius preserves center`() {
			val original = cluster(centerLatE7 = 500000000, centerLonE7 = -200000000)
			val updated = original.copy(radiusM = 300f)

			updated.centerLatE7 shouldBe 500000000
			updated.centerLonE7 shouldBe -200000000
			updated.radiusM shouldBe 300f
		}
	}

	@Nested
	inner class EdgeCases {

		@Test
		fun `max coordinate values are representable`() {
			val c = cluster(centerLatE7 = Int.MAX_VALUE, centerLonE7 = Int.MIN_VALUE)
			c.centerLatE7 shouldBe Int.MAX_VALUE
			c.centerLonE7 shouldBe Int.MIN_VALUE
		}

		@Test
		fun `large visit count is preserved`() {
			val c = cluster(visitCount = Int.MAX_VALUE)
			c.visitCount shouldBe Int.MAX_VALUE
		}

		@Test
		fun `large radius is preserved`() {
			val c = cluster(radiusM = Float.MAX_VALUE)
			c.radiusM shouldBe Float.MAX_VALUE
		}
	}
}
