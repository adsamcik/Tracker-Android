package com.adsamcik.tracker.osm.match

import com.adsamcik.tracker.osm.io.PolylineE7Codec
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import com.adsamcik.tracker.stats.api.roadmatch.RoadObservation
import com.adsamcik.tracker.stats.api.roadmatch.RoadLimitProvenance
import com.adsamcik.tracker.stats.api.roadmatch.RoadMatchStatus
import com.adsamcik.tracker.stats.api.roadmatch.RoadPoint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OsmHmmMapMatcher]. No Room/Hilt — the DAOs are mocked with
 * MockK and we hand-craft a single straight OSM way to match against.
 *
 * The way is a north-going line at lon = 140_000_000 (E7) with three vertices.
 * Observations sit a metre or two east of the line so they snap onto it.
 */
@DisplayName("OsmHmmMapMatcher")
class OsmHmmMapMatcherTest {

	private val wayDao = mockk<OsmWayDao>()
	private val cellDao = mockk<OsmWayCellDao>()
	private val matcher = OsmHmmMapMatcher(wayDao, cellDao)

	private val lats = intArrayOf(500_000_000, 500_010_000, 500_020_000)
	private val lons = intArrayOf(140_000_000, 140_000_000, 140_000_000)

	private fun straightWay(maxKmh: Int = 60): OsmWayEntity = OsmWayEntity(
		id = 1L,
		importId = 1L,
		name = "test-road",
		roadClass = "residential",
		maxspeedKmh = maxKmh,
		maxspeedExplicit = 1,
		isOneway = 0,
		geomPolylineE7 = PolylineE7Codec.encode(lats, lons),
		bboxMinLatE7 = lats.min(),
		bboxMaxLatE7 = lats.max(),
		bboxMinLonE7 = lons.min(),
		bboxMaxLonE7 = lons.max(),
	)

	private fun haveRoadNearby(way: OsmWayEntity = straightWay()) {
		coEvery { cellDao.findWayIdsInCells(any()) } returns listOf(way.id)
		coEvery { wayDao.findByIds(any()) } returns listOf(way)
	}

	private fun haveRoadsNearby(vararg ways: OsmWayEntity) {
		coEvery { cellDao.findWayIdsInCells(any()) } returns ways.map { it.id }
		coEvery { wayDao.findByIds(any()) } returns ways.toList()
	}

	private fun obs(latE7: Int, lonE7: Int, timeMs: Long) =
		RoadObservation(latE7 = latE7, lonE7 = lonE7, accuracyM = 8f, timeMs = timeMs)

	@Test
	fun `fewer than two observations yields no edges`() = runTest {
		haveRoadNearby()
		matcher.match(listOf(obs(500_005_000, 140_000_050, 1_000L))).shouldBeEmpty()
	}

	@Test
	fun `no nearby ways yields no edges`() = runTest {
		coEvery { cellDao.findWayIdsInCells(any()) } returns emptyList()
		matcher.match(
			listOf(
				obs(500_005_000, 140_000_050, 1_000L),
				obs(500_015_000, 140_000_050, 2_000L),
			),
		).shouldBeEmpty()
	}

	@Test
	fun `two observations on a way produce one road-following edge`() = runTest {
		haveRoadNearby()
		val edges = matcher.match(
			listOf(
				obs(500_005_000, 140_000_050, 1_000L),
				obs(500_015_000, 140_000_050, 2_000L),
			),
		)

		edges shouldHaveSize 1
		val edge = edges.single()
		edge.fromIndex shouldBe 0
		edge.toIndex shouldBe 1
		edge.maxspeedKmh shouldBe 60
		edge.limitProvenance shouldBe RoadLimitProvenance.EXPLICIT_OSM_TAG
		edge.importId shouldBe 1L
		edge.osmWayId shouldBe 1L
		// Path snaps both ends onto the line and includes the middle vertex.
		edge.path shouldBe listOf(
			RoadPoint(500_005_000, 140_000_000),
			RoadPoint(500_010_000, 140_000_000),
			RoadPoint(500_015_000, 140_000_000),
		)
	}

	@Test
	fun `road class fallback remains marked heuristic`() = runTest {
		val fallback = straightWay().copy(maxspeedExplicit = 0)
		haveRoadNearby(fallback)

		val edge = matcher.match(
			listOf(
				obs(500_005_000, 140_000_050, 1_000L),
				obs(500_015_000, 140_000_050, 2_000L),
			),
		).single()

		edge.maxspeedKmh shouldBe 60
		edge.limitProvenance shouldBe RoadLimitProvenance.ROAD_CLASS_HEURISTIC
	}

	@Test
	fun `three collinear observations produce two contiguous edges`() = runTest {
		haveRoadNearby()
		val edges = matcher.match(
			listOf(
				obs(500_002_000, 140_000_050, 1_000L),
				obs(500_010_000, 140_000_050, 2_000L),
				obs(500_018_000, 140_000_050, 3_000L),
			),
		)

		edges shouldHaveSize 2
		edges[0].fromIndex shouldBe 0
		edges[0].toIndex shouldBe 1
		edges[1].fromIndex shouldBe 1
		edges[1].toIndex shouldBe 2
		// Contiguity: edge 0 ends exactly where edge 1 begins.
		edges[0].path.last() shouldBe edges[1].path.first()
	}

	@Test
	fun `an off-road observation in the middle emits typed gap spans`() = runTest {
		haveRoadNearby()
		// Middle observation is ~600 m east — far beyond the 50 m snap radius — so it
		// has no candidate, so neither adjacent interval may be rendered as a
		// normal compliance edge.
		val edges = matcher.match(
			listOf(
				obs(500_005_000, 140_000_050, 1_000L),
				obs(500_010_000, 140_900_000, 2_000L),
				obs(500_015_000, 140_000_050, 3_000L),
			),
		)
		edges shouldHaveSize 2
		edges.map { it.matchStatus } shouldBe listOf(RoadMatchStatus.GAP, RoadMatchStatus.GAP)
		edges.forEach { edge -> edge.path.shouldBeEmpty() }
	}

	@Test
	fun `unconnected candidate ways emit a typed no path span`() = runTest {
		val disconnectedLons = lons.map { it + 10_000 }.toIntArray()
		val disconnected = straightWay().copy(
			id = 2L,
			geomPolylineE7 = PolylineE7Codec.encode(lats, disconnectedLons),
			bboxMinLonE7 = disconnectedLons.min(),
			bboxMaxLonE7 = disconnectedLons.max(),
		)
		haveRoadsNearby(straightWay(), disconnected)

		val edge = matcher.match(
			listOf(
				obs(500_005_000, 140_000_000, 1_000L),
				obs(500_015_000, 140_010_000, 2_000L),
			),
		).single()

		edge.matchStatus shouldBe RoadMatchStatus.NO_PATH
		edge.path.shouldBeEmpty()
	}
}
