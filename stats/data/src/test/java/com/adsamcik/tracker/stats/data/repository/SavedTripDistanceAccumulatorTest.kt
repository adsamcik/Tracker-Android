package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.stats.api.repository.SavedLocationFix
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class SavedTripDistanceAccumulatorTest {

    @Test fun `finite whole route preview stays bounded and preserves both sides of a saved gap`() {
        val route = SavedTripDistanceAccumulator(retainWholeRoutePreview = true)
        for (index in 1..2_000) {
            val owner = if (index <= 1_000) { "owner:1" } else { "owner:2" }
            val row = point(index * 1_000L, index * 100, owner, index.toLong())
            route.add(row, previewFix(row))
        }
        val sections = route.routePreview
        kotlin.test.assertTrue(sections.sumOf { it.size } <= 512)
        assertEquals(1_000L, sections.first().first().measuredTimeMs)
        assertEquals(2_000_000L, sections.last().last().measuredTimeMs)
        assertEquals(2, sections.size)
        kotlin.test.assertTrue(sections.first().all { it.measuredTimeMs <= 1_000_000 })
        kotlin.test.assertTrue(sections.last().all { it.measuredTimeMs > 1_000_000 })
        kotlin.test.assertTrue(route.hasDisconnectedSections)
    }

    @Test fun `bounded whole route never joins isolated sections even when some are omitted`() {
        val route = SavedTripDistanceAccumulator(retainWholeRoutePreview = true)
        for (index in 1..2_000) {
            val row = point(index * 1_000L, index * 100, "owner:$index", 1)
            route.add(row, previewFix(row))
        }
        val sections = route.routePreview
        kotlin.test.assertTrue(sections.sumOf { it.size } <= 512)
        kotlin.test.assertTrue(sections.all { it.size == 1 })
        assertEquals(1_000L, sections.first().single().measuredTimeMs)
        assertEquals(2_000_000L, sections.last().single().measuredTimeMs)
        assertNull(route.measuredMeters)
    }

    private fun previewFix(row: LocationPathSampleRow): SavedLocationFix = row.sample.let { sample ->
        SavedLocationFix(sample.timeMs, sample.timeMs, 0, requireNotNull(sample.latE7),
            requireNotNull(sample.lonE7), null, null, null, null,
            com.adsamcik.tracker.shared.model.AltitudeDatum.UNKNOWN_LEGACY,
            com.adsamcik.tracker.shared.model.AltitudeSource.UNKNOWN_LEGACY, null)
    }
	@Test fun `legacy gaps reversed times and invalid coordinates do not manufacture an edge`() {
		for (boundary in listOf(point(200_000, 900_000), point(1_000, 900_000),
			point(2_000, null), point(2_000, 900_000).copy(sample = point(2_000, 900_000).sample.copy(latE7 = 1_000_000_000)))) {
			val distance = SavedTripDistanceAccumulator()
			distance.add(point(1_000, 0))
			distance.add(boundary)
			assertNull(distance.measuredMeters)
		}
	}

	@Test fun `measured sections survive a long gap without including the gap displacement`() {
		val distance = SavedTripDistanceAccumulator()
		listOf(point(1_000, 0), point(2_000, 100_000), point(200_000, 9_000_000),
			point(201_000, 9_100_000)).forEach(distance::add)
		assertEquals(edge(0, 100_000) + edge(9_000_000, 9_100_000), distance.measuredMeters)
	}

	@Test fun `registration sequence clock and elapsed discontinuities each break native continuity`() {
		val first = point(1_000, 0, "owner:1", 1)
		val next = point(2_000, 100_000, "owner:1", 2)
		for (boundary in listOf(next.copy(continuityId = "owner:2"), next.copy(continuitySequence = 3),
			next.copy(sample = next.sample.copy(clockDomainId = "another-boot")),
			next.copy(sample = next.sample.copy(elapsedRealtimeNanos = first.sample.elapsedRealtimeNanos)),
			next.copy(sample = next.sample.copy(elapsedRealtimeNanos = 200_000_000_000)))) {
			val distance = SavedTripDistanceAccumulator()
			distance.add(first)
			distance.add(boundary)
			assertNull(distance.measuredMeters)
		}
		val continuous = SavedTripDistanceAccumulator()
		continuous.add(first)
		continuous.add(next)
		assertEquals(edge(0, 100_000), continuous.measuredMeters)
	}

	@Test fun `a measured stationary pair is zero while one lonely point is unavailable`() {
		val distance = SavedTripDistanceAccumulator()
		distance.add(point(1_000, 0))
		assertNull(distance.measuredMeters)
		distance.add(point(2_000, 0))
		assertEquals(0.0, distance.measuredMeters)
	}

    @Test fun `moving average excludes stationary pairs and keeps measured zero distinct from absent data`() {
        val route = SavedTripDistanceAccumulator()
        route.add(point(1_000, 0, "owner:1", 1))
        assertNull(route.movingAverageSpeedMetersPerSecond)
        route.add(point(2_000, 100_000, "owner:1", 2))
        route.add(point(102_000, 100_000, "owner:1", 3))
        route.add(point(103_000, 200_000, "owner:1", 4))
        assertEquals((edge(0, 100_000) + edge(100_000, 200_000)) / 2.0,
            route.movingAverageSpeedMetersPerSecond)
        val stationary = SavedTripDistanceAccumulator()
        stationary.add(point(1_000, 0))
        stationary.add(point(2_000, 0))
        assertEquals(0.0, stationary.movingAverageSpeedMetersPerSecond)
    }

    @Test fun `moving average excludes gaps and resumes the shared running state exactly`() {
        val points = listOf(point(1_000, 0, "owner:1", 1), point(2_000, 100_000, "owner:1", 2),
            point(200_000, 9_000_000, "owner:1", 3), point(202_000, 9_100_000, "owner:1", 4))
        val route = SavedTripDistanceAccumulator()
        points.take(2).forEach(route::add)
        val continued = SavedTripDistanceAccumulator(route.snapshotState())
        points.drop(2).forEach(continued::add)
        points.drop(2).forEach(route::add)
        assertEquals(route.snapshotState(), continued.snapshotState())
        assertEquals((edge(0, 100_000) + edge(9_000_000, 9_100_000)) / 3.0,
            continued.movingAverageSpeedMetersPerSecond)
        kotlin.test.assertTrue(continued.hasDisconnectedSections)
    }

    @Test fun `native moving average uses recorded elapsed time despite reversed wall timestamps`() {
        val route = SavedTripDistanceAccumulator()
        route.add(point(2_000, 0, "owner:1", 1))
        route.add(point(3_000, 100_000, "owner:1", 2).copy(
            sample = point(3_000, 100_000).sample.copy(timeMs = 1_000)))
        assertEquals(edge(0, 100_000), route.movingAverageSpeedMetersPerSecond)
    }

	private fun point(time: Long, longitude: Int?, continuity: String? = null, sequence: Long? = null) =
		LocationPathSampleRow(LocationSample(timeMs = time, elapsedRealtimeNanos = time * 1_000_000,
			latE7 = 0, lonE7 = longitude, altitudeM = null, rawGpsAltitudeM = null, hAccM = null,
			vAccM = null, speedMps = null, speedAccuracyMps = null, provider = "saved",
			quality = SampleQuality.COARSE, motionState = null, policy = null, bucketId = null,
			createdAt = time, clockDomainId = "boot"), continuity, sequence, false)
	private fun edge(first: Int, second: Int) = Location.distance(0.0, first / 1e7, 0.0, second / 1e7, LengthUnit.Meter)
}
