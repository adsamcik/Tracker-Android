package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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

	@Test
	fun `transient request stays payload free until a complete acknowledgement is available`() {
		val requested = requested()

		requested.acknowledgementFields().all { it == null } shouldBe true

		val acknowledged = requested.copy(
			state = SourceRunRetirementEntity.STATE_ACKNOWLEDGED,
			appliedRevision = 3L,
			callbackEntryBarrierSequence = 8L,
			lastSourceSequence = 8L,
			lastAdmissionOrdinal = 5L,
			failedAdmissionCount = 0L,
			registrationRemovalOutcome = "REMOVED",
			providerFlushOutcome = "NOT_SUPPORTED",
			providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
			appDrainComplete = true,
			stopStatus = "COMPLETE",
			updatedAtMs = requested.updatedAtMs + 1L,
		)

		acknowledged.state shouldBe SourceRunRetirementEntity.STATE_ACKNOWLEDGED
		acknowledged.registrationRemovalOutcome shouldBe "REMOVED"
		acknowledged.providerFlushOutcome shouldBe "NOT_SUPPORTED"
		acknowledged.appDrainComplete shouldBe true
		acknowledged.stopStatus shouldBe "COMPLETE"
	}

	private fun SourceRunRetirementEntity.acknowledgementFields(): List<Any?> = listOf(
		appliedRevision,
		callbackEntryBarrierSequence,
		lastSourceSequence,
		lastAdmissionOrdinal,
		failedAdmissionCount,
		unresolvedSequenceStart,
		unresolvedSequenceEnd,
		registrationRemovalOutcome,
		providerFlushOutcome,
		providerCoverage,
		appDrainComplete,
		stopStatus,
	)

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
