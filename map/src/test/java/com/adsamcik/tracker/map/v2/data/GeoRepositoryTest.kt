package com.adsamcik.tracker.map.v2.data

import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import com.adsamcik.tracker.shared.base.database.entity.GeoFeatureEntity
import com.adsamcik.tracker.shared.base.database.entity.GeoWeightedFeatureEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import androidx.sqlite.db.SupportSQLiteQuery

class GeoRepositoryTest {

    @Test
    fun `location basic query maps to basic domain`() = runBlocking {
        val dao = mock<UnifiedGeoDao>()
        val flow = MutableStateFlow(listOf(GeoFeatureEntity(1.0,2.0,3L)))
        whenever(dao.queryLocations(any())).thenReturn(flow)
        val repo = GeoRepositoryImpl(dao)
        val result = repo.query(GeoQuery(GeoSource.LOCATION)).first()
        assertEquals(1, result.size)
        val feature = result[0] as BasicGeoFeature
        assertEquals(1.0, feature.lat, 0.0)
    }

    @Test
    fun `aggregation sum reduces into fewer cells`() = runBlocking {
        val dao = mock<UnifiedGeoDao>()
        val state: MutableStateFlow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(
            listOf(
                GeoWeightedFeatureEntity(0.01,0.01,1L,1.0),
                GeoWeightedFeatureEntity(0.02,0.02,2L,2.0),
                GeoWeightedFeatureEntity(1.0,1.0,3L,3.0)
            )
        )
        whenever(dao.queryLocationsWeighted(any())).thenReturn(state)
        val repo = GeoRepositoryImpl(dao)
        val q = GeoQuery(
            source = GeoSource.LOCATION,
            bounds = Bounds(2.0,2.0,-1.0,-1.0)
        )
        val result = repo.queryWeightedAggregated(q, "speed", Aggregation.Sum, 0.5, 0.5).first()
        // First two points in same 0.5x0.5 cell (approx), third separate => 2 cells
        assertEquals(2, result.size)
        val combined = result.first { it.weight == 3.0 } // 1 + 2
        assertEquals(3.0, combined.weight, 0.0)
    }

    @Test
    fun `wifi weighted query maps to weighted domain`() = runBlocking {
        val dao = mock<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(listOf(GeoWeightedFeatureEntity(1.0,2.0,3L, 5.0)))
        whenever(dao.queryWifiWeighted(any())).thenReturn(flow)
        val repo = GeoRepositoryImpl(dao)
        val result = repo.query(GeoQuery(GeoSource.WIFI, weight = "level")).first()
        val feature = result[0] as WeightedGeoFeature
        assertEquals(5.0, feature.weight, 0.0)
    }

    @Test
    fun `cell weighted query maps to weighted domain`() = runBlocking {
        val dao = mock<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(listOf(GeoWeightedFeatureEntity(5.0,6.0,7L, 9.0)))
        whenever(dao.queryCellsWeighted(any())).thenReturn(flow)
        val repo = GeoRepositoryImpl(dao)
        val result = repo.query(GeoQuery(GeoSource.CELL, weight = "asu")).first()
        val feature = result[0] as WeightedGeoFeature
        assertEquals(9.0, feature.weight, 0.0)
    }

    @Test
    fun `queryWeighted forces weight column`() = runBlocking {
        val dao = mock<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(listOf(GeoWeightedFeatureEntity(0.0,0.0,0L, 2.5)))
        whenever(dao.queryLocationsWeighted(any())).thenReturn(flow)
        val repo = GeoRepositoryImpl(dao)
        val result = repo.queryWeighted(GeoQuery(GeoSource.LOCATION), weightColumn = "speed").first()
        val feature = result[0]
        assertEquals(2.5, feature.weight, 0.0)
    }

    @Test
    fun `aggregation avg`() = runBlocking {
        val dao = mock<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(
            listOf(
                GeoWeightedFeatureEntity(0.0,0.0,1L,2.0),
                GeoWeightedFeatureEntity(0.01,0.01,2L,4.0)
            )
        )
        whenever(dao.queryLocationsWeighted(any())).thenReturn(flow)
        val repo = GeoRepositoryImpl(dao)
        val q = GeoQuery(GeoSource.LOCATION, bounds = Bounds(1.0,1.0,-1.0,-1.0))
        val result = repo.queryWeightedAggregated(q, "speed", Aggregation.Avg, 0.5, 0.5).first()
        assertEquals(1, result.size)
        assertEquals(3.0, result[0].weight, 0.0)
    }

    @Test
    fun `aggregation max`() = runBlocking {
        val dao = mock<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(
            listOf(
                GeoWeightedFeatureEntity(0.0,0.0,1L,2.0),
                GeoWeightedFeatureEntity(0.01,0.01,2L,4.0),
                GeoWeightedFeatureEntity(0.02,0.02,3L,3.0)
            )
        )
        whenever(dao.queryLocationsWeighted(any())).thenReturn(flow)
        val repo = GeoRepositoryImpl(dao)
        val q = GeoQuery(GeoSource.LOCATION, bounds = Bounds(1.0,1.0,-1.0,-1.0))
        val result = repo.queryWeightedAggregated(q, "speed", Aggregation.Max, 0.5, 0.5).first()
        assertEquals(1, result.size)
        assertEquals(4.0, result[0].weight, 0.0)
    }

    @Test
    fun `aggregation missing bounds throws`() {
        val dao = mock<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(emptyList())
        whenever(dao.queryLocationsWeighted(any())).thenReturn(flow)
        val repo = GeoRepositoryImpl(dao)
        try {
            repo.queryWeightedAggregated(
                GeoQuery(GeoSource.LOCATION),
                weightColumn = "speed",
                aggregation = Aggregation.Sum,
                cellSizeLatDeg = 0.5,
                cellSizeLonDeg = 0.5
            )
            fail("Expected exception for missing bounds")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Bounds required"))
        }
    }
}
