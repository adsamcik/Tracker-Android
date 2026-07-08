package com.adsamcik.tracker.osm.match

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure JVM unit tests for [OsmGeometry]. Coordinates are chosen near the equator
 * so `cos(lat) ≈ 1` and metres-per-E7 is uniform in both axes, making the
 * expected distances easy to reason about.
 */
@DisplayName("OsmGeometry")
class OsmGeometryTest {

	private val mPerE7 = OsmGeometry.METRES_PER_E7_DEG

	@Test
	fun `cumulativeArcLengthM accumulates segment lengths`() {
		val lats = intArrayOf(0, 100_000, 300_000)
		val lons = intArrayOf(0, 0, 0)
		val cum = OsmGeometry.cumulativeArcLengthM(lats, lons)
		cum.size shouldBe 3
		cum[0] shouldBe 0.0
		cum[1] shouldBe (100_000 * mPerE7).plusOrMinus(1e-6)
		cum[2] shouldBe (300_000 * mPerE7).plusOrMinus(1e-6)
	}

	@Test
	fun `cumulativeArcLengthM is empty for empty input`() {
		OsmGeometry.cumulativeArcLengthM(IntArray(0), IntArray(0)).size shouldBe 0
	}

	@Test
	fun `projectToPolyline returns null for degenerate polyline`() {
		OsmGeometry.projectToPolyline(intArrayOf(0), intArrayOf(0), doubleArrayOf(0.0), 10, 10)
			.shouldBeNull()
	}

	@Test
	fun `projectToPolyline snaps a point onto the nearest segment`() {
		// A vertical (north-going) two-segment line along lon=0.
		val lats = intArrayOf(0, 100_000, 200_000)
		val lons = intArrayOf(0, 0, 0)
		val cum = OsmGeometry.cumulativeArcLengthM(lats, lons)

		// Point at lat 50_000, offset 100 E7 east of the line.
		val proj = OsmGeometry.projectToPolyline(lats, lons, cum, 50_000, 100).shouldNotBeNull()

		proj.segmentIndex shouldBe 0
		proj.t shouldBe 0.5.plusOrMinus(1e-6)
		proj.snappedLatE7 shouldBe 50_000
		proj.snappedLonE7 shouldBe 0
		// Perpendicular distance == the 100-E7 east offset converted to metres.
		proj.distanceM shouldBe (100 * mPerE7).plusOrMinus(1e-4)
		proj.arcLengthM shouldBe (50_000 * mPerE7).plusOrMinus(1e-4)
	}

	@Test
	fun `projectToPolyline picks the closer of two segments`() {
		val lats = intArrayOf(0, 100_000, 200_000)
		val lons = intArrayOf(0, 0, 0)
		val cum = OsmGeometry.cumulativeArcLengthM(lats, lons)

		val proj = OsmGeometry.projectToPolyline(lats, lons, cum, 150_000, 5).shouldNotBeNull()
		proj.segmentIndex shouldBe 1
		proj.arcLengthM shouldBe (150_000 * mPerE7).plusOrMinus(1e-4)
	}

	@Test
	fun `slicePolyline includes intermediate vertices between two positions`() {
		val lats = intArrayOf(0, 100_000, 200_000)
		val lons = intArrayOf(0, 0, 0)
		val cum = OsmGeometry.cumulativeArcLengthM(lats, lons)

		val (sliceLat, sliceLon) = OsmGeometry.slicePolyline(
			lats, lons, cum,
			startArcM = 50_000 * mPerE7,
			endArcM = 150_000 * mPerE7,
			startLatE7 = 50_000, startLonE7 = 0,
			endLatE7 = 150_000, endLonE7 = 0,
		)
		// start, the middle vertex (100_000), end.
		sliceLat.toList() shouldBe listOf(50_000, 100_000, 150_000)
		sliceLon.toList() shouldBe listOf(0, 0, 0)
	}

	@Test
	fun `slicePolyline reverses for backward travel`() {
		val lats = intArrayOf(0, 100_000, 200_000)
		val lons = intArrayOf(0, 0, 0)
		val cum = OsmGeometry.cumulativeArcLengthM(lats, lons)

		val (sliceLat, _) = OsmGeometry.slicePolyline(
			lats, lons, cum,
			startArcM = 150_000 * mPerE7,
			endArcM = 50_000 * mPerE7,
			startLatE7 = 150_000, startLonE7 = 0,
			endLatE7 = 50_000, endLonE7 = 0,
		)
		sliceLat.toList() shouldBe listOf(150_000, 100_000, 50_000)
	}

	@Test
	fun `distanceM matches the planar separation`() {
		OsmGeometry.distanceM(0, 0, 100_000, 0) shouldBe (100_000 * mPerE7).plusOrMinus(1e-4)
	}

	@Test
	fun `arc length is non-negative and increasing for a real-ish path`() {
		val lats = intArrayOf(500_000_000, 500_010_000, 500_020_000)
		val lons = intArrayOf(140_000_000, 140_005_000, 140_010_000)
		val cum = OsmGeometry.cumulativeArcLengthM(lats, lons)
		((cum[2] - cum[1]).toInt()) shouldBeGreaterThan 0
		((cum[1] - cum[0]).toInt()) shouldBeGreaterThan 0
	}
}
