package com.adsamcik.tracker.geocoder.osm

import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.osm.io.PolylineE7Codec
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.math.cos

@DisplayName("OsmStreetResolver")
class OsmStreetResolverTest {

    @Test
    fun `finds roads 45 and 55 metres east and west at 50 and 60 degrees latitude`() = runTest {
        listOf(
            Triple(500_000_000, 45.0, 1),
            Triple(500_000_000, 55.0, -1),
            Triple(600_000_000, 45.0, 1),
            Triple(600_000_000, 55.0, -1),
        ).forEachIndexed { index, (latE7, eastMetres, direction) ->
            val longitudeOffsetE7 = ceil(
                eastMetres / (METRES_PER_E7_DEG * cos(Math.toRadians(latE7 / E7_PER_DEGREE))),
            ).toInt() * direction
            val way = makeWay(
                id = index.toLong(),
                name = "Nearby $eastMetres",
                latsE7 = intArrayOf(latE7 - 1_000, latE7 + 1_000),
                lonsE7 = intArrayOf(longitudeOffsetE7, longitudeOffsetE7),
            )

            resolverReturning(way).nearestRoadName(latE7, 0) shouldBe way.name
        }
    }

    @Test
    fun `includes a road at the 60 metre threshold`() {
        OsmStreetResolver.isWithinSnapThreshold(60.0 * 60.0) shouldBe true
    }

    @Test
    fun `resolves a road across the antimeridian`() = runTest {
        val latE7 = 0
        val queryLonE7 = 1_799_998_000
        val roadLonE7 = -1_799_998_000
        val way = makeWay(
            id = 1L,
            name = "Dateline Road",
            latsE7 = intArrayOf(-1_000, 1_000),
            lonsE7 = intArrayOf(roadLonE7, roadLonE7),
        )
        val roadCell = OsmGridIndex.cellKey(latE7, roadLonE7)

        resolverReturningWhenCellIsRequested(way, roadCell)
            .nearestRoadName(latE7, queryLonE7) shouldBe "Dateline Road"
    }

    @Test
    fun `expands high latitude search beyond the original 3 by 3 grid`() = runTest {
        val latE7 = 890_000_000
        val lonOffsetE7 = 283_000 // Approximately 55 m east at 89°N; two cells away.
        val way = makeWay(
            id = 1L,
            name = "Polar Road",
            latsE7 = intArrayOf(latE7 - 1_000, latE7 + 1_000),
            lonsE7 = intArrayOf(lonOffsetE7, lonOffsetE7),
        )
        val roadCell = OsmGridIndex.cellKey(latE7, lonOffsetE7)

        (roadCell in OsmGridIndex.cellAnd8Neighbors(latE7, 0)) shouldBe false
        resolverReturningWhenCellIsRequested(way, roadCell)
            .nearestRoadName(latE7, 0) shouldBe "Polar Road"
    }

    @Test
    fun `breaks equal distance ties by lowest OSM way id regardless of DAO order`() = runTest {
        val lowerId = makeWay(
            id = 10L,
            name = "Alpha Road",
            latsE7 = intArrayOf(-1_000, 1_000),
            lonsE7 = intArrayOf(2_000, 2_000),
        )
        val higherId = makeWay(
            id = 20L,
            name = "Zulu Road",
            latsE7 = intArrayOf(-1_000, 1_000),
            lonsE7 = intArrayOf(2_000, 2_000),
        )

        resolverReturning(higherId, lowerId).nearestRoadName(0, 0) shouldBe "Alpha Road"
    }

    private fun resolverReturning(vararg ways: OsmWayEntity): OsmStreetResolver {
        val cellDao = mockk<OsmWayCellDao>()
        val wayDao = mockk<OsmWayDao>()
        coEvery { cellDao.findWayIdsInCells(any()) } returns ways.map(OsmWayEntity::id)
        coEvery { wayDao.findByIds(any()) } returns ways.toList()
        return OsmStreetResolver(wayDao, cellDao)
    }

    private fun resolverReturningWhenCellIsRequested(
        way: OsmWayEntity,
        requiredCell: Long,
    ): OsmStreetResolver {
        val cellDao = mockk<OsmWayCellDao>()
        val wayDao = mockk<OsmWayDao>()
        coEvery { cellDao.findWayIdsInCells(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val cellKeys = firstArg<Collection<Long>>()
            if (requiredCell in cellKeys) listOf(way.id) else emptyList()
        }
        coEvery { wayDao.findByIds(any()) } returns listOf(way)
        return OsmStreetResolver(wayDao, cellDao)
    }

    private fun makeWay(
        id: Long,
        name: String,
        latsE7: IntArray,
        lonsE7: IntArray,
    ): OsmWayEntity = OsmWayEntity(
        id = id,
        importId = 1L,
        name = name,
        roadClass = "residential",
        maxspeedKmh = 50,
        maxspeedExplicit = 1,
        isOneway = 0,
        geomPolylineE7 = PolylineE7Codec.encode(latsE7, lonsE7),
        bboxMinLatE7 = latsE7.min(),
        bboxMaxLatE7 = latsE7.max(),
        bboxMinLonE7 = lonsE7.min(),
        bboxMaxLonE7 = lonsE7.max(),
    )

    private companion object {
        const val METRES_PER_E7_DEG = 0.01112
        const val E7_PER_DEGREE = 1e7
    }
}
