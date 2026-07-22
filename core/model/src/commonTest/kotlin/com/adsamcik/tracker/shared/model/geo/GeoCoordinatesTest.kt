package com.adsamcik.tracker.shared.model.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoCoordinatesTest {

	@Test
	fun `normalizes canonical longitude values without overflow`() {
		assertEquals(-180.0, CircularLongitude.normalizeDegrees(-180.0))
		assertEquals(-180.0, CircularLongitude.normalizeDegrees(180.0))
		assertEquals(-180.0, CircularLongitude.normalizeDegrees(540.0))
		assertEquals(-180.0, CircularLongitude.normalizeDegrees(-540.0))
		assertEquals(1.0, CircularLongitude.normalizeDegrees(721.0))
		assertEquals(-1.0, CircularLongitude.normalizeDegrees(-721.0))
		assertEquals(0.0.toBits(), CircularLongitude.normalizeDegrees(-0.0).toBits())
		assertEquals(-1_800_000_000L, CircularLongitude.normalizeE7(-1_800_000_000L))
		assertEquals(-1_800_000_000L, CircularLongitude.normalizeE7(1_800_000_000L))
		assertEquals(-1_800_000_000L, CircularLongitude.normalizeE7(5_400_000_000L))
		assertEquals(-1_800_000_000L, CircularLongitude.normalizeE7(-5_400_000_000L))
		assertEquals(10_000_000L, CircularLongitude.normalizeE7(7_210_000_000L))
		assertEquals(-10_000_000L, CircularLongitude.normalizeE7(-7_210_000_000L))

		listOf(Long.MIN_VALUE, Long.MAX_VALUE).forEach { value ->
			val normalized = CircularLongitude.normalizeE7(value)
			assertTrue(normalized in -1_800_000_000L until 1_800_000_000L)
			assertEquals(normalized, CircularLongitude.normalizeE7(normalized))
		}
	}

	@Test
	fun `shortest delta uses antimeridian and westward half-world tie`() {
		assertEquals(0.2, CircularLongitude.shortestDeltaDegrees(179.9, -179.9), 1e-12)
		assertEquals(-0.2, CircularLongitude.shortestDeltaDegrees(-179.9, 179.9), 1e-12)
		assertEquals(-180.0, CircularLongitude.shortestDeltaDegrees(0.0, 180.0))
		assertEquals(-180.0, CircularLongitude.shortestDeltaDegrees(180.0, 0.0))
		assertEquals(-1_800_000_000L, CircularLongitude.shortestDeltaE7(0, 1_800_000_000))
	}

	@Test
	fun `short arc interpolation crosses the antimeridian`() {
		assertEquals(179.95, CircularLongitude.interpolateShortestDegrees(179.9, -179.9, 0.25), 1e-12)
		assertEquals(-180.0, CircularLongitude.interpolateShortestDegrees(179.9, -179.9, 0.5), 1e-12)
		assertEquals(-179.95, CircularLongitude.interpolateShortestDegrees(179.9, -179.9, 0.75), 1e-12)
	}

	@Test
	fun `checked pair is atomic canonical and uses half E7 ties`() {
		assertNull(CheckedCoordinateE7.fromDegreesOrNull(50.0, Double.NaN))
		assertNull(CheckedCoordinateE7.fromDegreesOrNull(90.0000001, 0.0))
		assertNull(CheckedCoordinateE7.fromDegreesOrNull(0.0, 180.0000001))

		val dateline = assertNotNull(CheckedCoordinateE7.fromDegreesOrNull(-0.0, 180.0))
		assertEquals(0, dateline.latitudeE7)
		assertEquals(-1_800_000_000, dateline.longitudeE7)

		val pole = assertNotNull(CheckedCoordinateE7.fromDegreesOrNull(90.0, 12.0))
		assertEquals(0, pole.longitudeE7)

		assertEquals(1, CheckedLatitudeE7.requireDegrees(0.00000005).value)
		assertEquals(0, CheckedLatitudeE7.requireDegrees(-0.00000005).value)
	}

	@Test
	fun `circular intervals represent crossing paths and full state explicitly`() {
		val crossing = CircularLongitudeInterval.fromShortestEdgePolyline(
			intArrayOf(1_799_000_000, -1_799_000_000),
		)
		assertEquals(1_799_000_000L, crossing.startE7)
		assertEquals(2_000_000L, crossing.eastwardSpanE7)
		assertTrue(crossing.contains(-1_800_000_000))
		assertTrue(!crossing.contains(0))
		assertEquals(2, crossing.toOrdinaryRangesE7().size)

		val longPath = CircularLongitudeInterval.fromShortestEdgePolyline(
			intArrayOf(-1_700_000_000, 0, 1_700_000_000),
		)
		assertEquals(3_400_000_000L, longPath.eastwardSpanE7)

		assertIs<CircularLongitudeInterval.Full>(
			CircularLongitudeInterval.point(0).expand(1_800_000_000),
		)
	}

	@Test
	fun `radius bounds are conservative at Prague and explicit at the pole`() {
		val prague = CheckedCoordinateE7.requireDegrees(50.0755, 14.4377523)
		val pragueBounds = ConservativeRadiusBounds.around(prague, 50.0)
		assertTrue(pragueBounds.longitude.eastwardSpanE7 / 2L >= 7_046L)

		val nearPole = CheckedCoordinateE7.requireDegrees(89.9995, 0.0)
		assertTrue(ConservativeRadiusBounds.around(nearPole, 50.0).longitude !is CircularLongitudeInterval.Full)

		val poleCap = CheckedCoordinateE7.requireDegrees(89.9998, 0.0)
		assertIs<CircularLongitudeInterval.Full>(ConservativeRadiusBounds.around(poleCap, 50.0).longitude)
	}
}
