package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.*
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
// Removed unused StandardTestDispatcher/TestScope imports; runTest supplies scope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NormalizationNeighborhoodAsyncCoreTest {

    private class FakeRepo(var out: List<WeightedGeoFeature> = emptyList()) : GeoRepository {
        override fun query(query: GeoQuery) = throw UnsupportedOperationException()
        override fun queryWeighted(query: GeoQuery, weightColumn: String) = throw UnsupportedOperationException()
        override fun queryWeightedAggregated(
            query: GeoQuery,
            weightColumn: String,
            aggregation: Aggregation,
            cellSizeLatDeg: Double,
            cellSizeLonDeg: Double
        ) = flow { emit(out) }
    }

    private fun deltaStamp(): (Int) -> HeatmapStamp = { r ->
        val diameter = r * 2 + 1
        val data = FloatArray(diameter * diameter) { 0f }
        val c = r
        data[c + c * diameter] = 1f
        HeatmapStamp(diameter, diameter, data)
    }

    private fun config() = NormalizationNeighborhood.Config(
        source = GeoSource.WIFI,
        weightColumn = "level",
        aggregation = Aggregation.Sum,
        ageThresholdSec = 60,
        maxHeat = 100f,
        weightMerge = { cur, _, s, v -> cur + s * v },
        alphaMerge = { cur, s, _ -> cur }
    )

    @Test
    fun emptyReturnsNullAndNoCache() = runTest {
        val repo = FakeRepo(emptyList())
    val nn = NormalizationNeighborhood(repo, this, {}, StandardTestDispatcher(testScheduler))
        nn.updateConfig(config())
        val b = Bounds(3.0,3.0,0.0,0.0)
        val stamp = NormalizationNeighborhood.NormalizationStampPolicy(3,10.0, deltaStamp())
        val first = nn.ensureAndGetOverride("t/0/0",12,b,32,stamp){ it.toFloat() }
        assertNull(first)
        advanceUntilIdle()
        val second = nn.ensureAndGetOverride("t/0/0",12,b,32,stamp){ it.toFloat() }
        assertNull(second) // still null because state removed on empty
    }

    @Test
    fun sparseNeighborProducesValue() = runTest {
        val repo = FakeRepo(listOf(WeightedGeoFeature(1.5,1.5,1000L,1.0)))
    val nn = NormalizationNeighborhood(repo, this, {}, StandardTestDispatcher(testScheduler))
        nn.updateConfig(config())
        val b = Bounds(3.0,3.0,0.0,0.0)
        val stamp = NormalizationNeighborhood.NormalizationStampPolicy(2,200.0, deltaStamp())
        assertNull(nn.ensureAndGetOverride("t/1/1",12,b,64,stamp){1f})
        advanceUntilIdle()
        val ready = nn.ensureAndGetOverride("t/1/1",12,b,64,stamp){1f}
        assertNotNull(ready)
        assertTrue(ready!! >= 1f)
    }


    @Test
    fun highCoverageHigherThanLow() = runTest {
        val repo = FakeRepo()
    val nn = NormalizationNeighborhood(repo, this, {}, StandardTestDispatcher(testScheduler))
        nn.updateConfig(config())
        val b = Bounds(3.0,3.0,0.0,0.0)
        val stamp = NormalizationNeighborhood.NormalizationStampPolicy(1,1.0, deltaStamp())
        repo.out = listOf(
            WeightedGeoFeature(0.5,0.5,1000L,0.3),
            WeightedGeoFeature(2.5,2.5,1001L,0.4)
        )
        assertNull(nn.ensureAndGetOverride("A",14,b,64,stamp){ it.toFloat() })
        advanceUntilIdle()
        val low = nn.ensureAndGetOverride("A",14,b,64,stamp){ it.toFloat() }!!
        repo.out = (0 until 100).map { i ->
            val x = (i % 10); val y = (i/10); val w = 0.2 + (i/100.0)
            WeightedGeoFeature(0.1 + x*0.28, 0.1 + y*0.28, 2000L + i, w)
        }
        assertNull(nn.ensureAndGetOverride("B",14,b,64,stamp){ it.toFloat().coerceIn(0f,1f) })
        advanceUntilIdle()
        val high = nn.ensureAndGetOverride("B",14,b,64,stamp){ it.toFloat().coerceIn(0f,1f) }!!
        assertTrue(high >= low)
    }

    @Test
    fun ageDecayRecentDominates() = runTest {
        val repo = FakeRepo()
    val nn = NormalizationNeighborhood(repo, this, {}, StandardTestDispatcher(testScheduler))
        nn.updateConfig(config())
        val b = Bounds(3.0,3.0,0.0,0.0)
        val stamp = NormalizationNeighborhood.NormalizationStampPolicy(1,1.0, deltaStamp())
        val t0 = 0L; val t1 = 3_600_000L
        repo.out = listOf(WeightedGeoFeature(1.5,1.5,t0,10.0), WeightedGeoFeature(1.5,1.5,t1,1.0))
        assertNull(nn.ensureAndGetOverride("AGE/A",14,b,64,stamp){ it.toFloat() })
        advanceUntilIdle()
        val a = nn.ensureAndGetOverride("AGE/A",14,b,64,stamp){ it.toFloat() }!!
        repo.out = listOf(WeightedGeoFeature(1.5,1.5,t0,1.0), WeightedGeoFeature(1.5,1.5,t1,10.0))
        assertNull(nn.ensureAndGetOverride("AGE/B",14,b,64,stamp){ it.toFloat() })
        advanceUntilIdle()
        val bVal = nn.ensureAndGetOverride("AGE/B",14,b,64,stamp){ it.toFloat() }!!
        assertTrue(bVal > a)
        assertTrue(a >= 1f && bVal >= 1f)
    }

    @Test
    fun duplicateSuppressionSingleLaunch() = runTest {
        var launches = 0
        val repo = object : GeoRepository {
            override fun query(q: GeoQuery) = throw UnsupportedOperationException()
            override fun queryWeighted(q: GeoQuery, weightColumn: String) = throw UnsupportedOperationException()
            override fun queryWeightedAggregated(q: GeoQuery, weightColumn: String, aggregation: Aggregation, cellSizeLatDeg: Double, cellSizeLonDeg: Double) = flow {
                launches++
                emit(listOf(WeightedGeoFeature(1.5,1.5,1000L,1.0)))
            }
        }
    val nn = NormalizationNeighborhood(repo, this, {}, StandardTestDispatcher(testScheduler))
        nn.updateConfig(config())
        val b = Bounds(3.0,3.0,0.0,0.0)
        val stamp = NormalizationNeighborhood.NormalizationStampPolicy(2,10.0, deltaStamp())
        assertNull(nn.ensureAndGetOverride("dup/1",12,b,32,stamp){1f})
        assertNull(nn.ensureAndGetOverride("dup/1",12,b,32,stamp){1f})
        advanceUntilIdle()
        val ready = nn.ensureAndGetOverride("dup/1",12,b,32,stamp){1f}
        assertNotNull(ready)
        assertEquals(1, launches)
    }
}
