package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LocationHeatmapLayer].
 *
 * Uses a [TestableLocationHeatmapLayer] wrapper to expose protected methods.
 * Color.BLUE/YELLOW/RED return 0 in JUnit (no Robolectric), so color stop
 * values are tested structurally, not by exact ARGB value.
 */
@DisplayName("LocationHeatmapLayer")
class LocationHeatmapLayerTest {

    /** Wrapper that exposes protected methods for testing. */
    private class TestableLocationHeatmapLayer(
        repo: GeoRepository,
        perf: PerformanceManager = PerformanceManager()
    ) : LocationHeatmapLayer(repo, perf) {
        init { zoom = 17f }
        fun testColorStops() = colorStops()
        fun testGeoJsonFrom(processed: String) = geoJsonFrom(processed)
        fun testRadiusPx() = radiusPx()
        fun testIntensity() = intensity()
        suspend fun testLoadData(context: Context, bounds: Bounds? = null) = loadData(context, bounds)
        fun testProcessData(
            input: List<WeightedGeoFeature>,
            budgets: PerformanceManager.PerformanceBudgets
        ) = processData(input, budgets)
        fun testProduceConfig(processed: String) = produceConfig(processed)
    }

    private val mockRepo: GeoRepository = mockk()
    private val perf = PerformanceManager()
    private val layer = TestableLocationHeatmapLayer(mockRepo, perf)

    /** Extract all numeric "weight" property values from a GeoJSON FeatureCollection string. */
    private fun weightsOf(geoJson: String): List<Double> =
        Regex(""""weight":([0-9.eE+-]+)""").findAll(geoJson)
            .map { it.groupValues[1].toDouble() }
            .toList()

    // ── colorStops ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("colorStops")
    inner class ColorStops {

        @Test
        fun `returns shared density ramp stops`() {
            layer.testColorStops() shouldBe HeatmapColorRamps.LocationDensity
        }

        @Test
        fun `positions span 0 to 1 in ascending order`() {
            val stops = layer.testColorStops()
            stops shouldHaveSize 6
            stops.first().first shouldBe 0.0f
            stops.last().first shouldBe 1.0f
            for (i in 1 until stops.size) {
                (stops[i].first > stops[i - 1].first) shouldBe true
            }
        }
    }

    // ── loadData ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("loadData")
    inner class LoadData {

        @Test
        fun `queries weighted data with hor_acc weight`() = runTest {
            val features = listOf(
                WeightedGeoFeature(50.0, 14.0, 1000L, 5.0),
                WeightedGeoFeature(51.0, 15.0, 2000L, 10.0)
            )
            every { mockRepo.queryWeighted(any(), eq("hor_acc")) } returns flowOf(features)

            val ctx: Context = mockk()
            val result = layer.testLoadData(ctx)

            result shouldHaveSize 2
            result[0].weight shouldBe 0.9
            result[1].weight shouldBe 0.8
        }

        @Test
        fun `passes dateRange as timeFrom and timeTo in query`() = runTest {
            val querySlot = slot<com.adsamcik.tracker.map.data.GeoQuery>()
            every { mockRepo.queryWeighted(capture(querySlot), eq("hor_acc")) } returns flowOf(emptyList())

            layer.dateRange = 1000L..5000L
            val ctx: Context = mockk()
            layer.testLoadData(ctx)

            querySlot.captured.timeFrom shouldBe 1000L
            querySlot.captured.timeTo shouldBe 5000L
        }

        @Test
        fun `all-time dateRange passes null timeFrom and timeTo`() = runTest {
            val querySlot = slot<com.adsamcik.tracker.map.data.GeoQuery>()
            every { mockRepo.queryWeighted(capture(querySlot), eq("hor_acc")) } returns flowOf(emptyList())

            layer.dateRange = 0L..Long.MAX_VALUE
            val ctx: Context = mockk()
            layer.testLoadData(ctx)

            querySlot.captured.timeFrom shouldBe null
            querySlot.captured.timeTo shouldBe null
        }
    }

    // ── SupportsDateRange contract ───────────────────────────────────────────

