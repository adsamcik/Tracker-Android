package com.adsamcik.tracker.stats.engine.segment

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.TransportMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TransportModeClassifierTest {

	private fun classify(
		avgSpeedMps: Float = 0f,
		maxSpeedMps: Float = avgSpeedMps,
		totalSteps: Int = 0,
		durationMs: Long = 600_000L, // 10 min default
		primaryActivity: DetectedActivityType? = null,
		totalDistanceM: Float = avgSpeedMps * (durationMs / 1000f),
	): TransportMode = TransportModeClassifier.classify(
		avgSpeedMps, maxSpeedMps, totalSteps, durationMs, primaryActivity, totalDistanceM
	)

	@Nested
	inner class WalkDetection {
		@Test
		fun `high step rate and slow speed classifies as WALK`() {
			// 90 steps/min at 1.2 m/s
			classify(
				avgSpeedMps = 1.2f,
				totalSteps = 900,
				durationMs = 600_000L,
			) shouldBe TransportMode.WALK
		}

		@Test
		fun `moderate step rate with slow speed classifies as WALK`() {
			// ~50 steps/min at 1.0 m/s (below full WALK threshold but above half)
			classify(
				avgSpeedMps = 1.0f,
				totalSteps = 500,
				durationMs = 600_000L,
			) shouldBe TransportMode.WALK
		}
	}

	@Nested
	inner class RunDetection {
		@Test
		fun `very high step rate classifies as RUN`() {
			// 140 steps/min at 3.5 m/s
			classify(
				avgSpeedMps = 3.5f,
				totalSteps = 1400,
				durationMs = 600_000L,
			) shouldBe TransportMode.RUN
		}

		@Test
		fun `high step rate at moderate speed classifies as RUN`() {
			// 125 steps/min at 5.0 m/s
			classify(
				avgSpeedMps = 5.0f,
				totalSteps = 1250,
				durationMs = 600_000L,
			) shouldBe TransportMode.RUN
		}
	}

	@Nested
	inner class CycleDetection {
		@Test
		fun `bicycle activity with medium speed classifies as CYCLE`() {
			classify(
				avgSpeedMps = 6.0f,
				primaryActivity = DetectedActivityType.ON_BICYCLE,
			) shouldBe TransportMode.CYCLE
		}

		@Test
		fun `medium speed with no steps and no activity classifies as CYCLE`() {
			classify(
				avgSpeedMps = 5.0f,
				totalSteps = 0,
			) shouldBe TransportMode.CYCLE
		}

		@Test
		fun `bicycle activity too fast rejects CYCLE`() {
			val result = classify(
				avgSpeedMps = 15.0f,
				primaryActivity = DetectedActivityType.ON_BICYCLE,
			)
			result shouldBe TransportMode.DRIVE
		}
	}

	@Nested
	inner class DriveDetection {
		@Test
		fun `in-vehicle activity classifies as DRIVE`() {
			classify(
				avgSpeedMps = 15.0f,
				primaryActivity = DetectedActivityType.IN_VEHICLE,
			) shouldBe TransportMode.DRIVE
		}

		@Test
		fun `high speed with few steps classifies as DRIVE`() {
			// 8 m/s with only 20 steps in 10 minutes
			classify(
				avgSpeedMps = 8.0f,
				totalSteps = 20,
				durationMs = 600_000L,
			) shouldBe TransportMode.DRIVE
		}

		@Test
		fun `slow in-vehicle still classifies as DRIVE`() {
			classify(
				avgSpeedMps = 2.0f,
				primaryActivity = DetectedActivityType.IN_VEHICLE,
			) shouldBe TransportMode.DRIVE
		}
	}

	@Nested
	inner class HighSpeedDetection {
		@Test
		fun `very high max speed classifies as HIGH_SPEED_RAIL`() {
			classify(
				avgSpeedMps = 40.0f,
				maxSpeedMps = 55.0f,
			) shouldBe TransportMode.HIGH_SPEED_RAIL
		}
	}

	@Nested
	inner class EdgeCases {
		@Test
		fun `zero duration returns UNKNOWN`() {
			classify(durationMs = 0L) shouldBe TransportMode.UNKNOWN
		}

		@Test
		fun `negative duration returns UNKNOWN`() {
			classify(durationMs = -1L) shouldBe TransportMode.UNKNOWN
		}

		@Test
		fun `no data at all returns UNKNOWN`() {
			classify(
				avgSpeedMps = 0f,
				totalSteps = 0,
				primaryActivity = null,
			) shouldBe TransportMode.UNKNOWN
		}

		@Test
		fun `ambiguous low speed with some steps returns UNKNOWN or WALK`() {
			// 10 steps/min at 0.5 m/s - below all thresholds
			val result = classify(
				avgSpeedMps = 0.5f,
				totalSteps = 100,
				durationMs = 600_000L,
			)
			// Low step rate doesn't meet walk threshold
			result shouldBe TransportMode.UNKNOWN
		}

		@Test
		fun `still activity returns UNKNOWN`() {
			classify(
				avgSpeedMps = 0.1f,
				primaryActivity = DetectedActivityType.STILL,
			) shouldBe TransportMode.UNKNOWN
		}
	}
}
