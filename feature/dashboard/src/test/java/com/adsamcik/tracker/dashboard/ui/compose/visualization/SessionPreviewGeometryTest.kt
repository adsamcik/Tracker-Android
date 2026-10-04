package com.adsamcik.tracker.dashboard.ui.compose.visualization

import com.adsamcik.tracker.shared.model.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPreviewGeometryTest {
	@Test
	fun negativeCoordinatesFillTheSamePreviewAsPositiveCoordinates() {
		val positive = project(listOf(point(50.0, 14.0), point(50.002, 14.002)))
		val negative = project(listOf(point(-34.0, -58.0), point(-33.998, -57.998)))
		positive.zip(negative).forEach { (expected, actual) ->
			assertEquals(expected.x, actual.x, 0.01f)
			assertEquals(expected.y, actual.y, 0.01f)
		}
		assertTrue(negative.last().x - negative.first().x > 190f)
		assertTrue(negative.first().y - negative.last().y > 90f)
	}

	@Test
	fun routeAcrossZeroKeepsItsEndpointsAndMiddlePoint() {
		val points = project(listOf(point(-0.002, -0.002), point(0.0, 0.0), point(0.002, 0.002)))
		assertEquals(20f, points.first().x, 0.01f)
		assertEquals(120f, points[1].x, 0.01f)
		assertEquals(60f, points[1].y, 0.01f)
		assertEquals(220f, points.last().x, 0.01f)
		assertEquals(10f, points.last().y, 0.01f)
	}

	@Test
	fun northSouthRouteAtNegativeLongitudeIsCenteredHorizontally() {
		val points = project(listOf(point(-34.0, -58.0), point(-33.998, -58.0)))
		points.forEach { assertEquals(120f, it.x, 0.01f) }
		assertEquals(110f, points.first().y, 0.01f)
		assertEquals(10f, points.last().y, 0.01f)
	}

	@Test
	fun eastWestRouteAtNegativeLatitudeIsCenteredVertically() {
		val points = project(listOf(point(-34.0, -58.0), point(-34.0, -57.998)))
		points.forEach { assertEquals(60f, it.y, 0.01f) }
		assertEquals(20f, points.first().x, 0.01f)
		assertEquals(220f, points.last().x, 0.01f)
	}

	@Test
	fun identicalNegativePointsDoNotInventARoute() {
		assertTrue(project(listOf(point(-34.0, -58.0), point(-34.0, -58.0))).isEmpty())
	}

	private fun project(points: List<Location>) = sessionPreviewScreenPoints(points, 240f, 120f)

	private fun point(latitude: Double, longitude: Double) = Location(
		time = 1_000L, latitude = latitude, longitude = longitude,
		altitude = null, horizontalAccuracy = null, verticalAccuracy = null,
		speed = null, speedAccuracy = null,
	)
}
