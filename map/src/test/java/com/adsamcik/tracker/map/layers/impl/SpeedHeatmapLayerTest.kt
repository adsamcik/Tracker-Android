package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SpeedHeatmapLayer].
 *
 * Uses a [TestableSpeedHeatmapLayer] wrapper to expose protected methods.
 * Color.rgb() returns 0 in JUnit (no Robolectric), so color stop values
 * are tested structurally, not by exact ARGB value.
 */
@DisplayName("SpeedHeatmapLayer")
class SpeedHeatmapLayerTest {

    /** Wrapper that exposes protected methods for testing. */
    private class TestableSpeedHeatmapLayer(
        repo: GeoRepository,
        perf: PerformanceManager = PerformanceManager()
    ) : SpeedHeatmapLayer(repo, perf) {
        fun testColorStops() = colorStops()
        fun testGeoJsonFrom(processed: String) = geoJsonFrom(processed)
        fun testRadiusPx() = radiusPx()
        fun testIntensity() = intensity()
        suspend fun testLoadData(context: Context) = loadData(context)
        fun testProcessData(
            input: List<WeightedGeoFeature>,
            budgets: PerformanceManager.PerformanceBudgets
        ) = processData(input, budgets)
        fun testProduceConfig(processed: String) = produceConfig(processed)
    }

    private val mockRepo: GeoRepository = mockk()
    private val perf = PerformanceManager()
    private val layer = TestableSpeedHeatmapLayer(mockRepo, perf)

    // ── colorStops ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("colorStops")
    inner class ColorStops {

        @Test
        fun `returns exactly 6 stops`() {
            layer.testColorStops() shouldHaveSize 6
        }

        @Test
        fun `positions span 0 to 1 in ascending order`() {
            val stops = layer.testColorStops()
            stops.first().first shouldBe 0.0f
            stops.last().first shouldBe 1.0f
            for (i in 1 until stops.size) {
                stops[i].first shouldBeGreaterThan stops[i - 1].first
            }
        }

        @Test
        fun `positions are evenly spaced at 0_2 intervals`() {
            val stops = layer.testColorStops()
            for (i in 1 until stops.size) {
                val delta = stops[i].first - stops[i - 1].first
                delta shouldBeGreaterThan 0.19f
                delta shouldBeLessThan 0.21f
            }
        }
    }

    // ── loadData ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("loadData")
    inner class LoadData {

        @Test
        fun `queries weighted data with speed weight`() = runTest {
            val features = listOf(
                WeightedGeoFeature(50.0, 14.0, 1000L, 3.5),
                WeightedGeoFeature(51.0, 15.0, 2000L, 12.0)
            )
            every { mockRepo.queryWeighted(any(), eq("speed")) } returns flowOf(features)

            val ctx: Context = mockk()
            val result = layer.testLoadData(ctx)

            result shouldHaveSize 2
            result[0].weight shouldBe 3.5
            result[1].weight shouldBe 12.0
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
        fun `produces valid GeoJSON with coordinates and weight`() {
            val features = listOf(
                WeightedGeoFeature(50.0, 14.0, 1000L, 7.5)
            )
            val json = layer.testProcessData(features, budgets(1000))
            json shouldContain """"type":"FeatureCollection""""
            json shouldContain "14.0"
            json shouldContain "50.0"
            json shouldContain "7.5"
        }

        @Test
        fun `downsamples when input exceeds maxPoints`() {
            val features = (1..300).map {
                WeightedGeoFeature(50.0 + it * 0.001, 14.0, it.toLong(), 1.0)
            }
            val json = layer.testProcessData(features, budgets(100))
            // step = 300/100 = 3, keeps every 3rd → 100 points
            val count = """"type":"Point"""".toRegex().findAll(json).count()
            count shouldBe 100
        }

        @Test
        fun `no downsampling when within budget`() {
            val features = (1..50).map {
                WeightedGeoFeature(50.0 + it * 0.001, 14.0, it.toLong(), 1.0)
            }
            val json = layer.testProcessData(features, budgets(1000))
            val count = """"type":"Point"""".toRegex().findAll(json).count()
            count shouldBe 50
        }

        @Test
        fun `empty input produces empty features array`() {
            val json = layer.testProcessData(emptyList(), budgets(1000))
            json shouldContain """"features":[]"""
        }
    }

    // ── produceConfig ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("produceConfig")
    inner class ProduceConfig {

        @Test
        fun `returns Heatmap config with 6 color stops and 0_8 opacity`() {
            val geoJson = """{"type":"FeatureCollection","features":[{"type":"Feature"}]}"""
            val config = layer.testProduceConfig(geoJson)
            config.shouldNotBeNull()
            config.shouldBeInstanceOf<MapLibreLayerConfig.Heatmap>()
            val heatmap = config as MapLibreLayerConfig.Heatmap
            heatmap.colorStops shouldHaveSize 6
            heatmap.opacity shouldBe 0.8f
        }

        @Test
        fun `returns null for empty GeoJSON string`() {
            layer.testProduceConfig("").shouldBeNull()
        }
    }

    // ── geoJsonFrom ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("geoJsonFrom")
    inner class GeoJsonFrom {

        @Test
        fun `passthrough returns input unchanged`() {
            val input = """{"type":"FeatureCollection","features":[]}"""
            layer.testGeoJsonFrom(input) shouldBe input
        }
    }

    // ── radius / intensity ───────────────────────────────────────────────────

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
