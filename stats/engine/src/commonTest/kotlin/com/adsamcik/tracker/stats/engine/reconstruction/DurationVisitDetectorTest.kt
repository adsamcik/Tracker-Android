package com.adsamcik.tracker.stats.engine.reconstruction

import com.adsamcik.tracker.stats.api.reconstruction.ObservationHealth
import com.adsamcik.tracker.stats.api.reconstruction.TrajectoryEstimateKind
import com.adsamcik.tracker.stats.api.reconstruction.TrajectoryStateEstimate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurationVisitDetectorTest {
	@Test
	fun durationNotSampleCountControlsVisit() {
		val denseShort = listOf(0L, 10_000L, 20_000L, 30_000L).map(::state)
		val sparseLong = listOf(0L, 90_000L, 180_000L).map(::state)
		val detector = DurationVisitDetector()

		assertEquals(0, detector.detect(denseShort).size)
		assertEquals(1, detector.detect(sparseLong).size)
	}

	@Test
	fun monotonicDurationSurvivesWallClockRollback() {
		val states = listOf(
			state(timeMs = 100_000L, elapsedRealtimeNanos = 0L),
			state(timeMs = 10_000L, elapsedRealtimeNanos = 180_000_000_000L),
		)

		assertEquals(1, DurationVisitDetector().detect(states).size)
	}

	@Test
	fun visitCannotSpanBootDomains() {
		val states = listOf(
			state(timeMs = 0L, elapsedRealtimeNanos = 100_000_000_000L, bootDomain = "boot-1"),
			state(timeMs = 180_000L, elapsedRealtimeNanos = 1_000_000_000L, bootDomain = "boot-2"),
		)

		assertEquals(0, DurationVisitDetector().detect(states).size)
	}

	@Test
	fun datelineClusterHasDatelineCentroid() {
		val states = listOf(
			state(timeMs = 0L, longitudeE7 = 1_799_999_000),
			state(timeMs = 180_000L, longitudeE7 = -1_799_999_000),
		)

		val visit = DurationVisitDetector().detect(states).single()

		assertTrue(abs(visit.centroidLonE7) > 1_790_000_000)
	}

	private fun state(
		timeMs: Long,
		elapsedRealtimeNanos: Long = timeMs * 1_000_000L,
		longitudeE7: Int = 140_000_000,
		bootDomain: String = "boot-1",
	) = TrajectoryStateEstimate(
		sourceId = "fix-$timeMs",
		epochMs = timeMs,
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		clockDomainId = "session-1",
		bootClockDomainId = bootDomain,
		latitudeE7 = 500_000_000,
		longitudeE7 = longitudeE7,
		velocityEastMps = 0.0,
		velocityNorthMps = 0.0,
		positionCovarianceEastEastM2 = 9.0,
		positionCovarianceEastNorthM2 = 0.0,
		positionCovarianceNorthNorthM2 = 9.0,
		stationaryProbability = 0.95,
		kind = TrajectoryEstimateKind.SMOOTHED,
		observationHealth = ObservationHealth.HEALTHY,
		observationWeight = 1.0,
	)
}
