package com.adsamcik.tracker.map.viz

import android.content.Context
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.layers.base.LayerReloadResult
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.stats.api.repository.*
import io.mockk.mockk
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RetainedRadioContextLayerTest {
    @Test fun `native context is never passed to the legacy AP or tower aggregator`() = runTest {
        val legacy = WeightedGeoFeature(51.0, 15.0, 1_000, 1.0)
        var aggregated: List<WeightedGeoFeature>? = null
        var requested: RadioLocationContextRequest? = null
        val pipeline = VizPipeline("wifi_heatmap", VizSource { listOf(legacy) },
            Aggregator<WeightedGeoFeature, SpatialData.Markers> { features, _ -> aggregated = features; SpatialData.Markers(features) },
            Encoder<SpatialData.Markers> { _, _ -> null })
        val repository = object : RadioLocationContextRepository {
            override suspend fun read(request: RadioLocationContextRequest): RadioLocationContextReadResult {
                requested = request
                // A valid sibling stays renderable while the other purpose reports its bound.
                return RadioLocationContextReadResult.Ready(RadioContextSource.WIFI, listOf(observation()),
                    setOf(RadioContextReadGap(RadioContextPurpose.AMBIENT, RadioContextReadGapReason.DEPENDENCY_LIMIT)))
            }
        }
        val layer = RetainedRadioContextLayer(pipeline, repository, RadioContextSource.WIFI,
            TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
        layer.dateRange = 500L..1_000L
        layer.enable(mockk<Context>(), 1f).join()
        assertEquals(listOf(legacy), aggregated)
        assertEquals(500L, requested?.fromInclusiveMs)
        assertEquals(1_001L, requested?.toExclusiveMs)
        val composite = assertIs<MapLibreLayerConfig.Composite>(layer.lastConfig)
        assertIs<MapLibreLayerConfig.Fill>(composite.layers.first())
        val marker = assertIs<MapLibreLayerConfig.Circle>(composite.layers.last())
        assertTrue(marker.geoJson.contains("[14.0,50.0]"))
        assertTrue(marker.geoJson.contains("\"accuracyMeters\":1200.0"))
        layer.disable()
    }

    @Test fun `missing context produces no device point while available legacy data stays renderable`() = runTest {
        val legacyConfig = MapLibreLayerConfig.Circle("legacy", listOf(0f to 1, 1f to 1), 1f, 1f)
        val pipeline = VizPipeline("cell_heatmap", VizSource<WeightedGeoFeature> { emptyList() },
            Aggregator<WeightedGeoFeature, SpatialData.Markers> { features, _ -> SpatialData.Markers(features) },
            Encoder<SpatialData.Markers> { _, _ -> legacyConfig })
        var read: RadioLocationContextReadResult = RadioLocationContextReadResult.Ready(RadioContextSource.CELL,
            listOf(observation().copy(signalQuality = RadioContextSignalQuality.Cell(CellHistorySignalQuality(0, 0, 0, 0, 1, 0)),
                location = OptionalRetainedLocationContext.Unlocated(RadioLocationContextGap.NO_COMPATIBLE_LOCATION))))
        val repository = object : RadioLocationContextRepository {
            override suspend fun read(request: RadioLocationContextRequest) = read
        }
        val layer = RetainedRadioContextLayer(pipeline, repository, RadioContextSource.CELL,
            TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
        layer.enable(mockk(), 1f).join()
        assertEquals(legacyConfig, layer.lastConfig)
        read = RadioLocationContextReadResult.DependencyLimit
        assertIs<LayerReloadResult.Success>(layer.reloadData(mockk()))
        assertEquals(legacyConfig, layer.lastConfig)
        read = RadioLocationContextReadResult.StorageUnavailable
        assertIs<LayerReloadResult.Success>(layer.reloadData(mockk()))
        assertEquals(legacyConfig, layer.lastConfig)
        layer.disable()
    }

    @Test fun `unavailable enrichment on first load still renders saved legacy radio data`() = runTest {
        val legacy = WeightedGeoFeature(51.0, 15.0, 1_000, 1.0)
        val legacyConfig = MapLibreLayerConfig.Circle("legacy", listOf(0f to 1, 1f to 1), 1f, 1f)
        var loaded = false
        var aggregated: List<WeightedGeoFeature>? = null
        val pipeline = VizPipeline("wifi_heatmap", VizSource { loaded = true; listOf(legacy) },
            Aggregator<WeightedGeoFeature, SpatialData.Markers> { features, _ ->
                aggregated = features; SpatialData.Markers(features)
            }, Encoder<SpatialData.Markers> { _, _ -> legacyConfig })
        val repository = object : RadioLocationContextRepository {
            override suspend fun read(request: RadioLocationContextRequest) =
                RadioLocationContextReadResult.DependencyLimit
        }
        val layer = RetainedRadioContextLayer(pipeline, repository, RadioContextSource.WIFI,
            TestDispatchersProvider(StandardTestDispatcher(testScheduler)))

        layer.enable(mockk(), 1f).join()

        assertTrue(loaded)
        assertEquals(listOf(legacy), aggregated)
        assertEquals(legacyConfig, layer.lastConfig)
        layer.disable()
    }

    @Test fun `failed enrichment removes old device points while preserving the current legacy layer`() = runTest {
        val legacyConfig = MapLibreLayerConfig.Circle("legacy", listOf(0f to 1, 1f to 1), 1f, 1f)
        val pipeline = VizPipeline("wifi_heatmap", VizSource<WeightedGeoFeature> { emptyList() },
            Aggregator<WeightedGeoFeature, SpatialData.Markers> { features, _ -> SpatialData.Markers(features) },
            Encoder<SpatialData.Markers> { _, _ -> legacyConfig })
        var fail = false
        val repository = object : RadioLocationContextRepository {
            override suspend fun read(request: RadioLocationContextRequest): RadioLocationContextReadResult {
                if (fail) throw IllegalStateException("Local read failed")
                return RadioLocationContextReadResult.Ready(RadioContextSource.WIFI, listOf(observation()))
            }
        }
        val layer = RetainedRadioContextLayer(pipeline, repository, RadioContextSource.WIFI,
            TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
        layer.enable(mockk(), 1f).join()
        assertIs<MapLibreLayerConfig.Composite>(layer.lastConfig)

        fail = true
        assertIs<LayerReloadResult.Success>(layer.reloadData(mockk()))

        assertEquals(legacyConfig, layer.lastConfig)
        layer.disable()
    }

    @Test fun `valid retained device points survive an unavailable legacy radio reader`() = runTest {
        val pipeline = VizPipeline("wifi_heatmap", VizSource<WeightedGeoFeature> {
            throw IllegalStateException("Legacy read failed")
        }, Aggregator<WeightedGeoFeature, SpatialData.Markers> { features, _ -> SpatialData.Markers(features) },
            Encoder<SpatialData.Markers> { _, _ -> null })
        val repository = object : RadioLocationContextRepository {
            override suspend fun read(request: RadioLocationContextRequest) =
                RadioLocationContextReadResult.Ready(RadioContextSource.WIFI, listOf(observation()))
        }
        val layer = RetainedRadioContextLayer(pipeline, repository, RadioContextSource.WIFI,
            TestDispatchersProvider(StandardTestDispatcher(testScheduler)))

        layer.enable(mockk(), 1f).join()

        val config = assertIs<MapLibreLayerConfig.Composite>(layer.lastConfig)
        assertTrue(assertIs<MapLibreLayerConfig.Circle>(config.layers.last()).geoJson.contains("[14.0,50.0]"))
        layer.disable()
    }

    @Test fun `cancelling the context read never falls back to an independent legacy load`() = runTest {
        var legacyLoads = 0
        val pipeline = VizPipeline("wifi_heatmap", VizSource<WeightedGeoFeature> { legacyLoads++; emptyList() },
            Aggregator<WeightedGeoFeature, SpatialData.Markers> { features, _ -> SpatialData.Markers(features) },
            Encoder<SpatialData.Markers> { _, _ -> null })
        var cancel = false
        val repository = object : RadioLocationContextRepository {
            override suspend fun read(request: RadioLocationContextRequest): RadioLocationContextReadResult {
                if (cancel) throw CancellationException("Screen closed")
                return RadioLocationContextReadResult.Ready(RadioContextSource.WIFI, emptyList())
            }
        }
        val layer = RetainedRadioContextLayer(pipeline, repository, RadioContextSource.WIFI,
            TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
        layer.enable(mockk(), 1f).join()
        cancel = true

        assertEquals(1, legacyLoads)
        assertFailsWith<CancellationException> { layer.reloadData(mockk()) }
        assertEquals(1, legacyLoads)

        layer.disable()
    }

    @Test fun `both unavailable radio readers remain a load failure rather than an empty recording`() = runTest {
        var fail = false
        val pipeline = VizPipeline("wifi_heatmap", VizSource<WeightedGeoFeature> {
            if (fail) throw IllegalStateException("Legacy read failed")
            emptyList()
        }, Aggregator<WeightedGeoFeature, SpatialData.Markers> { features, _ -> SpatialData.Markers(features) },
            Encoder<SpatialData.Markers> { _, _ -> null })
        val repository = object : RadioLocationContextRepository {
            override suspend fun read(request: RadioLocationContextRequest): RadioLocationContextReadResult =
                if (fail) RadioLocationContextReadResult.StorageUnavailable
                else RadioLocationContextReadResult.Ready(RadioContextSource.WIFI, emptyList())
        }
        val layer = RetainedRadioContextLayer(pipeline, repository, RadioContextSource.WIFI,
            TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
        layer.enable(mockk(), 1f).join()
        fail = true

        val result = assertIs<LayerReloadResult.Failure>(layer.reloadData(mockk()))
        assertEquals("Legacy read failed", result.cause.message)

        layer.disable()
    }

    @Test fun `same Location endpoint shared across independent purposes renders once`() {
        val observations = listOf(observation(), observation().copy(purpose = RadioContextPurpose.AMBIENT))
        val config = assertIs<MapLibreLayerConfig.Composite>(RetainedRadioContextEncoder.encode(observations))
        val markers = assertIs<MapLibreLayerConfig.Circle>(config.layers.last())
        assertEquals(1, Regex("\"type\":\"Point\"").findAll(markers.geoJson).count())
        assertFalse(markers.geoJson.contains("bssid"))
        assertFalse(markers.geoJson.contains("tower"))
    }

    @Test fun `accuracy circle splits across date line and preserves a coarse fix`() {
        val point = located().copy(longitudeE7 = 1_799_990_000, horizontalAccuracyMeters = 20_000f)
        val rings = RetainedRadioContextEncoder.accuracyRings(point)
        assertEquals(2, rings.size)
        rings.forEach { ring ->
            assertEquals(ring.first(), ring.last())
            assertTrue(ring.all { it.first in -180.0..180.0 && it.second in -90.0..90.0 })
            assertTrue(ring.zipWithNext().all { (left, right) -> kotlin.math.abs(left.first - right.first) <= 180.0 })
        }
        assertTrue(RetainedRadioContextEncoder.accuracyRings(point.copy(horizontalAccuracyMeters = 1_200f)).isNotEmpty())
    }

    @Test fun `zero and polar accuracy remain finite and bounded`() {
        assertTrue(RetainedRadioContextEncoder.accuracyRings(located().copy(horizontalAccuracyMeters = 0f)).isEmpty())
        val rings = RetainedRadioContextEncoder.accuracyRings(located().copy(latitudeE7 = 899_999_000,
            horizontalAccuracyMeters = 1_200f))
        assertTrue(rings.isNotEmpty())
        assertTrue(rings.flatten().all { it.first.isFinite() && it.second.isFinite() })
    }

    private fun located() = RetainedLocationContext("location", 500_000_000, 140_000_000, 1_000, 20, 1_200f, 0)
    private fun observation() = RadioContextObservation("radio", RadioContextPurpose.CAPTURE, 1_000, 1_000, 10,
        RadioContextSignalQuality.Wifi(WifiHistorySignalQuality(-40, -40, -40.0, 1)),
        OptionalRetainedLocationContext.Located(located()))
}
