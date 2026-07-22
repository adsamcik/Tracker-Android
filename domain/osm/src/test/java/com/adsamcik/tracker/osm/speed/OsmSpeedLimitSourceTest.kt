package com.adsamcik.tracker.osm.speed

import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.osm.io.PolylineE7Codec
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import com.adsamcik.tracker.shared.model.geo.CheckedCoordinateE7
import com.adsamcik.tracker.shared.model.geo.ConservativeRadiusBounds
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [OsmSpeedLimitSource]. No Room, no Hilt — DAOs are
 * mocked with MockK and we hand-craft tiny [OsmWayEntity] rows.
 */
@DisplayName("OsmSpeedLimitSource")
class OsmSpeedLimitSourceTest {

	private fun makeWay(
		id: Long,
		latsE7: IntArray,
		lonsE7: IntArray,
		maxKmh: Int = 50,
		bboxMinLonE7: Int = lonsE7.min(),
		bboxMaxLonE7: Int = lonsE7.max(),
	): OsmWayEntity {
		val polyline = PolylineE7Codec.encode(latsE7, lonsE7)
		return OsmWayEntity(
			id = id,
			importId = 1L,
			name = "test-way-$id",
			roadClass = "residential",
			maxspeedKmh = maxKmh,
			maxspeedExplicit = 1,
			isOneway = 0,
			geomPolylineE7 = polyline,
			bboxMinLatE7 = latsE7.min(),
			bboxMaxLatE7 = latsE7.max(),
			bboxMinLonE7 = bboxMinLonE7,
			bboxMaxLonE7 = bboxMaxLonE7,
		)
	}

	private fun makeDatelineCrossingWay(
		id: Long,
		latsE7: IntArray,
		lonsE7: IntArray,
		maxKmh: Int = 50,
	): OsmWayEntity = makeWay(
		id = id,
		latsE7 = latsE7,
		lonsE7 = lonsE7,
		maxKmh = maxKmh,
		// The crossing form accepted by the grid/reindex path: east edge first,
		// west edge second, so min > max instead of a nearly-global raw bbox.
		bboxMinLonE7 = EAST_OF_DATELINE_E7,
		bboxMaxLonE7 = WEST_OF_DATELINE_E7,
	)

	@Test
	fun `finds an antimeridian crossing at equivalent boundary longitudes`() = runTest {
		val crossingWay = makeDatelineCrossingWay(
			id = 1L,
			latsE7 = intArrayOf(0, 10_000),
			lonsE7 = intArrayOf(EAST_OF_DATELINE_E7, WEST_OF_DATELINE_E7),
			maxKmh = 80,
		)
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		val datelineCell = OsmGridIndex.cellKey(5_000, WEST_DATELINE_E7)
		coEvery { cellDao.findWayIdsInCells(any()) } answers {
			if (firstArg<Collection<Long>>().contains(datelineCell)) listOf(crossingWay.id) else emptyList()
		}
		coEvery { wayDao.findByIds(any()) } returns listOf(crossingWay)

		val source = OsmSpeedLimitSource(wayDao, cellDao)
		val expected = 80.0 / 3.6
		source.findRoadLimitMps(5_000, WEST_DATELINE_E7)!!.shouldBeBetween(expected, expected, 1e-6)
		source.findRoadLimitMps(5_000, EAST_DATELINE_E7)!!.shouldBeBetween(expected, expected, 1e-6)
	}

	@Test
	fun `finds the same antimeridian geometry with reversed vertices`() = runTest {
		val crossingWay = makeDatelineCrossingWay(
			id = 1L,
			latsE7 = intArrayOf(10_000, 0),
			lonsE7 = intArrayOf(WEST_OF_DATELINE_E7, EAST_OF_DATELINE_E7),
			maxKmh = 80,
		)
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } returns listOf(crossingWay.id)
		coEvery { wayDao.findByIds(any()) } returns listOf(crossingWay)

