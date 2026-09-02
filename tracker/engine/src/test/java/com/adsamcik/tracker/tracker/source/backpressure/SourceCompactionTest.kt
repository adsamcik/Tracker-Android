package com.adsamcik.tracker.tracker.source.backpressure

import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class SourceCompactionTest {
	@Test
	fun `step windows merge only when source sequence is contiguous`() {
		val merged = SourceCompaction.mergeSteps(step(1, 2, 100, 105), step(3, 4, 105, 112))

		merged.deltaCount shouldBe 12L
		merged.firstProviderSequence shouldBe 1L
		merged.lastProviderSequence shouldBe 4L

		shouldThrow<IllegalArgumentException> {
			SourceCompaction.mergeSteps(step(1, 2, 100, 105), step(4, 5, 105, 112))
		}
	}

	@Test
	fun `pressure sufficient statistics preserve mean and sequence range`() {
		val merged = SourceCompaction.mergePressure(
			pressure(1, 2, 2, 1000.0),
			pressure(3, 4, 2, 1010.0),
		)

		merged.sampleCount shouldBe 4
		merged.meanHectopascals.shouldBeExactly(1005.0)
		merged.sumSquaredDeviations.shouldBeExactly(100.0)
		merged.firstProviderSequence shouldBe 1L
		merged.lastProviderSequence shouldBe 4L
	}

	@Test
	fun `qualified Pressure windows cannot silently lose fit and coverage during compaction`() {
		val legacy = pressure(3, 4, 2, 1_010.0)
		val qualified = pressure(1, 2, 2, 1_000.0).copy(
			firstHectopascals = 1_000f,
			lastHectopascals = 1_000f,
			slopeHectopascalsPerSecond = 0.0,
			rSquared = null,
			sensorAccuracy = PressureSensorAccuracy.HIGH,
			effectiveSamplePeriodMicros = 1,
			effectiveMaximumReportLatencyMicros = 0,
			targetWindowDurationNanos = 10L,
			expectedSampleCount = 1,
			maximumInterSampleGapNanos = 10L,
			closureKind = PressureWindowClosureKind.SOURCE_BOUNDARY,
		)

		shouldThrow<IllegalArgumentException> {
			SourceCompaction.mergePressure(qualified, legacy)
		}
	}

	private fun step(firstSequence: Long, lastSequence: Long, firstCount: Long, lastCount: Long) =
		StepCounterWindowPayload(
			bootClockDomainId = "boot",
			firstCumulativeCount = firstCount,
			lastCumulativeCount = lastCount,
			deltaCount = lastCount - firstCount,
			windowStartElapsedRealtimeNanos = firstSequence * 10,
			windowEndElapsedRealtimeNanos = lastSequence * 10,
			firstProviderSequence = firstSequence,
			lastProviderSequence = lastSequence,
			baselineReset = false,
		)

	private fun pressure(first: Long, last: Long, count: Int, mean: Double) = PressureWindowPayload(
		sampleCount = count,
		meanHectopascals = mean,
		sumSquaredDeviations = 0.0,
		minimumHectopascals = mean.toFloat(),
		maximumHectopascals = mean.toFloat(),
		windowStartElapsedRealtimeNanos = first * 10,
		windowEndElapsedRealtimeNanos = last * 10,
		firstProviderSequence = first,
		lastProviderSequence = last,
	)
}
