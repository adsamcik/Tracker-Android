package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TiledDataSourceTest {

    private lateinit var repo: GeoRepository
    private lateinit var cache: TileCache
    private lateinit var dataSource: TiledDataSource

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = TestDispatchersProvider(testDispatcher)

    private fun makePoints(
        count: Int,
        latBase: Double = 48.85,
        lonBase: Double = 2.34,
    ): List<WeightedGeoFeature> = (0 until count).map { i ->
        WeightedGeoFeature(
            lat = latBase + i * 0.001,
            lon = lonBase + i * 0.001,
            time = 1000L + i,
            weight = i.toDouble() / count,
        )
    }

    @BeforeEach
    fun setup() {
        repo = mockk()
        cache = TileCache(maxSize = 64)
        dataSource = TiledDataSource(repo, cache, dispatchers)

        // Default mock: return empty
        coEvery { repo.queryWeighted(any(), any()) } returns flowOf(emptyList())
    }

    @Nested
    inner class GetTile {

        @Test
        fun `returns GeoJSON FeatureCollection for tile with data`() = runTest(testDispatcher) {
            val points = makePoints(5, latBase = 48.855, lonBase = 2.345)
            coEvery { repo.queryWeighted(any(), eq("hor_acc")) } returns flowOf(points)

            val result = dataSource.getTile(
                layerId = "heatmap",
                z = 14,
                x = 8293,
                y = 5632,
                source = GeoSource.LOCATION,
                weightColumn = "hor_acc",
            )

            result shouldContain "FeatureCollection"
            result shouldContain "Feature"
        }

        @Test
        fun `returns empty FeatureCollection for tile without data`() = runTest(testDispatcher) {
            coEvery { repo.queryWeighted(any(), any()) } returns flowOf(emptyList())

            val result = dataSource.getTile(
                layerId = "heatmap",
                z = 14,
                x = 0,
                y = 0,
                source = GeoSource.LOCATION,
                weightColumn = "hor_acc",
            )

            result shouldContain "FeatureCollection"
            result shouldContain "features"
            result shouldNotContain "Point" // No actual point features
        }

        @Test
        fun `cache hit returns cached data`() = runTest(testDispatcher) {
            val points = makePoints(3, latBase = 48.855, lonBase = 2.345)
            coEvery { repo.queryWeighted(any(), eq("hor_acc")) } returns flowOf(points)

            // First call populates cache
            val first = dataSource.getTile(
                "heatmap", 14, 8293, 5632, GeoSource.LOCATION, "hor_acc"
            )

            // Change mock to return different data
            coEvery { repo.queryWeighted(any(), eq("hor_acc")) } returns flowOf(emptyList())

            // Second call should return cached result
            val second = dataSource.getTile(
                "heatmap", 14, 8293, 5632, GeoSource.LOCATION, "hor_acc"
            )

            first shouldBe second
        }

        @Test
        fun `invalidation causes cache miss`() = runTest(testDispatcher) {
            val points = makePoints(3, latBase = 48.855, lonBase = 2.345)
            coEvery { repo.queryWeighted(any(), eq("hor_acc")) } returns flowOf(points)

            dataSource.getTile("heatmap", 14, 8293, 5632, GeoSource.LOCATION, "hor_acc")
            dataSource.invalidate("heatmap")

            coEvery { repo.queryWeighted(any(), eq("hor_acc")) } returns flowOf(emptyList())

            val afterInvalidate = dataSource.getTile(
                "heatmap", 14, 8293, 5632, GeoSource.LOCATION, "hor_acc"
            )
            afterInvalidate shouldNotContain "Point"
        }
    }

    @Nested
    inner class GetVisibleTilesGeoJson {

        @Test
        fun `produces merged GeoJSON for viewport`() = runTest(testDispatcher) {
            val points = makePoints(10, latBase = 48.855, lonBase = 2.345)
            coEvery { repo.queryWeighted(any(), eq("hor_acc")) } returns flowOf(points)

            val result = dataSource.getVisibleTilesGeoJson(
                viewportBounds = Bounds(
                    north = 48.88,
                    south = 48.84,
                    east = 2.38,
                    west = 2.32,
                ),
                zoom = 14f,
                source = GeoSource.LOCATION,
                weightColumn = "hor_acc",
                layerId = "heatmap",
            )

            result shouldContain "FeatureCollection"
        }

        @Test
        fun `invalidateAll clears all cached data`() = runTest(testDispatcher) {
            val points = makePoints(5, latBase = 48.855, lonBase = 2.345)
            coEvery { repo.queryWeighted(any(), eq("hor_acc")) } returns flowOf(points)

            dataSource.getTile("heatmap", 14, 8293, 5632, GeoSource.LOCATION, "hor_acc")
            dataSource.getTile("speed", 14, 8293, 5632, GeoSource.LOCATION, "speed")

            cache.size() shouldBe 2
            dataSource.invalidateAll()
            cache.size() shouldBe 0
        }

        @Test
        fun `second call still returns data (cache does not drop tiles)`() = runTest(testDispatcher) {
            val points = makePoints(5, latBase = 48.855, lonBase = 2.345)
            coEvery { repo.queryWeighted(any(), eq("hor_acc")) } returns flowOf(points)

            val viewport = Bounds(
                north = 48.88,
                south = 48.84,
                east = 2.38,
                west = 2.32,
            )

            val first = dataSource.getVisibleTilesGeoJson(
                viewportBounds = viewport,
                zoom = 14f,
                source = GeoSource.LOCATION,
                weightColumn = "hor_acc",
                layerId = "heatmap",
            )

            // Second call should also return data (regression: cache-hit path
            // previously returned null causing tiles to silently vanish).
            val second = dataSource.getVisibleTilesGeoJson(
                viewportBounds = viewport,
                zoom = 14f,
                source = GeoSource.LOCATION,
                weightColumn = "hor_acc",
                layerId = "heatmap",
            )

            first shouldContain "Feature"
            second shouldContain "Feature"
        }
    }
}
