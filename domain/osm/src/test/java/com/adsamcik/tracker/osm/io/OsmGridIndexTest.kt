package com.adsamcik.tracker.osm.io

import com.adsamcik.tracker.shared.model.geo.CircularLongitudeInterval
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
	fun `cellKeysForBbox splits a narrow antimeridian crossing`() {
		val keys = OsmGridIndex.cellKeysForBbox(
			minLatE7 = 0,
			maxLatE7 = 0,
			minLonE7 = 1_799_000_000,
			maxLonE7 = -1_799_000_000,
		)
		val lonCells = keys.map(::decodeLonCell)

		keys.toList().distinct().shouldHaveSize(21)
		lonCells.shouldContainExactlyInAnyOrder(
			(17_990..17_999).toList() + (-18_000..-17_990).toList(),
		)
	}

	@Test
	fun `typed coverage does not infer a complement from an ordered wide bbox`() {
		val coverage = OsmGridIndex.cellCoverageForBbox(
			minLatE7 = 0,
			maxLatE7 = 0,
			startLonE7 = -1_799_000_000,
			endLonE7 = 1_799_000_000,
		)

		coverage.shouldBeInstanceOf<OsmCellCoverage.TooLarge>()
	}

	@Test
	fun `endpoint aliases at negative and positive 180 are a point not inferred full world`() {
		val coverage = OsmGridIndex.cellCoverageForBbox(
			minLatE7 = 0,
			maxLatE7 = 0,
			startLonE7 = -1_800_000_000,
			endLonE7 = 1_800_000_000,
		)

		val available = coverage.shouldBeInstanceOf<OsmCellCoverage.Available>()
		available.cellKeys shouldBe longArrayOf(OsmGridIndex.cellKey(0, -1_800_000_000))
	}

	@Test
	fun `full world coverage requires the explicit interval state and abstains without allocating`() {
		val coverage = OsmGridIndex.cellCoverageForBounds(
			minLatE7 = 0,
			maxLatE7 = 0,
			longitude = CircularLongitudeInterval.full(),
		)

		val tooLarge = coverage.shouldBeInstanceOf<OsmCellCoverage.TooLarge>()
		tooLarge.requestedCellCount shouldBe 36_000L
		OsmGridIndex.MAX_CELLS_PER_BBOX shouldBe 20_000
	}

	@Test
	fun `cellKeysForBbox handles positive 180 endpoint without expanding worldwide`() {
		val keys = OsmGridIndex.cellKeysForBbox(
			minLatE7 = 0,
			maxLatE7 = 0,
			minLonE7 = 1_799_000_000,
			maxLonE7 = 1_800_000_000,
		)

		keys.toList().distinct().shouldHaveSize(11)
	}

	@Test
	fun `cellKeysForBbox canonicalizes a point exactly at positive 180`() {
		val keys = OsmGridIndex.cellKeysForBbox(
			minLatE7 = 0,
			maxLatE7 = 0,
			minLonE7 = 1_800_000_000,
			maxLonE7 = 1_800_000_000,
		)

		keys shouldBe longArrayOf(OsmGridIndex.cellKey(0, -1_800_000_000))
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

	// region cell-size invariants (locked in by the v31->v32 migration)

	@Test
	fun `cell size is 0_01 degrees so road-segment-scale lookups are selective`() {
		OsmGridIndex.CELL_DEGREES shouldBe 0.01
		OsmGridIndex.CELL_E7 shouldBe 100_000
	}

	@Test
	fun `cell width at lat 50 is roughly 1_1 km wide and 0_7 km tall`() {
		// One cell at lat 50: 0.01° latitude = ~1113 m great-circle.
		// 0.01° longitude shrinks by cos(50°) ≈ 0.6428 → ~715 m.
		val metresPerDegLat = 111_320.0
		val expectedLatM = OsmGridIndex.CELL_DEGREES * metresPerDegLat
		val expectedLonM = OsmGridIndex.CELL_DEGREES * metresPerDegLat * Math.cos(Math.toRadians(50.0))
		(expectedLatM in 1108.0..1118.0) shouldBe true
		(expectedLonM in 710.0..720.0) shouldBe true
	}

	@Test
	fun `cellKeysForBbox covers a 5km x 5km bbox at lat 50 with at most ~50 cells`() {
		// 5 km north-south ≈ 0.045° latitude → ~5 cells.
		// 5 km east-west at lat 50 ≈ 0.070° longitude → ~7 cells.
		// Total grid block: ~5x7 ≈ 35 cells (plus boundary overlap, capped at 8x10 = 80).
		val centerLatE7 = 500_000_000 // lat 50.0°
		val centerLonE7 = 144_000_000 // lon 14.4°
		val halfLatE7 = 250_000        // 0.025° → 2.78 km
		val halfLonE7 = 350_000        // 0.035° at lat 50 → ~2.5 km
		val keys = OsmGridIndex.cellKeysForBbox(
			minLatE7 = centerLatE7 - halfLatE7,
			maxLatE7 = centerLatE7 + halfLatE7,
			minLonE7 = centerLonE7 - halfLonE7,
			maxLonE7 = centerLonE7 + halfLonE7,
		)
		// 5 lat × 7 lon = 35; allow ±1 on each axis for boundary alignment.
		(keys.size in 30..56) shouldBe true
		// Same bbox under the old 0.08° (E7 800_000) grid resolved to a single
		// cell, so the new index is at minimum an order of magnitude more
		// selective on this size of query.
		(keys.size >= 30) shouldBe true
	}

	@Test
	fun `equator boundary lat 0 returns adjacent cells north and south of it`() {
		val north = OsmGridIndex.cellKey(latE7 = 50_000, lonE7 = 0)
		val onLine = OsmGridIndex.cellKey(latE7 = 0, lonE7 = 0)
		val south = OsmGridIndex.cellKey(latE7 = -50_000, lonE7 = 0)
		// (lat=0 falls into the [0,CELL_E7) cell, same as north).
		north shouldBe onLine
		(south != onLine) shouldBe true
	}

	@Test
	fun `prime meridian boundary lon 0 separates east and west cells`() {
		val east = OsmGridIndex.cellKey(latE7 = 500_000_000, lonE7 = 50_000)
		val west = OsmGridIndex.cellKey(latE7 = 500_000_000, lonE7 = -50_000)
		(east != west) shouldBe true
	}

	@Test
	fun `dateline lon plus and minus 180 share one canonical cell key`() {
		val east = OsmGridIndex.cellKey(latE7 = 0, lonE7 = 1_800_000_000)
		val west = OsmGridIndex.cellKey(latE7 = 0, lonE7 = -1_800_000_000)
		east shouldBe west
	}

	@Test
	fun `north pole lat 90 packs without overflow into the high-bit slot`() {
		val polar = OsmGridIndex.cellKey(latE7 = 900_000_000, lonE7 = 0)
		val justBelow = OsmGridIndex.cellKey(latE7 = 899_900_000, lonE7 = 0)
		(polar != justBelow) shouldBe true
	}

	@Test
	fun `keys for two adjacent cells decode to consecutive lat or lon indices`() {
		// Sanity check that the packing is recoverable; lat in high bits,
		// lon in the low 24 bits as a signed value.
		val key = OsmGridIndex.cellKey(latE7 = 500_000_000, lonE7 = 144_000_000)
		val latCell = key shr 24
		val lonCellRaw = (key and 0xFFFFFFL).toInt()
		// 0xFFFFFF is unsigned; reconstruct sign manually for negatives.
		val lonCell = if ((lonCellRaw and 0x800000) != 0) lonCellRaw or 0xFF000000.toInt() else lonCellRaw
		latCell shouldBe 5_000L
		lonCell shouldBe 1_440
	}

	// endregion

	private fun decodeLonCell(key: Long): Int {
		val raw = (key and 0xFFFFFFL).toInt()
		return if ((raw and 0x800000) != 0) raw or 0xFF000000.toInt() else raw
	}
}
