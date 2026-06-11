package com.adsamcik.tracker.osm.speed

import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.osm.io.PolylineE7Codec
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
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
			bboxMinLonE7 = lonsE7.min(),
			bboxMaxLonE7 = lonsE7.max(),
		)
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
	fun `kmhToMps converts correctly`() {
		val mps = OsmSpeedLimitSource.kmhToMps(90)
		mps.shouldBeBetween(25.0, 25.0, 1e-6)
	}

	@Test
	fun `queries cellAnd8Neighbors not just the central cell`() = runTest {
		val cellDao = mockk<OsmWayCellDao>()
		val wayDao = mockk<OsmWayDao>()
		var seenCellKeys: Collection<Long> = emptyList()
		coEvery { cellDao.findWayIdsInCells(any()) } answers {
			@Suppress("UNCHECKED_CAST")
			seenCellKeys = firstArg<Collection<Long>>()
			emptyList()
		}

		OsmSpeedLimitSource(wayDao, cellDao).findRoadLimitMps(500_000_000, 144_000_000)

		// Should be exactly 9 keys (3x3 grid).
		val expected = OsmGridIndex.cellAnd8Neighbors(500_000_000, 144_000_000).toList()
		seenCellKeys.toList() shouldBe expected
	}
}
