package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.*
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import org.junit.Assert.*
import org.junit.Test

class NormalizationNeighborhoodTest {

    private class FakeRepo(var out: List<WeightedGeoFeature> = emptyList()) : GeoRepository {
        override fun query(query: GeoQuery) = throw UnsupportedOperationException()
        override fun queryWeighted(query: GeoQuery, weightColumn: String) = throw UnsupportedOperationException()
        override fun queryWeightedAggregated(
            query: GeoQuery,
            weightColumn: String,
            aggregation: Aggregation,
            cellSizeLatDeg: Double,
            cellSizeLonDeg: Double
        ) = kotlinx.coroutines.flow.flow { emit(out) }
    }

    private fun deltaStamp(): (Int) -> HeatmapStamp = { r ->
        val diameter = r * 2 + 1
        val data = FloatArray(diameter * diameter) { 0f }
        val c = r
        data[c + c * diameter] = 1f
        HeatmapStamp(diameter, diameter, data)
    }

    @Test
    fun returnsNullWhenNoNeighbors() {
        val repo = FakeRepo(emptyList())
        val nn = NormalizationNeighborhood(repo)
        nn.updateConfig(
            NormalizationNeighborhood.Config(
                source = GeoSource.WIFI,
                weightColumn = "level",
                aggregation = Aggregation.Sum,
                ageThresholdSec = 60,
                maxHeat = 100f,
                weightMerge = { cur, _, s, v -> cur + s * v },
                alphaMerge = { cur, s, _ -> cur.coerceIn(0,255) }
            )
        )
        val b = Bounds(3.0, 3.0, 0.0, 0.0)
        val res = nn.computeSaturationOverride(
            tileKey = "z/0/0", zoom = 12, bounds3x3 = b, normSize = 32,
            stampPolicy = NormalizationNeighborhood.NormalizationStampPolicy(
                baseRadiusPxAtTile = 3, metersPerPixelAtTile = 10.0, buildStamp = deltaStamp()
            ),
            weightNormalizer = { it.toFloat().coerceIn(0f, 1f) }
        )
        assertNull(res)
    }

    @Test
    fun sparseNeighborsProducePositiveOverride() {
        val repo = FakeRepo(listOf(WeightedGeoFeature(1.5, 1.5, 1000L, 1.0)))
        val nn = NormalizationNeighborhood(repo)
        nn.updateConfig(
            NormalizationNeighborhood.Config(
                source = GeoSource.WIFI,
                weightColumn = "level",
                aggregation = Aggregation.Sum,
                ageThresholdSec = 60,
                maxHeat = 100f,
                weightMerge = { cur, _, s, v -> cur + s * v },
                alphaMerge = { cur, s, _ -> cur.coerceIn(0,255) }
            )
        )
        val b = Bounds(3.0, 3.0, 0.0, 0.0)
        val res = nn.computeSaturationOverride(
            tileKey = "z/1/1", zoom = 12, bounds3x3 = b, normSize = 64,
            stampPolicy = NormalizationNeighborhood.NormalizationStampPolicy(
                baseRadiusPxAtTile = 2, metersPerPixelAtTile = 200.0, buildStamp = deltaStamp()
            ),
            weightNormalizer = { 1f }
        )
        assertNotNull(res)
        assertTrue(res!! >= 1f)
    }

    @Test
    fun smoothingCachesAcrossCalls() {
        val repo = FakeRepo()
        val nn = NormalizationNeighborhood(repo)
        nn.updateConfig(
            NormalizationNeighborhood.Config(
                source = GeoSource.WIFI,
                weightColumn = "level",
                aggregation = Aggregation.Sum,
                ageThresholdSec = 60,
                maxHeat = 100f,
                weightMerge = { cur, _, s, v -> cur + s * v },
                alphaMerge = { cur, s, _ -> cur.coerceIn(0,255) }
            )
        )
        val b = Bounds(3.0, 3.0, 0.0, 0.0)
        val stamp = NormalizationNeighborhood.NormalizationStampPolicy(
            baseRadiusPxAtTile = 3, metersPerPixelAtTile = 50.0, buildStamp = deltaStamp()
        )
        // High override scenario: many strong points
        repo.out = (0 until 50).map { i ->
            val t = 1000L + i
            WeightedGeoFeature(0.1 + (i % 10) * 0.28, 0.1 + (i / 10) * 0.28, t, 5.0)
        }
        val hi1 = nn.computeSaturationOverride("k/1/1", 12, b, 64, stamp) { it.toFloat() }!!
        // Lower override scenario: single weak point
        repo.out = listOf(WeightedGeoFeature(1.5, 1.5, 2000L, 0.2))
        val loSmoothed = nn.computeSaturationOverride("k/1/1", 12, b, 64, stamp) { it.toFloat() }!!
        val loRaw = nn.computeSaturationOverride("k2/1/1", 12, b, 64, stamp) { it.toFloat() }!!
        // Smoothed value for same key should be pulled toward previous high; thus > raw for new key
        assertTrue(loSmoothed >= loRaw)
        // Also not below 1 due to coerceAtLeast
        assertTrue(loSmoothed >= 1f)
    }

