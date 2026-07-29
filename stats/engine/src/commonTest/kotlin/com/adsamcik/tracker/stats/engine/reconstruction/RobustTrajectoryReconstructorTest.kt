package com.adsamcik.tracker.stats.engine.reconstruction

import com.adsamcik.tracker.stats.api.reconstruction.LocationReconstructionObservation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RobustTrajectoryReconstructorTest {
	private val reconstructor = RobustTrajectoryReconstructor()

	@Test
	fun reconstructionIsDeterministicAndPreservesLineage() {
		val observations = straightTrace()

		val first = reconstructor.reconstruct(observations)
		val second = reconstructor.reconstruct(observations)

		assertEquals(first, second)
		assertEquals(observations.map { it.sourceId }, first.filtered.map { it.sourceId })
		assertEquals(first.filtered.size, first.smoothed.size)
	}

	@Test
	fun robustUpdateLimitsSinglePositionOutlier() {
		val observations = straightTrace().toMutableList()
		observations[3] = observations[3].copy(
			latitudeE7 = observations[3].latitudeE7 + 9_000,
			horizontalAccuracyM = 5.0,
		)

		val result = reconstructor.reconstruct(observations)
		val outlierEstimate = result.filtered[3]
		val reportedOutlier = observations[3]

		assertTrue(abs(outlierEstimate.latitudeE7 - reportedOutlier.latitudeE7) > 2_000)
		assertTrue(result.smoothed.all { it.positionCovarianceEastEastM2 >= 0.0 })
		assertTrue(result.smoothed.all { it.positionCovarianceNorthNorthM2 >= 0.0 })
	}

	@Test
	fun rtsUsesFutureEvidenceToReduceMiddleUncertainty() {
		val result = reconstructor.reconstruct(straightTrace())
		val middle = result.filtered.size / 2

		assertTrue(
			result.smoothed[middle].positionCovarianceEastEastM2 <=
				result.filtered[middle].positionCovarianceEastEastM2,
		)
		assertTrue(
			result.smoothed[middle].positionCovarianceNorthNorthM2 <=
				result.filtered[middle].positionCovarianceNorthNorthM2,
		)
	}

	@Test
	fun explicitBootChangeStartsANewIndependentSegment() {
		val first = LocationReconstructionObservation(
			sourceId = "before-reboot",
			epochMs = 10_000L,
			elapsedRealtimeNanos = 9_000_000_000L,
			bootClockDomainId = "boot-1",
			latitudeE7 = 500_000_000,
			longitudeE7 = 140_000_000,
			horizontalAccuracyM = 5.0,
		)
		val afterReboot = first.copy(
			sourceId = "after-reboot",
			epochMs = 11_000L,
			elapsedRealtimeNanos = 500_000_000L,
			bootClockDomainId = "boot-2",
			longitudeE7 = 141_000_000,
		)

		val result = reconstructor.reconstruct(listOf(first, afterReboot))

		assertEquals(emptySet(), result.rejectedSourceIds)
		assertEquals(listOf("before-reboot", "after-reboot"), result.filtered.map { it.sourceId })
		assertEquals(afterReboot.longitudeE7, result.filtered.last().longitudeE7)
		assertEquals(afterReboot.longitudeE7, result.smoothed.last().longitudeE7)
	}

	@Test
	fun datelineCrossingDoesNotSendEstimateAroundTheWorld() {
		val first = LocationReconstructionObservation(
			sourceId = "west-edge",
			epochMs = 0L,
			elapsedRealtimeNanos = 0L,
			bootClockDomainId = "boot-1",
			latitudeE7 = 0,
			longitudeE7 = 1_799_999_000,
			horizontalAccuracyM = 5.0,
		)
		val crossing = first.copy(
			sourceId = "east-edge",
			epochMs = 1_000L,
			elapsedRealtimeNanos = 1_000_000_000L,
			longitudeE7 = -1_799_999_000,
		)

		val result = reconstructor.reconstruct(listOf(first, crossing))
		val longitudeErrorDegrees = abs(
			wrappedLongitudeDeltaDegrees(
				result.filtered.last().longitudeE7 / 1e7,
				crossing.longitudeE7 / 1e7,
			),
		)

		assertEquals(emptySet(), result.rejectedSourceIds)
		assertTrue(longitudeErrorDegrees < 0.01)
	}

	private fun straightTrace(): List<LocationReconstructionObservation> =
		(0 until 7).map { index ->
			LocationReconstructionObservation(
				sourceId = "fix-$index",
				epochMs = index * 1_000L,
				elapsedRealtimeNanos = index * 1_000_000_000L,
				bootClockDomainId = "boot-1",
				latitudeE7 = 500_000_000,
				longitudeE7 = 140_000_000 + index * 150,
				horizontalAccuracyM = 5.0,
				platformSpeedMps = 1.1,
				platformSpeedAccuracyMps = 0.3,
				bearingDeg = 90.0,
				bearingAccuracyDeg = 5.0,
				activity = "walking",
			)
		}
}
