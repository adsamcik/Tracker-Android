package com.adsamcik.tracker.tracker.source.battery

import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Test

class QualitativeBatteryImpactEstimatorTest {
	@Test
	fun `generic plan preview never invents a percent per hour`() {
		val revision = 1L
		val location = LocationPlan(
			revision = revision,
			backend = LocationBackend.FUSED,
			mode = LocationMode.HIGH_ACCURACY,
			requestedIntervalMs = 1_000,
			minimumUpdateIntervalMs = 500,
			minimumDisplacementMeters = 0f,
			maximumBatchDelayMs = 1_000,
			preciseLocationAvailable = true,
		)
		val estimate = QualitativeBatteryImpactEstimator().estimate(
			AcquisitionPlanRevision(revision, "high", 100, mapOf(SourceKind.LOCATION to location)),
			comparisonBaselineId = "balanced",
		)

		estimate.estimatedPercentPerHour shouldBe null
		estimate.confidence shouldBe EstimateConfidence.LOW
		estimate.evidenceSource shouldBe EvidenceSource.GENERIC_PRIOR
		estimate.dominantDrivers shouldContain ImpactDriver.LOCATION
	}

	@Test
	fun `legacy cache label and passive broadcast have the same physical battery cost`() {
		val backoff = RetryBackoff(30_000L, 30 * 60_000L)
		fun estimate(mode: WifiMode) = QualitativeBatteryImpactEstimator().estimate(
			AcquisitionPlanRevision(
				revision = 1L,
				planId = mode.name,
				createdAtMs = 100L,
				plans = mapOf(
					SourceKind.WIFI to WifiPlan(
						revision = 1L,
						mode = mode,
						minimumAttemptIntervalMs = 15 * 60_000L,
						maximumAcceptableResultAgeMs = 10 * 60_000L,
						unchangedResultDedupeWindowMs = 30 * 60_000L,
						backoff = backoff,
					),
				),
			),
			comparisonBaselineId = null,
		)

		estimate(WifiMode.CACHED_ONLY).level shouldBe
			estimate(WifiMode.BROADCAST_DRIVEN).level
	}
}
