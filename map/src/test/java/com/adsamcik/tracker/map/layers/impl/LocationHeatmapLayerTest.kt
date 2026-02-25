package com.adsamcik.tracker.map.layers.impl

import android.content.Context
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
    private val layer = TestableLocationHeatmapLayer(mockRepo, perf)

    // ── colorStops ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("colorStops")
    inner class ColorStops {

        @Test
        fun `returns exactly 3 stops`() {
            layer.testColorStops() shouldHaveSize 3
        }

        @Test
        fun `positions are 0, 0_5, 1`() {
            val stops = layer.testColorStops()
            stops[0].first shouldBe 0.0f
            stops[1].first shouldBe 0.5f
            stops[2].first shouldBe 1.0f
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
            result[0].weight shouldBe 5.0
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
