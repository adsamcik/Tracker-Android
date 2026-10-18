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

		val acknowledged = acknowledged(SourceDestinationOwnerEntity.SOURCE_PRESSURE)

		acknowledged.state shouldBe SourceRunRetirementEntity.STATE_ACKNOWLEDGED
		acknowledged.registrationRemovalOutcome shouldBe "REMOVED"
		acknowledged.providerFlushOutcome shouldBe "NOT_SUPPORTED"
		acknowledged.appDrainComplete shouldBe true
		acknowledged.stopStatus shouldBe "COMPLETE"
	}

	@Test
	fun `cleanup-only completion is terminal and cannot carry product acknowledgement fields`() {
		val cleanupOnly = requested().copy(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			state = SourceRunRetirementEntity.STATE_CLEANUP_ONLY_COMPLETED,
		)

		cleanupOnly.acknowledgementFields().all { it == null } shouldBe true
		shouldThrow<IllegalArgumentException> {
			cleanupOnly.copy(stopStatus = "COMPLETE")
		}
		shouldThrow<IllegalArgumentException> {
			cleanupOnly.copy(sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION)
		}
	}

	@Test
	fun `raw retirement accepts zero admission high water without inventing observations`() {
		for (sequence in listOf(null, 0L)) {
			val empty = acknowledged(SourceDestinationOwnerEntity.SOURCE_ACTIVITY).copy(
				callbackEntryBarrierSequence = 0L,
				lastSourceSequence = sequence,
				lastAdmissionOrdinal = 0L,
			)

			empty.raw().validatedOrNull() shouldBe empty
		}
	}

	@Test
	fun `raw retirement still rejects negative high water and incorrect SQL storage types`() {
		val raw = acknowledged(SourceDestinationOwnerEntity.SOURCE_ACTIVITY).raw()

		raw.validatedOrNull() shouldBe acknowledged(SourceDestinationOwnerEntity.SOURCE_ACTIVITY)
		raw.copy(lastAdmissionOrdinal = -1L).validatedOrNull() shouldBe null
		val malformedTypes = requireNotNull(raw.storageClassSignature).split('|').toMutableList()
		malformedTypes[14] = "real"
		raw.copy(lastAdmissionOrdinal = 0L, storageClassSignature = malformedTypes.joinToString("|"))
			.validatedOrNull() shouldBe null
	}

	@Test
	fun `Steps and non-Steps retirement state corresponds exactly to process restart status`() {
		shouldThrow<IllegalArgumentException> {
			acknowledged(SourceDestinationOwnerEntity.SOURCE_STEPS).copy(
				stopStatus = "PROCESS_RESTARTED",
			)
		}
		shouldThrow<IllegalArgumentException> {
			acknowledged(SourceDestinationOwnerEntity.SOURCE_LOCATION).copy(
				state = SourceRunRetirementEntity.STATE_INTERRUPTED,
			)
		}

		val interruptedLocation = acknowledged(SourceDestinationOwnerEntity.SOURCE_LOCATION).copy(
			state = SourceRunRetirementEntity.STATE_INTERRUPTED,
			stopStatus = "PROCESS_RESTARTED",
		)
		interruptedLocation.state shouldBe SourceRunRetirementEntity.STATE_INTERRUPTED
		interruptedLocation.stopStatus shouldBe "PROCESS_RESTARTED"
	}

	private fun SourceRunRetirementEntity.raw(): SourceRunRetirementEntity.RawSourceRunRetirement {
		val values = listOf(logicalTrackingId, serviceRunId, sourceKind, sourceInstanceId,
			registrationGeneration, actionId, attemptCount, leaseGeneration, cutoffElapsedRealtimeNanos,
			cutoffWallTimeMs, state, appliedRevision, callbackEntryBarrierSequence, lastSourceSequence,
			lastAdmissionOrdinal, failedAdmissionCount, unresolvedSequenceStart, unresolvedSequenceEnd,
			registrationRemovalOutcome, providerFlushOutcome, providerCoverage, appDrainComplete,
			stopStatus, updatedAtMs)
		val signature = values.joinToString("|") { value ->
			when (value) {
				null -> "null"
				is String -> "text"
				else -> "integer"
			}
		}
		return SourceRunRetirementEntity.RawSourceRunRetirement(
			storageClassSignature = signature,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceKind = sourceKind.toLong(),
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = registrationGeneration,
			actionId = actionId,
			attemptCount = attemptCount.toLong(),
			leaseGeneration = leaseGeneration,
			cutoffElapsedRealtimeNanos = cutoffElapsedRealtimeNanos,
			cutoffWallTimeMs = cutoffWallTimeMs,
			state = state,
			appliedRevision = appliedRevision,
			callbackEntryBarrierSequence = callbackEntryBarrierSequence,
			lastSourceSequence = lastSourceSequence,
			lastAdmissionOrdinal = lastAdmissionOrdinal,
			failedAdmissionCount = failedAdmissionCount,
			unresolvedSequenceStart = unresolvedSequenceStart,
			unresolvedSequenceEnd = unresolvedSequenceEnd,
			registrationRemovalOutcome = registrationRemovalOutcome,
			providerFlushOutcome = providerFlushOutcome,
			providerCoverage = providerCoverage,
			appDrainComplete = appDrainComplete?.let { if (it) { 1L } else { 0L } },
			stopStatus = stopStatus,
			updatedAtMs = updatedAtMs,
		)
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

	private fun acknowledged(sourceKind: Int) = requested().copy(
		sourceKind = sourceKind,
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
		updatedAtMs = 2_001L,
	)
}
