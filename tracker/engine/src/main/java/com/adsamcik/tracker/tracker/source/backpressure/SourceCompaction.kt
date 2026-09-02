package com.adsamcik.tracker.tracker.source.backpressure

import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload

object SourceCompaction {
	fun mergeSteps(
		first: StepCounterWindowPayload,
		second: StepCounterWindowPayload,
	): StepCounterWindowPayload {
		require(first.bootClockDomainId == second.bootClockDomainId)
		require(!first.baselineReset && !second.baselineReset)
		require(first.lastProviderSequence + 1L == second.firstProviderSequence)
		require(first.windowEndElapsedRealtimeNanos <= second.windowStartElapsedRealtimeNanos)
		require(first.lastCumulativeCount <= second.firstCumulativeCount)
		return StepCounterWindowPayload(
			bootClockDomainId = first.bootClockDomainId,
			firstCumulativeCount = first.firstCumulativeCount,
			lastCumulativeCount = second.lastCumulativeCount,
			deltaCount = first.deltaCount + second.deltaCount,
			windowStartElapsedRealtimeNanos = first.windowStartElapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = second.windowEndElapsedRealtimeNanos,
			firstProviderSequence = first.firstProviderSequence,
			lastProviderSequence = second.lastProviderSequence,
			baselineReset = false,
		)
	}

	fun mergePressure(
		first: PressureWindowPayload,
		second: PressureWindowPayload,
	): PressureWindowPayload {
		require(first.isLegacyPressureShape() && second.isLegacyPressureShape()) {
			"Qualified Pressure windows cannot be compacted without preserving regression evidence"
		}
		require(first.sampleCount > 0 && second.sampleCount > 0)
		require(first.lastProviderSequence + 1L == second.firstProviderSequence)
		require(first.windowEndElapsedRealtimeNanos <= second.windowStartElapsedRealtimeNanos)
		val totalCount = first.sampleCount + second.sampleCount
		val meanDelta = second.meanHectopascals - first.meanHectopascals
		val combinedMean = (
			first.meanHectopascals * first.sampleCount + second.meanHectopascals * second.sampleCount
		) / totalCount
		val combinedDeviation = first.sumSquaredDeviations + second.sumSquaredDeviations +
			meanDelta * meanDelta * first.sampleCount * second.sampleCount / totalCount
		return PressureWindowPayload(
			sampleCount = totalCount,
			meanHectopascals = combinedMean,
			sumSquaredDeviations = combinedDeviation,
			minimumHectopascals = minOf(first.minimumHectopascals, second.minimumHectopascals),
			maximumHectopascals = maxOf(first.maximumHectopascals, second.maximumHectopascals),
			windowStartElapsedRealtimeNanos = first.windowStartElapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = second.windowEndElapsedRealtimeNanos,
			firstProviderSequence = first.firstProviderSequence,
			lastProviderSequence = second.lastProviderSequence,
		)
	}
}

private fun PressureWindowPayload.isLegacyPressureShape(): Boolean =
	hasLegacyPressureStatistics() &&
		hasLegacyPressureProviderEvidence() &&
		hasLegacyPressureCoverage()

private fun PressureWindowPayload.hasLegacyPressureStatistics(): Boolean =
	firstHectopascals == null &&
		lastHectopascals == null &&
		slopeHectopascalsPerSecond == null &&
		rSquared == null

private fun PressureWindowPayload.hasLegacyPressureProviderEvidence(): Boolean =
	sensorAccuracy == PressureSensorAccuracy.LEGACY_UNAVAILABLE &&
		effectiveSamplePeriodMicros == null &&
		effectiveMaximumReportLatencyMicros == null

private fun PressureWindowPayload.hasLegacyPressureCoverage(): Boolean =
	targetWindowDurationNanos == null &&
		expectedSampleCount == null &&
		maximumInterSampleGapNanos == null &&
		closureKind == PressureWindowClosureKind.LEGACY_UNAVAILABLE
