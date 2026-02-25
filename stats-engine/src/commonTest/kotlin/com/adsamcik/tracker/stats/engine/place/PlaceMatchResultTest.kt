package com.adsamcik.tracker.stats.engine.place

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlaceMatchResultTest {

	private val sampleCluster = PlaceCluster(
		id = 1L,
		centerLatE7 = 407128000,
		centerLonE7 = -740060000,
		radiusM = 100f,
		visitCount = 5,
	)

	@Nested
	inner class MatchedVariant {

		@Test
		fun `Matched holds the provided cluster`() {
			val result = PlaceMatchResult.Matched(sampleCluster)
			result.cluster shouldBe sampleCluster
		}

		@Test
		fun `Matched is a PlaceMatchResult`() {
			val result: PlaceMatchResult = PlaceMatchResult.Matched(sampleCluster)
			result.shouldBeInstanceOf<PlaceMatchResult.Matched>()
		}

		@Test
		fun `two Matched with same cluster are equal`() {
			val a = PlaceMatchResult.Matched(sampleCluster)
			val b = PlaceMatchResult.Matched(sampleCluster)
			a shouldBe b
			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `two Matched with different clusters are not equal`() {
			val other = sampleCluster.copy(id = 99L)
			val a = PlaceMatchResult.Matched(sampleCluster)
			val b = PlaceMatchResult.Matched(other)
			a shouldNotBe b
		}

		@Test
		fun `Matched copy updates cluster`() {
			val original = PlaceMatchResult.Matched(sampleCluster)
			val newCluster = sampleCluster.copy(visitCount = 20)
			val updated = original.copy(cluster = newCluster)

			updated.cluster.visitCount shouldBe 20
			updated.cluster.id shouldBe sampleCluster.id
		}
	}

	@Nested
	inner class NewPlaceVariant {

		@Test
		fun `NewPlace holds provided coordinates`() {
			val result = PlaceMatchResult.NewPlace(latE7 = 123, lonE7 = 456)
			result.latE7 shouldBe 123
			result.lonE7 shouldBe 456
		}

		@Test
		fun `NewPlace is a PlaceMatchResult`() {
			val result: PlaceMatchResult = PlaceMatchResult.NewPlace(latE7 = 0, lonE7 = 0)
			result.shouldBeInstanceOf<PlaceMatchResult.NewPlace>()
		}

		@Test
		fun `two NewPlace with same coordinates are equal`() {
			val a = PlaceMatchResult.NewPlace(latE7 = 100, lonE7 = 200)
			val b = PlaceMatchResult.NewPlace(latE7 = 100, lonE7 = 200)
			a shouldBe b
			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `two NewPlace with different lat are not equal`() {
			val a = PlaceMatchResult.NewPlace(latE7 = 100, lonE7 = 200)
			val b = PlaceMatchResult.NewPlace(latE7 = 999, lonE7 = 200)
			a shouldNotBe b
		}

		@Test
		fun `two NewPlace with different lon are not equal`() {
			val a = PlaceMatchResult.NewPlace(latE7 = 100, lonE7 = 200)
			val b = PlaceMatchResult.NewPlace(latE7 = 100, lonE7 = 999)
			a shouldNotBe b
		}

		@Test
		fun `NewPlace with negative coordinates is valid`() {
			val result = PlaceMatchResult.NewPlace(latE7 = -339200000, lonE7 = -703500000)
			result.latE7 shouldBe -339200000
			result.lonE7 shouldBe -703500000
		}
	}

	@Nested
	inner class SealedClassBehavior {

		@Test
		fun `Matched and NewPlace are not equal`() {
			val matched = PlaceMatchResult.Matched(sampleCluster)
			val newPlace = PlaceMatchResult.NewPlace(
				latE7 = sampleCluster.centerLatE7,
				lonE7 = sampleCluster.centerLonE7,
			)
			(matched as PlaceMatchResult) shouldNotBe (newPlace as PlaceMatchResult)
		}

		@Test
		fun `when expression covers both variants`() {
			val results = listOf<PlaceMatchResult>(
				PlaceMatchResult.Matched(sampleCluster),
				PlaceMatchResult.NewPlace(latE7 = 0, lonE7 = 0),
			)

			val descriptions = results.map { result ->
				when (result) {
					is PlaceMatchResult.Matched -> "matched:${result.cluster.id}"
					is PlaceMatchResult.NewPlace -> "new:${result.latE7},${result.lonE7}"
				}
			}

			descriptions[0] shouldBe "matched:1"
			descriptions[1] shouldBe "new:0,0"
		}
	}
}
