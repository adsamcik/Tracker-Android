package com.adsamcik.tracker.tracker.source.lifecycle

import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload

internal fun SourceEventWalEntity.sourceLifecycleStepsWalOwnerKey() = StepsCountDomainOwnerLookupKey(
	ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
	ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
		admissionOrdinal,
		eventId,
	),
	ownerRevision = 1L,
)

internal fun StepFactRevisionEntity.sourceLifecycleStepsFactOwnerKey() = StepsCountDomainOwnerLookupKey(
	ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
	ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
		writerProjectionId,
		writerProjectionVersion,
		logicalFactId,
	),
	ownerRevision = semanticRevision,
)

internal fun StepCounterWindowPayload.recordingDisqualification(
	wal: SourceEventWalEntity,
): SourceProofReason? {
	if (boundaryKind == StepBoundaryKind.BASELINE ||
		boundaryKind == StepBoundaryKind.COUNTER_RESET ||
		boundaryKind == StepBoundaryKind.LEGACY_AMBIGUOUS
	) {
		return SourceProofReason.BASELINE_ONLY
	}
	if (deltaCount == 0L) {
		return SourceProofReason.ZERO_DELTA
	}
	return if (hasConsistentStepCounts() && hasMatchingStepClock(wal) && hasMatchingStepSequence(wal)) {
		null
	} else {
		SourceProofReason.MALFORMED_EVIDENCE
	}
}

private fun StepCounterWindowPayload.hasConsistentStepCounts(): Boolean = deltaCount > 0L &&
	firstCumulativeCount >= 0L && lastCumulativeCount >= firstCumulativeCount &&
	deltaCount == lastCumulativeCount - firstCumulativeCount

private fun StepCounterWindowPayload.hasMatchingStepClock(wal: SourceEventWalEntity): Boolean =
	windowStartElapsedRealtimeNanos >= 0L && windowEndElapsedRealtimeNanos >= windowStartElapsedRealtimeNanos &&
		windowEndElapsedRealtimeNanos == wal.observedElapsedNanos && bootClockDomainId == wal.clockDomainId

private fun StepCounterWindowPayload.hasMatchingStepSequence(wal: SourceEventWalEntity): Boolean =
	firstProviderSequence >= 0L && lastProviderSequence >= firstProviderSequence &&
		lastProviderSequence == wal.sourceSequence
