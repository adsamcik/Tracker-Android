package com.adsamcik.tracker.tracker.source.battery

import io.kotest.assertions.throwables.shouldThrow
import org.junit.Test

class BatteryImpactEstimateTest {
	@Test
	fun `incremental estimate rejects observations without a counterfactual`() {
		shouldThrow<IllegalArgumentException> {
			estimate(
				target = EstimateTarget.ESTIMATED_INCREMENTAL_TRACKER_DRAIN,
				source = EvidenceSource.ON_DEVICE_OBSERVATIONS,
			)
		}
	}

	@Test
	fun `incremental estimate permits validated counterfactual evidence`() {
		estimate(
			target = EstimateTarget.ESTIMATED_INCREMENTAL_TRACKER_DRAIN,
			source = EvidenceSource.VALIDATED_COUNTERFACTUAL,
		)
	}

	private fun estimate(
		target: EstimateTarget,
		source: EvidenceSource,
	) = BatteryImpactEstimate(
		level = ImpactLevel.MODERATE,
		estimatedPercentPerHour = 0.5..1.0,
		estimateTarget = target,
		candidatePlanId = "balanced-v1",
		comparisonBaselineId = "legacy-balanced",
		evidenceSource = source,
		sampleCount = 10,
		observationDurationMs = 3_600_000L,
		confidence = EstimateConfidence.MEDIUM,
		uncertainty = 0.2..0.5,
		dominantDrivers = listOf(ImpactDriver.LOCATION),
		assumptions = listOf(ImpactAssumption("screen_off")),
		calibrationVersion = 1,
	)
}