    @Nested
    @DisplayName("SupportsDateRange")
    inner class DateRangeContract {

        @Test
        fun `implements SupportsDateRange`() {
            layer.shouldBeInstanceOf<com.adsamcik.tracker.map.layers.base.SupportsDateRange>()
        }

        @Test
        fun `default dateRange covers all time`() {
            val freshLayer = TestableLocationHeatmapLayer(mockRepo)
            freshLayer.dateRange shouldBe 0L..Long.MAX_VALUE
        }

        @Test
        fun `dateRange is mutable`() {
            layer.dateRange = 100L..200L
            layer.dateRange shouldBe 100L..200L
        }
    }

    // ── processData ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("processData")
    inner class ProcessData {

        private fun budgets(maxPoints: Int) = PerformanceManager.PerformanceBudgets(
            maxPoints = maxPoints,
            maxPolylinePoints = 1000,
            maxCacheSize = 10,
            tileRenderTimeout = 1000,
            decimationThreshold = 1000,
            batchSize = 100
        )

        @Test
        fun `produces valid GeoJSON FeatureCollection`() {
            val features = listOf(
                WeightedGeoFeature(50.0, 14.0, 1000L, 5.0)
            )
            val json = layer.testProcessData(features, budgets(1000))
            json shouldContain """"type":"FeatureCollection""""
            json shouldContain """"type":"Point""""
            json shouldContain "14.0"
            json shouldContain "50.0"
        }

        @Test
        fun `downsamples when input exceeds maxPoints`() {
            val features = (1..200).map {
                WeightedGeoFeature(50.0 + it * 0.001, 14.0, it.toLong(), 1.0)
            }
            val json = layer.testProcessData(features, budgets(50))
            // step = 200/50 = 4, keeps every 4th → 50 points
            val count = """"type":"Point"""".toRegex().findAll(json).count()
            count shouldBe 50
        }

        @Test
        fun `empty input produces empty features array`() {
            val json = layer.testProcessData(emptyList(), budgets(1000))
            json shouldContain """"features":[]"""
        }

        @Test
        fun `cell weight is absolute and independent of a busier cell elsewhere`() {
            // The previous code normalized by the viewport's max count, so the SAME cell recoloured
            // whenever a busier cell scrolled into view — the "breathing" the user reported. With
            // absolute log-density weighting a cell's weight must depend only on its own count.
            val quietCell = List(5) { WeightedGeoFeature(50.0, 14.0, it.toLong(), 1.0) }
            val busyCell = List(500) { WeightedGeoFeature(60.0, 24.0, it.toLong(), 1.0) }

            val weightAlone = weightsOf(layer.testProcessData(quietCell, budgets(1000))).single()
            val withBusyNeighbour = weightsOf(layer.testProcessData(quietCell + busyCell, budgets(1000)))

            // Same physical cell, same weight regardless of the busy neighbour's presence.
            withBusyNeighbour.min() shouldBe weightAlone
        }

        @Test
        fun `denser cells get hotter weights and sparse cells stay cool`() {
            val sparse = List(2) { WeightedGeoFeature(50.0, 14.0, it.toLong(), 1.0) }
            val dense = List(400) { WeightedGeoFeature(60.0, 24.0, it.toLong(), 1.0) }

            val weights = weightsOf(layer.testProcessData(sparse + dense, budgets(1000))).sorted()
            weights shouldHaveSize 2
            val cool = weights.first()
            val hot = weights.last()
            // A real gradient: the sparse cell is a cool mid-low tone, the dense cell is clearly
            // hotter — not both pinned to the top of the ramp ("all red").
            (cool < 0.5) shouldBe true
            (hot > cool) shouldBe true
        }
    }

    // ── produceConfig ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("produceConfig")
    inner class ProduceConfig {

        @Test
        fun `returns Heatmap config for non-empty GeoJSON`() {
            val geoJson = """{"type":"FeatureCollection","features":[{"type":"Feature"}]}"""
            val config = layer.testProduceConfig(geoJson)
            config.shouldNotBeNull()
            config.shouldBeInstanceOf<MapLibreLayerConfig.Heatmap>()
        }

        @Test
        fun `returns null for empty GeoJSON string`() {
            layer.testProduceConfig("").shouldBeNull()
        }
    }

    // ── radius / intensity defaults ──────────────────────────────────────────

    @Nested
    @DisplayName("radius and intensity")
    inner class RadiusAndIntensity {

        @Test
        fun `radiusPx returns 20f times quality (default 1_0f)`() {
            layer.testRadiusPx() shouldBe 20f
        }

        @Test
        fun `intensity returns quality (default 1_0f)`() {
            layer.testIntensity() shouldBe 1.0f
        }
    }
}