		val limit = OsmSpeedLimitSource(wayDao, cellDao)
			.findRoadLimitMps(5_000, WEST_DATELINE_E7)
		val expected = 80.0 / 3.6
		limit!!.shouldBeBetween(expected, expected, 1e-6)
	}

	@Test
	fun `does not reinterpret an ordered wide bbox as an antimeridian complement`() = runTest {
		val crossingWay = makeWay(
			id = 1L,
			latsE7 = intArrayOf(0, 10_000),
			lonsE7 = intArrayOf(EAST_OF_DATELINE_E7, WEST_OF_DATELINE_E7),
			maxKmh = 80,
			// Simulate a pre-contract development row with raw numeric extrema.
			// Consumers must not guess that it means the narrow complement.
			bboxMinLonE7 = WEST_OF_DATELINE_E7,
			bboxMaxLonE7 = EAST_OF_DATELINE_E7,
		)
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } returns listOf(crossingWay.id)
		coEvery { wayDao.findByIds(any()) } returns listOf(crossingWay)

		val limit = OsmSpeedLimitSource(wayDao, cellDao)
			.findRoadLimitMps(5_000, WEST_DATELINE_E7)
		limit shouldBe null
	}

	@Test
	fun `ordered wide bbox remains unavailable at positive 180`() = runTest {
		val crossingWay = makeWay(
			id = 1L,
			latsE7 = intArrayOf(10_000, 0),
			lonsE7 = intArrayOf(WEST_OF_DATELINE_E7, EAST_OF_DATELINE_E7),
			maxKmh = 80,
			bboxMinLonE7 = WEST_OF_DATELINE_E7,
			bboxMaxLonE7 = EAST_OF_DATELINE_E7,
		)
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } returns listOf(crossingWay.id)
		coEvery { wayDao.findByIds(any()) } returns listOf(crossingWay)

		val limit = OsmSpeedLimitSource(wayDao, cellDao)
			.findRoadLimitMps(5_000, EAST_DATELINE_E7)
		limit shouldBe null
	}

	@Test
	fun `does not match an antimeridian crossing far from the dateline`() = runTest {
		val crossingWay = makeDatelineCrossingWay(
			id = 1L,
			latsE7 = intArrayOf(0, 10_000),
			lonsE7 = intArrayOf(EAST_OF_DATELINE_E7, WEST_OF_DATELINE_E7),
		)
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } returns listOf(crossingWay.id)
		coEvery { wayDao.findByIds(any()) } returns listOf(crossingWay)

		OsmSpeedLimitSource(wayDao, cellDao)
			.findRoadLimitMps(5_000, 0) shouldBe null
	}

	@Test
	fun `chooses a nearer antimeridian crossing over an ordinary candidate`() = runTest {
		val crossingWay = makeDatelineCrossingWay(
			id = 10L,
			latsE7 = intArrayOf(0, 10_000),
			lonsE7 = intArrayOf(EAST_OF_DATELINE_E7, WEST_OF_DATELINE_E7),
			maxKmh = 80,
		)
		val ordinaryWay = makeWay(
			id = 20L,
			latsE7 = intArrayOf(8_000, 8_000),
			lonsE7 = intArrayOf(1_799_995_000, 1_799_997_000),
			maxKmh = 30,
		)
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } returns listOf(crossingWay.id, ordinaryWay.id)
		coEvery { wayDao.findByIds(any()) } returns listOf(ordinaryWay, crossingWay)

		val limit = OsmSpeedLimitSource(wayDao, cellDao)
			.findRoadLimitMps(5_000, EAST_DATELINE_E7)
		val expected = 80.0 / 3.6
		limit!!.shouldBeBetween(expected, expected, 1e-6)
	}

	@Test
	fun `returns null when no candidate cells contain ways`() = runTest {
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } returns emptyList()

		val source = OsmSpeedLimitSource(wayDao, cellDao)
		source.findRoadLimitMps(500_000_000, 144_000_000) shouldBe null
	}

	@Test
	fun `returns null when nearest way is beyond snap threshold`() = runTest {
		// Way is 500 metres north of the sample — well above the 50 m threshold.
		val way = makeWay(
			id = 1L,
			latsE7 = intArrayOf(500_045_000, 500_045_100),
			lonsE7 = intArrayOf(144_000_000, 144_000_100),
		)
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } returns listOf(1L)
		coEvery { wayDao.findByIds(any()) } returns listOf(way)

		val source = OsmSpeedLimitSource(wayDao, cellDao)
		source.findRoadLimitMps(500_000_000, 144_000_000) shouldBe null
	}

	@Test
	fun `snaps to nearby way and returns its limit converted to mps`() = runTest {
		// Way running east-west right through the sample point.
		val way = makeWay(
			id = 1L,
			latsE7 = intArrayOf(500_000_000, 500_000_000),
			lonsE7 = intArrayOf(143_999_000, 144_001_000),
			maxKmh = 90,
		)
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } returns listOf(1L)
		coEvery { wayDao.findByIds(any()) } returns listOf(way)

		val source = OsmSpeedLimitSource(wayDao, cellDao)
		val limit = source.findRoadLimitMps(500_000_000, 144_000_000)
		val expected = 90.0 / 3.6
		limit!!.shouldBeBetween(expected, expected, 1e-6)
	}

	@Test
	fun `Prague 45 metre east road is covered before distance evaluation`() = runTest {
		val queryLat = 500_755_000
		val queryLon = 144_399_000 // Near a 0.01° cell boundary.
		val roadLon = 144_405_300 // About 45 m east at this latitude.
		val way = makeWay(
			id = 1L,
			latsE7 = intArrayOf(queryLat - 1_000, queryLat + 1_000),
			lonsE7 = intArrayOf(roadLon, roadLon),
			maxKmh = 50,
		)
		val roadCell = OsmGridIndex.cellKey(queryLat, roadLon)
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } answers {
			if (firstArg<Collection<Long>>().contains(roadCell)) listOf(way.id) else emptyList()
		}
		coEvery { wayDao.findByIds(any()) } returns listOf(way)

		OsmSpeedLimitSource(wayDao, cellDao).findRoadLimitMps(queryLat, queryLon) shouldBe 50.0 / 3.6
	}

	@Test
	fun `full polar coverage abstains without querying cells`() = runTest {
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()

		OsmSpeedLimitSource(wayDao, cellDao).findRoadLimitMps(899_998_000, 0) shouldBe null

		coVerify(exactly = 0) { cellDao.findWayIdsInCells(any()) }
	}

	@Test
	fun `picks nearest of several candidates`() = runTest {
		// near and far ways — sample lat = 50.0
		val nearWay = makeWay(
			id = 10L,
			latsE7 = intArrayOf(500_000_010, 500_000_010),
			lonsE7 = intArrayOf(143_999_000, 144_001_000),
			maxKmh = 30,
		)
		val farWay = makeWay(
			id = 20L,
			latsE7 = intArrayOf(500_002_000, 500_002_000),
			lonsE7 = intArrayOf(143_999_000, 144_001_000),
			maxKmh = 130,
		)
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } returns listOf(10L, 20L)
		coEvery { wayDao.findByIds(any()) } returns listOf(nearWay, farWay)

		val source = OsmSpeedLimitSource(wayDao, cellDao)
		val limit = source.findRoadLimitMps(500_000_000, 144_000_000)
		val expected = 30.0 / 3.6
		limit!!.shouldBeBetween(expected, expected, 1e-6)
	}

	@Test
	fun `cache hits avoid re-querying DAOs`() = runTest {
		val way = makeWay(
			id = 1L,
			latsE7 = intArrayOf(500_000_000, 500_000_000),
			lonsE7 = intArrayOf(143_999_000, 144_001_000),
			maxKmh = 50,
		)
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } returns listOf(1L)
		coEvery { wayDao.findByIds(any()) } returns listOf(way)

		val source = OsmSpeedLimitSource(wayDao, cellDao)
		// First call populates cache.
		source.findRoadLimitMps(500_000_000, 144_000_000)
		// Subsequent calls within the same ~5 m bucket should hit the cache.
		source.findRoadLimitMps(500_000_000, 144_000_000)
		source.findRoadLimitMps(500_000_001, 144_000_001)

		coVerify(exactly = 1) { cellDao.findWayIdsInCells(any()) }
	}

	@Test
	fun `null results are also cached`() = runTest {
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		coEvery { cellDao.findWayIdsInCells(any()) } returns emptyList()

		val source = OsmSpeedLimitSource(wayDao, cellDao)
		source.findRoadLimitMps(500_000_000, 144_000_000)
		source.findRoadLimitMps(500_000_000, 144_000_000)

		coVerify(exactly = 1) { cellDao.findWayIdsInCells(any()) }
	}

	@Test
	fun `cacheKey buckets coords by 500 E7 (~5 m)`() {
		val k1 = OsmSpeedLimitSource.cacheKey(500_000_000, 144_000_000)
		val k2 = OsmSpeedLimitSource.cacheKey(500_000_499, 144_000_499)
		val k3 = OsmSpeedLimitSource.cacheKey(500_000_500, 144_000_500)
		k1 shouldBe k2
		(k1 != k3) shouldBe true
	}

	@Test
	fun `cache key canonicalizes dateline and uses floor buckets`() {
		OsmSpeedLimitSource.cacheKey(0, EAST_DATELINE_E7) shouldBe
			OsmSpeedLimitSource.cacheKey(0, WEST_DATELINE_E7)
		OsmSpeedLimitSource.cacheKey(-1, 0) shouldBe OsmSpeedLimitSource.cacheKey(-499, 0)
		(OsmSpeedLimitSource.cacheKey(-1, 0) != OsmSpeedLimitSource.cacheKey(0, 0)) shouldBe true
	}

	@Test
	fun `kmhToMps converts correctly`() {
		val mps = OsmSpeedLimitSource.kmhToMps(90)
		mps.shouldBeBetween(25.0, 25.0, 1e-6)
	}

	@Test
	fun `queries conservative latitude-aware coverage`() = runTest {
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		var seenCellKeys: Collection<Long> = emptyList()
		coEvery { cellDao.findWayIdsInCells(any()) } answers {
			@Suppress("UNCHECKED_CAST")
			seenCellKeys = firstArg<Collection<Long>>()
			emptyList()
		}

		OsmSpeedLimitSource(wayDao, cellDao).findRoadLimitMps(500_000_000, 144_000_000)

		val coordinate = CheckedCoordinateE7.requireE7(500_000_000, 144_000_000)
		val bounds = ConservativeRadiusBounds.around(coordinate, OsmSpeedLimitSource.SNAP_THRESHOLD_M)
		val expected = OsmGridIndex.cellCoverageForBounds(
			bounds.minLatitudeE7,
			bounds.maxLatitudeE7,
			bounds.longitude,
		).let { coverage ->
			(coverage as com.adsamcik.tracker.osm.io.OsmCellCoverage.Available).cellKeys.toList()
		}
		seenCellKeys.toList() shouldBe expected
	}

	private companion object {
		const val EAST_OF_DATELINE_E7 = 1_799_000_000
		const val WEST_OF_DATELINE_E7 = -1_799_000_000
		const val EAST_DATELINE_E7 = 1_800_000_000
		const val WEST_DATELINE_E7 = -1_800_000_000
	}
}
