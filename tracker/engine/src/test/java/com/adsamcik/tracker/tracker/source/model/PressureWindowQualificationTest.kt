package com.adsamcik.tracker.tracker.source.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class PressureWindowQualificationTest {
	@Test
	fun `target window with expected count span and cadence is complete`() {
		PressureWindowQualification.classify(payload()) shouldBe
			PressureWindowQualification.COMPLETE
	}

	@Test
	fun `source boundary is partial even with otherwise complete coverage`() {
		PressureWindowQualification.classify(
			payload().copy(closureKind = PressureWindowClosureKind.SOURCE_BOUNDARY),
		) shouldBe PressureWindowQualification.PARTIAL
	}

	@Test
	fun `insufficient count span or cadence is partial`() {
		listOf(
			payload().copy(sampleCount = 3, lastProviderSequence = 3L),
			payload().copy(windowEndElapsedRealtimeNanos = 149_999_999L),
			payload().copy(maximumInterSampleGapNanos = 100_000_000L),
		).forEach { candidate ->
			PressureWindowQualification.classify(candidate) shouldBe
				PressureWindowQualification.PARTIAL
		}
	}

	private fun payload() = PressureWindowPayload(
		sampleCount = 4,
		meanHectopascals = 1_001.5,
		sumSquaredDeviations = 5.0,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_003f,
		windowStartElapsedRealtimeNanos = 0L,
		windowEndElapsedRealtimeNanos = 150_000_000L,
		firstProviderSequence = 1L,
		lastProviderSequence = 4L,
		firstHectopascals = 1_000f,
		lastHectopascals = 1_003f,
		slopeHectopascalsPerSecond = 20.0,
		rSquared = 1.0,
		sensorAccuracy = PressureSensorAccuracy.HIGH,
		effectiveSamplePeriodMicros = 50_000,
		effectiveMaximumReportLatencyMicros = 200_000,
		targetWindowDurationNanos = 200_000_000L,
		expectedSampleCount = 4,
		maximumInterSampleGapNanos = 50_000_000L,
		closureKind = PressureWindowClosureKind.TARGET_ELAPSED,
	)
}