    @Test
    fun highCoverageTypicallyYieldsHigherPercentileThanLow() {
        val repo = FakeRepo()
        val nn = NormalizationNeighborhood(repo)
        nn.updateConfig(
            NormalizationNeighborhood.Config(
                source = GeoSource.WIFI,
                weightColumn = "level",
                aggregation = Aggregation.Sum,
                ageThresholdSec = 60,
                maxHeat = 100f,
                weightMerge = { cur, _, s, v -> cur + s * v },
                alphaMerge = { cur, s, _ -> cur }
            )
        )
        val b = Bounds(3.0, 3.0, 0.0, 0.0)
        val stamp = NormalizationNeighborhood.NormalizationStampPolicy(
            baseRadiusPxAtTile = 1, metersPerPixelAtTile = 1.0, buildStamp = deltaStamp()
        )
        // Low coverage: few points of moderate weight
        repo.out = listOf(
            WeightedGeoFeature(0.5, 0.5, 1000L, 0.3),
            WeightedGeoFeature(2.5, 2.5, 1001L, 0.4)
        )
        val low = nn.computeSaturationOverride("A", 14, b, 64, stamp) { it.toFloat() }!!
        // High coverage: many points across grid with higher weights toward end
        repo.out = (0 until 100).map { i ->
            val x = (i % 10)
            val y = (i / 10)
            val w = 0.2 + (i / 100.0) // 0.2 .. ~1.2
            WeightedGeoFeature(0.1 + x * 0.28, 0.1 + y * 0.28, 2000L + i, w)
        }
        val high = nn.computeSaturationOverride("B", 14, b, 64, stamp) { it.toFloat().coerceIn(0f, 1f) }!!
        assertTrue(high >= low)
    }

    @Test
    fun ageDecayFavorsRecentSamplesOverOld() {
        val repo = FakeRepo()
        val nn = NormalizationNeighborhood(repo)
        nn.updateConfig(
            NormalizationNeighborhood.Config(
                source = GeoSource.WIFI,
                weightColumn = "level",
                aggregation = Aggregation.Sum,
                ageThresholdSec = 60, // strong decay within a minute
                maxHeat = 100f,
                weightMerge = { cur, _, s, v -> cur + s * v },
                alphaMerge = { cur, _, _ -> cur }
            )
        )
        val b = Bounds(3.0, 3.0, 0.0, 0.0)
        val stamp = NormalizationNeighborhood.NormalizationStampPolicy(
            baseRadiusPxAtTile = 1, metersPerPixelAtTile = 1.0, buildStamp = deltaStamp()
        )
        val centerLat = 1.5
        val centerLon = 1.5
        val t0 = 0L
        val t1 = 3_600_000L // +1 hour

        // Case A: very old strong sample, then recent weak sample at same spot
        repo.out = listOf(
            WeightedGeoFeature(centerLon, centerLat, t0, 10.0),
            WeightedGeoFeature(centerLon, centerLat, t1, 1.0)
        )
        val oldStrong_recentWeak = nn.computeSaturationOverride("AGE/A", 14, b, 64, stamp) { it.toFloat() }!!

        // Case B: very old weak sample, then recent strong sample
        repo.out = listOf(
            WeightedGeoFeature(centerLon, centerLat, t0, 1.0),
            WeightedGeoFeature(centerLon, centerLat, t1, 10.0)
        )
        val oldWeak_recentStrong = nn.computeSaturationOverride("AGE/B", 14, b, 64, stamp) { it.toFloat() }!!

        // Recent strong should dominate after decay of the old sample
        assertTrue(oldWeak_recentStrong > oldStrong_recentWeak)
        // Sanity: both are >= 1 due to internal floor
        assertTrue(oldStrong_recentWeak >= 1f && oldWeak_recentStrong >= 1f)
    }
}
