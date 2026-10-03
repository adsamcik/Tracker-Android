package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class SavedTripDistanceAccumulatorTest {
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

	private fun point(time: Long, longitude: Int?, continuity: String? = null, sequence: Long? = null) =
		LocationPathSampleRow(LocationSample(timeMs = time, elapsedRealtimeNanos = time * 1_000_000,
			latE7 = 0, lonE7 = longitude, altitudeM = null, rawGpsAltitudeM = null, hAccM = null,
			vAccM = null, speedMps = null, speedAccuracyMps = null, provider = "saved",
			quality = SampleQuality.COARSE, motionState = null, policy = null, bucketId = null,
			createdAt = time, clockDomainId = "boot"), continuity, sequence, false)
	private fun edge(first: Int, second: Int) = Location.distance(0.0, first / 1e7, 0.0, second / 1e7, LengthUnit.Meter)
}
