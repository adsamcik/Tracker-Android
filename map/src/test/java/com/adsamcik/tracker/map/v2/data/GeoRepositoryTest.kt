package com.adsamcik.tracker.map.v2.data

import com.adsamcik.tracker.map.data.Aggregation
import com.adsamcik.tracker.map.data.BasicGeoFeature
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepositoryImpl
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import com.adsamcik.tracker.shared.base.database.entity.GeoFeatureEntity
import com.adsamcik.tracker.shared.base.database.entity.GeoWeightedFeatureEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import androidx.sqlite.db.SupportSQLiteQuery
import org.junit.jupiter.api.Test

class GeoRepositoryTest {

    @Test
    fun `location basic query maps to basic domain`() = runTest {
        val dao = mockk<UnifiedGeoDao>()
        val flow = MutableStateFlow(listOf(GeoFeatureEntity(1.0,2.0,3L)))
        every { dao.queryLocations(any()) } returns flow
        val repo = GeoRepositoryImpl(dao)
        val result = repo.query(GeoQuery(GeoSource.LOCATION)).first()
        result.size shouldBe 1
        val feature = result[0] as BasicGeoFeature
        feature.lat shouldBe 1.0
    }

    @Test
    fun `aggregation sum reduces into fewer cells`() = runTest {
        val dao = mockk<UnifiedGeoDao>()
        val state: MutableStateFlow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(
            listOf(
                GeoWeightedFeatureEntity(0.01,0.01,1L,1.0),
                GeoWeightedFeatureEntity(0.02,0.02,2L,2.0),
                GeoWeightedFeatureEntity(1.0,1.0,3L,3.0)
            )
        )
        every { dao.queryLocationsWeighted(any()) } returns state
        val repo = GeoRepositoryImpl(dao)
        val q = GeoQuery(
            source = GeoSource.LOCATION,
            bounds = Bounds(2.0,2.0,-1.0,-1.0)
        )
        val result = repo.queryWeightedAggregated(q, "speed", Aggregation.Sum, 0.5, 0.5).first()
        // First two points in same 0.5x0.5 cell (approx), third separate => 2 cells
        result.size shouldBe 2
        val combined = result.first { it.weight == 3.0 } // 1 + 2
        combined.weight shouldBe 3.0
    }

    @Test
    fun `wifi weighted query maps to weighted domain`() = runTest {
        val dao = mockk<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(listOf(GeoWeightedFeatureEntity(1.0,2.0,3L, 5.0)))
        every { dao.queryWifiWeighted(any()) } returns flow
        val repo = GeoRepositoryImpl(dao)
        val result = repo.query(GeoQuery(GeoSource.WIFI, weight = "level")).first()
        val feature = result[0] as WeightedGeoFeature
        feature.weight shouldBe 5.0
    }

    @Test
    fun `cell weighted query maps to weighted domain`() = runTest {
        val dao = mockk<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(listOf(GeoWeightedFeatureEntity(5.0,6.0,7L, 9.0)))
        every { dao.queryCellsWeighted(any()) } returns flow
        val repo = GeoRepositoryImpl(dao)
        val result = repo.query(GeoQuery(GeoSource.CELL, weight = "asu")).first()
        val feature = result[0] as WeightedGeoFeature
        feature.weight shouldBe 9.0
    }

    @Test
    fun `queryWeighted forces weight column`() = runTest {
        val dao = mockk<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(listOf(GeoWeightedFeatureEntity(0.0,0.0,0L, 2.5)))
        every { dao.queryLocationsWeighted(any()) } returns flow
        val repo = GeoRepositoryImpl(dao)
        val result = repo.queryWeighted(GeoQuery(GeoSource.LOCATION), weightColumn = "speed").first()
        val feature = result[0]
        feature.weight shouldBe 2.5
    }

    @Test
    fun `aggregation avg`() = runTest {
        val dao = mockk<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(
            listOf(
                GeoWeightedFeatureEntity(0.0,0.0,1L,2.0),
                GeoWeightedFeatureEntity(0.01,0.01,2L,4.0)
            )
        )
        every { dao.queryLocationsWeighted(any()) } returns flow
        val repo = GeoRepositoryImpl(dao)
        val q = GeoQuery(GeoSource.LOCATION, bounds = Bounds(1.0,1.0,-1.0,-1.0))
        val result = repo.queryWeightedAggregated(q, "speed", Aggregation.Avg, 0.5, 0.5).first()
        result.size shouldBe 1
        result[0].weight shouldBe 3.0
    }

    @Test
    fun `aggregation max`() = runTest {
        val dao = mockk<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(
            listOf(
                GeoWeightedFeatureEntity(0.0,0.0,1L,2.0),
                GeoWeightedFeatureEntity(0.01,0.01,2L,4.0),
                GeoWeightedFeatureEntity(0.02,0.02,3L,3.0)
            )
        )
        every { dao.queryLocationsWeighted(any()) } returns flow
        val repo = GeoRepositoryImpl(dao)
        val q = GeoQuery(GeoSource.LOCATION, bounds = Bounds(1.0,1.0,-1.0,-1.0))
        val result = repo.queryWeightedAggregated(q, "speed", Aggregation.Max, 0.5, 0.5).first()
        result.size shouldBe 1
        result[0].weight shouldBe 4.0
    }

    @Test
    fun `aggregation missing bounds throws`() {
        val dao = mockk<UnifiedGeoDao>()
        val flow: Flow<List<GeoWeightedFeatureEntity>> = MutableStateFlow(emptyList())
        every { dao.queryLocationsWeighted(any()) } returns flow
        val repo = GeoRepositoryImpl(dao)
        shouldThrow<IllegalStateException> {
            repo.queryWeightedAggregated(
                GeoQuery(GeoSource.LOCATION),
                weightColumn = "speed",
                aggregation = Aggregation.Sum,
                cellSizeLatDeg = 0.5,
                cellSizeLonDeg = 0.5
            )
        }.message shouldContain "Bounds required"
    }
}
