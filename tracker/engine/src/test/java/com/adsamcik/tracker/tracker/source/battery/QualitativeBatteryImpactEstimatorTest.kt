package com.adsamcik.tracker.tracker.source.battery

import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceKind
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
}
