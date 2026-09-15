package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.throwables.shouldThrow
import org.junit.Test

class SourceRunRetirementEntityTest {
	@Test
	fun `requested retirement cannot fabricate acknowledgement fields`() {
		shouldThrow<IllegalArgumentException> {
			requested().copy(
				stopStatus = "COMPLETE",
			)
		}
	}

	private fun requested() = SourceRunRetirementEntity(
		logicalTrackingId = "logical",
		serviceRunId = "run",
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		sourceInstanceId = "pressure-instance",
		registrationGeneration = 7L,
		actionId = "stop-pressure",
		attemptCount = 1,
		leaseGeneration = 4L,
		cutoffElapsedRealtimeNanos = 1_000L,
		cutoffWallTimeMs = 2_000L,
		state = SourceRunRetirementEntity.STATE_REQUESTED,
		appliedRevision = null,
		callbackEntryBarrierSequence = null,
		lastSourceSequence = null,
		lastAdmissionOrdinal = null,
		failedAdmissionCount = null,
		unresolvedSequenceStart = null,
		unresolvedSequenceEnd = null,
		registrationRemovalOutcome = null,
		providerFlushOutcome = null,
		providerCoverage = null,
		appDrainComplete = null,
		stopStatus = null,
		updatedAtMs = 2_000L,
	)
}
