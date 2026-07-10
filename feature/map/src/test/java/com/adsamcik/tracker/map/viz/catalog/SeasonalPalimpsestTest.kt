package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity
import com.adsamcik.tracker.map.viz.AggContext
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the Seasonal Palimpsest visualization: the primary-season scalar, the DAO-backed source
 * (bounds -> E7 conversion, entity -> feature mapping) and the [SeasonTileAggregator] (season tinting,
 * skipping season-less cells).
 */
@DisplayName("Seasonal Palimpsest")
class SeasonalPalimpsestTest {

	// Season bits: spring=1, summer=2, autumn=4, winter=8.

	@Nested
	@DisplayName("primarySeasonScalar")
	inner class PrimarySeason {

		@Test
		fun `maps the lowest set season bit to a discrete ramp position`() {
			primarySeasonScalar(0b0001)!! shouldBe (0.0 plusOrMinus 1e-9)   // spring
			primarySeasonScalar(0b0010)!! shouldBe (1.0 / 3.0 plusOrMinus 1e-9) // summer
			primarySeasonScalar(0b0100)!! shouldBe (2.0 / 3.0 plusOrMinus 1e-9) // autumn
			primarySeasonScalar(0b1000)!! shouldBe (1.0 plusOrMinus 1e-9)   // winter
		}

		@Test
		fun `multi-season cell takes its earliest season`() {
			// summer + winter -> summer (earliest in year).
			primarySeasonScalar(0b1010)!! shouldBe (1.0 / 3.0 plusOrMinus 1e-9)
		}

		@Test
		fun `no recorded season yields null`() {
			primarySeasonScalar(0).shouldBeNull()
		}

		@Test
		fun `ramp has exactly four discrete season stops`() {
			SEASONAL_RAMP shouldHaveSize 4
		}
	}

	@Nested
	@DisplayName("explorationCellSource")
	inner class Source {

		private val dao: ExplorationCellDao = mockk()

		private fun cell(latE7: Int, lonE7: Int, season: Int) = ExplorationCellEntity(
			cellToken = "t$latE7$lonE7",
			level = 14,
			quality = 2,
			firstDiscoveredAt = 0L,
			lastVisitedAt = 0L,
			visitCount = 1,
			seasonBitmask = season,
			centerLatE7 = latE7,
			centerLonE7 = lonE7,
			createdAt = 0L,
		)

		@Test
		fun `converts viewport bounds to E7 and maps entities to features`() = runTest {
			val latLo = slot<Int>(); val latHi = slot<Int>(); val lonLo = slot<Int>(); val lonHi = slot<Int>()
			coEvery {
				dao.getCellsInBounds(eq(14), capture(latLo), capture(latHi), capture(lonLo), capture(lonHi), any())
			} returns listOf(cell(latE7 = 500_875_000, lonE7 = 144_213_000, season = 0b0010))

			val bounds = com.adsamcik.tracker.map.data.Bounds(north = 50.1, east = 14.5, south = 50.0, west = 14.4)
			val features = explorationCellSource(dao).load(
				com.adsamcik.tracker.map.viz.VizRequest(0L..Long.MAX_VALUE, bounds),
			)

			latLo.captured shouldBe 500_000_000
			latHi.captured shouldBe 501_000_000
			lonLo.captured shouldBe 144_000_000
			lonHi.captured shouldBe 145_000_000

			features shouldHaveSize 1
			features.single().lat shouldBe (50.0875 plusOrMinus 1e-6)
			features.single().lon shouldBe (14.4213 plusOrMinus 1e-6)
			features.single().seasonBitmask shouldBe 0b0010
		}
	}

	@Nested
	@DisplayName("SeasonTileAggregator")
	inner class Aggregation {

		private fun feature(season: Int) = ExplorationCellFeature(
			lat = 50.0, lon = 14.0, level = 14, seasonBitmask = season, quality = 2,
			firstDiscoveredAt = 0L, visitCount = 1,
		)

		private val ctx = AggContext(zoom = 12f, quality = 1f, maxPoints = 20_000)

		@Test
		fun `builds one square tile per seasoned cell coloured by primary season`() {
			val tiles = SeasonTileAggregator().aggregate(listOf(feature(0b1000)), ctx).tiles
			tiles shouldHaveSize 1
			val t = tiles.single()
			t.weight shouldBe (1.0 plusOrMinus 1e-9) // winter
			// A non-degenerate square around the centre.
			(t.east > t.west) shouldBe true
			(t.north > t.south) shouldBe true
		}

		@Test
		fun `skips cells with no recorded season`() {
			SeasonTileAggregator().aggregate(listOf(feature(0)), ctx).tiles shouldHaveSize 0
		}
	}
}
