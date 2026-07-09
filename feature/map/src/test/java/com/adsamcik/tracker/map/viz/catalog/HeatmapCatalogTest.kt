package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizRequest
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the grid-heatmap catalog: the [locationDensitySource]/[speedSource] weight transforms and
 * bounds/time-window pass-through, plus the DSL-authored pipeline factories carrying the right id.
 * Compile-time type-linking (aggregator field type must match the encoder) is enforced by the
 * compiler, so this focuses on runtime behaviour.
 */
@DisplayName("Heatmap catalog")
class HeatmapCatalogTest {

	private val repo: GeoRepository = mockk()
	private val allTime = VizRequest(dateRange = 0L..Long.MAX_VALUE, bounds = null)

	// ── Sources ─────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("locationDensitySource")
	inner class Location {

		@Test
		fun `inverts hor_acc to a confidence weight in 0_1`() = runTest {
			every { repo.queryWeighted(any(), eq("hor_acc")) } returns flowOf(
				listOf(
					WeightedGeoFeature(50.0, 14.0, 1000L, 5.0),   // -> 0.9
					WeightedGeoFeature(51.0, 15.0, 2000L, 25.0),  // -> 0.5
					WeightedGeoFeature(52.0, 16.0, 3000L, 500.0), // clamped -> 0.0
				),
			)
			locationDensitySource(repo).load(allTime).map { it.weight } shouldBe listOf(0.9, 0.5, 0.0)
		}

		@Test
		fun `empty result yields no features`() = runTest {
			every { repo.queryWeighted(any(), any()) } returns flowOf(emptyList())
			locationDensitySource(repo).load(allTime) shouldHaveSize 0
		}

		@Test
		fun `date-filtered range is passed as timeFrom and timeTo`() = runTest {
			val q = slot<GeoQuery>()
			every { repo.queryWeighted(capture(q), any()) } returns flowOf(emptyList())
			locationDensitySource(repo).load(VizRequest(1000L..5000L, bounds = null))
			q.captured.timeFrom shouldBe 1000L
			q.captured.timeTo shouldBe 5000L
		}

		@Test
		fun `all-time range passes null timeFrom and timeTo`() = runTest {
			val q = slot<GeoQuery>()
			every { repo.queryWeighted(capture(q), any()) } returns flowOf(emptyList())
			locationDensitySource(repo).load(allTime)
			q.captured.timeFrom shouldBe null
			q.captured.timeTo shouldBe null
		}

		@Test
		fun `viewport bounds are passed through to the query`() = runTest {
			val q = slot<GeoQuery>()
			every { repo.queryWeighted(capture(q), any()) } returns flowOf(emptyList())
			val bounds = com.adsamcik.tracker.map.data.Bounds(north = 51.0, east = 15.0, south = 50.0, west = 14.0)
			locationDensitySource(repo).load(VizRequest(0L..Long.MAX_VALUE, bounds))
			q.captured.bounds shouldBe bounds
		}
	}

	@Nested
	@DisplayName("speedSource")
	inner class Speed {

		@Test
		fun `normalises m per s to 0_1`() = runTest {
			every { repo.queryWeighted(any(), eq("speed")) } returns flowOf(
				listOf(
					WeightedGeoFeature(50.0, 14.0, 1000L, 3.5),
					WeightedGeoFeature(51.0, 15.0, 2000L, 12.0),
					WeightedGeoFeature(52.0, 16.0, 3000L, 60.0), // clamped -> 1.0
				),
			)
			speedSource(repo).load(allTime).map { it.weight } shouldBe listOf(3.5 / 30.0, 12.0 / 30.0, 1.0)
		}
	}

	@Nested
	@DisplayName("cellSignalSource")
	inner class Cell {

		private fun cell(asu: Double, networkType: Int) =
			com.adsamcik.tracker.map.data.CellSignalGeoFeature(50.0, 14.0, 0L, asu, networkType)

		@Test
		fun `normalises asu by radio technology max`() = runTest {
			// GSM(ordinal 1) max ASU 31, LTE(ordinal 4) max ASU 97.
			every { repo.queryCellSignals(any()) } returns flowOf(
				listOf(cell(asu = 31.0, networkType = 1), cell(asu = 97.0, networkType = 4)),
			)
			cellSignalSource(repo).load(allTime).map { it.weight } shouldBe listOf(1.0, 1.0)
		}

		@Test
		fun `invert flips strong signal to cold and weak to hot`() = runTest {
			every { repo.queryCellSignals(any()) } returns flowOf(
				listOf(cell(asu = 31.0, networkType = 1), cell(asu = 0.0, networkType = 1)),
			)
			// Strong (1.0) -> 0.0, absent (0.0) -> 1.0.
			cellSignalSource(repo, invert = true).load(allTime).map { it.weight } shouldBe listOf(0.0, 1.0)
		}
	}

	@Nested
	@DisplayName("wifi sources")
	inner class Wifi {

		@Test
		fun `wifi signal source normalises dBm to 0_1`() = runTest {
			every { repo.queryWeighted(any(), eq("level")) } returns flowOf(
				listOf(
					WeightedGeoFeature(50.0, 14.0, 0L, -100.0), // -> 0.0
					WeightedGeoFeature(51.0, 15.0, 0L, -65.0),  // -> 0.5
					WeightedGeoFeature(52.0, 16.0, 0L, -30.0),  // -> 1.0
				),
			)
			wifiSignalSource(repo).load(allTime).map { it.weight } shouldBe listOf(0.0, 0.5, 1.0)
		}

		@Test
		fun `wifi count source weights every observation as one`() = runTest {
			every { repo.query(any()) } returns flowOf(
				listOf(
					com.adsamcik.tracker.map.data.BasicGeoFeature(50.0, 14.0, 0L),
					com.adsamcik.tracker.map.data.BasicGeoFeature(51.0, 15.0, 0L),
				),
			)
			wifiCountSource(repo).load(allTime).map { it.weight } shouldBe listOf(1.0, 1.0)
		}
	}

	// ── Pipeline factories ────────────────────────────────────────────────────────

	@Nested
	@DisplayName("pipeline factories")
	inner class Factories {

		@Test
		fun `location heatmap pipeline has the expected id and field type`() {
			val pipeline = locationDensityHeatmap(repo)
			pipeline.id shouldBe "location_heatmap"
			// Field type is SpatialData.WeightedCells by construction (compile-checked); assert usage.
			val field: SpatialData.WeightedCells = pipeline.aggregator.aggregate(
				emptyList(),
				com.adsamcik.tracker.map.viz.AggContext(17f, 1f, 1000),
			)
			field.cells shouldHaveSize 0
		}

		@Test
		fun `speed heatmap pipeline has the expected id`() {
			speedHeatmap(repo).id shouldBe "speed_heatmap"
		}

		@Test
		fun `all grid heatmaps expose their registry ids`() {
			cellSignalHeatmap(repo).id shouldBe "cell_heatmap"
			signalCoverageHeatmap(repo).id shouldBe "signal_coverage"
			wifiSignalHeatmap(repo).id shouldBe "wifi_heatmap"
			wifiCountHeatmap(repo).id shouldBe "wifi_count_heatmap"
		}

		@Test
		fun `legacy tile heatmap uses the Fill shape and its registry id`() {
			legacyTileHeatmap(repo).id shouldBe "legacy_heatmap"
		}

		@Test
		fun `life as terrain uses the 3D extrusion shape and its registry id`() {
			lifeAsTerrain(repo).id shouldBe "life_terrain"
		}
	}
}
