package com.adsamcik.tracker.osm.io

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OsmGridIndex cell keys")
class OsmGridIndexTest {

	@Test
	fun `cellKey is deterministic per cell`() {
		val a = OsmGridIndex.cellKey(500_000_000, 144_000_000)
		val b = OsmGridIndex.cellKey(500_000_001, 144_000_001)
		a shouldBe b
	}

	@Test
	fun `cellKey differs across cell boundary`() {
		val inside = OsmGridIndex.cellKey(500_000_000, 144_000_000)
		val nextCell = OsmGridIndex.cellKey(500_000_000 + OsmGridIndex.CELL_E7, 144_000_000)
		(inside != nextCell) shouldBe true
	}

	@Test
	fun `negative coords use floor division so they don't collide with positives`() {
		val north = OsmGridIndex.cellKey(50_000, 50_000)
		val south = OsmGridIndex.cellKey(-50_000, 50_000)
		(north != south) shouldBe true
	}

	@Test
	fun `cellKeysForBbox single cell returns one key`() {
		val keys = OsmGridIndex.cellKeysForBbox(
			minLatE7 = 500_000_000,
			maxLatE7 = 500_000_100,
			minLonE7 = 144_000_000,
			maxLonE7 = 144_000_100,
		)
		keys.size shouldBe 1
	}

	@Test
	fun `cellKeysForBbox spans rectangular grid block`() {
		// Bbox 3 cells wide x 2 cells tall -> 6 unique keys.
		val keys = OsmGridIndex.cellKeysForBbox(
			minLatE7 = 0,
			maxLatE7 = OsmGridIndex.CELL_E7 + 1,
			minLonE7 = 0,
			maxLonE7 = 2 * OsmGridIndex.CELL_E7 + 1,
		)
		keys.toList().distinct().shouldHaveSize(6)
	}

	@Test
	fun `cellAnd8Neighbors returns 9 distinct keys`() {
		val keys = OsmGridIndex.cellAnd8Neighbors(500_000_000, 144_000_000)
		keys.size shouldBe 9
		keys.toList().distinct().shouldHaveSize(9)
	}

	@Test
	fun `cellAnd8Neighbors contains center cell`() {
		val center = OsmGridIndex.cellKey(500_000_000, 144_000_000)
		val keys = OsmGridIndex.cellAnd8Neighbors(500_000_000, 144_000_000).toList()
		keys.shouldContain(center)
	}

	@Test
	fun `cellAnd8Neighbors near zero crossing returns 9 unique cells`() {
		val keys = OsmGridIndex.cellAnd8Neighbors(0, 0).toList()
		keys.distinct().shouldHaveSize(9)
	}

	@Test
	fun `cellKeysForBbox rejects inverted bbox`() {
		val ex = runCatching {
			OsmGridIndex.cellKeysForBbox(minLatE7 = 100, maxLatE7 = 50, minLonE7 = 0, maxLonE7 = 0)
		}.exceptionOrNull()
		(ex is IllegalArgumentException) shouldBe true
	}

	@Test
	fun `cellAnd8Neighbors at high latitude does not overflow lat slot`() {
		// 80 degrees N: latE7 = 800_000_000 = 1000 cells. Plus margin still fits in 16 bits.
		val keys = OsmGridIndex.cellAnd8Neighbors(800_000_000, 0).toList()
		keys.distinct().shouldHaveSize(9)
	}

	@Test
	fun `cellKeysForBbox returns same keys as 9-neighbor when bbox spans 3x3`() {
		val centerLat = 500_000_000
		val centerLon = 144_000_000
		val neighbors = OsmGridIndex.cellAnd8Neighbors(centerLat, centerLon).toList()
		val bbox = OsmGridIndex.cellKeysForBbox(
			minLatE7 = centerLat - OsmGridIndex.CELL_E7,
			maxLatE7 = centerLat + OsmGridIndex.CELL_E7,
			minLonE7 = centerLon - OsmGridIndex.CELL_E7,
			maxLonE7 = centerLon + OsmGridIndex.CELL_E7,
		).toList()
		bbox.shouldContainExactlyInAnyOrder(neighbors)
	}
}
