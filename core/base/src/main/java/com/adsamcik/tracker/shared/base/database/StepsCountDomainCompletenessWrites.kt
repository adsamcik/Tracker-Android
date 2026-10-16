package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore.Companion.COMPLETENESS_COVERAGE_VERSION
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore.Companion.MAX_COMPLETENESS_TIMELINE
import android.database.sqlite.SQLiteException
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsSessionCompletenessIntegrity

@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
private suspend fun StepsCountDomainStore.recordSessionCompletenessAuthenticated(
	row: SourceSessionCompletenessEntity,
	retirementEvidence: StepsCountDomainRetirementEvidence,
): StepsCountDomainWriteResult {
	if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS) {
		return StepsCountDomainWriteResult.NOT_APPLICABLE
	}
	writeSchemaFailure()?.let { return it }
	if (!row.hasTerminalRetirement(retirementEvidence)) {
		return StepsCountDomainWriteResult.AUTHORITY_PENDING
	}
	val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
		row.logicalTrackingId,
		row.serviceRunId,
	)
	val ownerEffectChecksum =
		StepsCountDomainReceiptIntegrity.completenessEffectChecksum(row)
	val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
		row.logicalTrackingId,
		row.serviceRunId,
		row.sourceInstanceId,
		row.registrationGeneration,
	)
	val ownerRevision = StepsCountDomainReceiptIntegrity.completenessOwnerRevision(row)
	val timeline = authenticatedStepsCompletenessTimeline(
		row.logicalTrackingId,
		row.serviceRunId,
	) ?: return StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
	val orderedTimeline =
		timeline.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
	val rowIndex = orderedTimeline.indexOfFirst { candidate ->
		candidate.sourceInstanceId == row.sourceInstanceId &&
			candidate.registrationGeneration == row.registrationGeneration
	}
	if (rowIndex < 0 || orderedTimeline[rowIndex] != row) {
		return StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
	}
	val timelinePrefix = orderedTimeline.take(rowIndex + 1)
	val timelineChecksum =
		StepsCountDomainReceiptIntegrity.registrationTimelineChecksum(timelinePrefix)
	val timelineWithinBound = orderedTimeline.size <= MAX_COMPLETENESS_TIMELINE
	val priorTimelineIsComplete = timelineWithinBound &&
		hasAuthenticPriorCompletenessOwners(timelinePrefix, scopeIdentity)
	val exactComplete = row.hasExactCompleteRetirement(retirementEvidence) &&
		timelineWithinBound &&
		StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
			timelinePrefix,
			row.logicalTrackingId,
			row.serviceRunId,
		) && priorTimelineIsComplete
	val marker = completenessMarker(
		row = row,
		ownerIdentity = ownerIdentity,
		ownerRevision = ownerRevision,
		retirementEvidence = retirementEvidence,
		timelineChecksum = timelineChecksum,
		exactComplete = exactComplete,
	)
	val admissionOrdinal = row.lastAdmissionOrdinal
	if (!exactComplete || admissionOrdinal == null) {
		return appendUnprovenSessionCompleteness(
			row, scopeIdentity, ownerIdentity, ownerRevision, ownerEffectChecksum, marker,
		)
	}
	val wal = try {
		database.sourceEventWalDao().getByAdmissionOrdinal(admissionOrdinal)
	} catch (_: SQLiteException) {
		return StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
	}
		?: return appendUnprovenSessionCompleteness(
			row, scopeIdentity, ownerIdentity, ownerRevision, ownerEffectChecksum, marker,
		)
	if (!wal.matchesTerminalStepsCompleteness(row)) {
		return StepsCountDomainWriteResult.IDENTITY_CONFLICT
	}
	val walOwner = loadExactOwner(
		StepsCountDomainOwnerLookupKey(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
				admissionOrdinal,
				wal.eventId,
			),
			1L,
		),
		scopeIdentity,
	) ?: return if (canonicalProjectionCommittedThrough(admissionOrdinal)) {
		appendUnprovenSessionCompleteness(
			row, scopeIdentity, ownerIdentity, ownerRevision, ownerEffectChecksum, marker,
		)
	} else {
		StepsCountDomainWriteResult.AUTHORITY_PENDING
	}
	if (walOwner.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN) {
		return appendUnprovenSessionCompleteness(
			row, scopeIdentity, ownerIdentity, ownerRevision, ownerEffectChecksum, marker,
		)
	}
	val walReceipt = walOwner.receipt ?: return StepsCountDomainWriteResult.IDENTITY_CONFLICT
	if (!walOwner.isAuthentic() || walOwner.owner.scopeIdentity != scopeIdentity) {
		return StepsCountDomainWriteResult.IDENTITY_CONFLICT
	}
	val receipt = nativeReceiptFrom(
		source = walReceipt,
		ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
		scopeIdentity = scopeIdentity,
		ownerIdentity = ownerIdentity,
		ownerRevision = ownerRevision,
		coverageKind = StepsCountDomainReceiptEntity.COVERAGE_COMPLETE_RUN,
		coverageVersion = COMPLETENESS_COVERAGE_VERSION,
		effectChecksum = ownerEffectChecksum,
		completionEvidenceChecksum = marker.evidenceChecksum,
	)
	return appendBoundOwner(
		receipt,
		scopeIdentity,
		ownerEffectChecksum,
		row.updatedAtMs,
		marker,
	)
}

private fun StepsCountDomainStore.appendUnprovenSessionCompleteness(
	row: SourceSessionCompletenessEntity,
	scopeIdentity: String,
	ownerIdentity: String,
	ownerRevision: Long,
	ownerEffectChecksum: String,
	marker: StepsCountDomainCompletenessMarkerEntity,
): StepsCountDomainWriteResult = appendTerminalUnproven(
	ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
	scopeIdentity = scopeIdentity,
	ownerIdentity = ownerIdentity,
	ownerRevision = ownerRevision,
	ownerEffectChecksum = ownerEffectChecksum,
	linkedAtMs = row.updatedAtMs,
	completenessMarker = marker.asUnproven(),
)

private fun StepsCountDomainStore.hasAuthenticPriorCompletenessOwners(
	timelinePrefix: List<SourceSessionCompletenessEntity>,
	scopeIdentity: String,
): Boolean = timelinePrefix.dropLast(1).all { prior ->
	val priorOwnerIdentity = StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
		prior.logicalTrackingId, prior.serviceRunId, prior.sourceInstanceId, prior.registrationGeneration,
	)
	val priorOwner = loadExactOwner(
		StepsCountDomainOwnerLookupKey(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			priorOwnerIdentity,
			StepsCountDomainReceiptIntegrity.completenessOwnerRevision(prior),
		),
		scopeIdentity,
	)
	priorOwner?.isAuthentic() == true
}

private fun completenessMarker(
	row: SourceSessionCompletenessEntity,
	ownerIdentity: String,
	ownerRevision: Long,
	retirementEvidence: StepsCountDomainRetirementEvidence,
	timelineChecksum: String,
	exactComplete: Boolean,
): StepsCountDomainCompletenessMarkerEntity {
	val terminalState = if (exactComplete) {
		StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE
	} else {
		StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN
	}
	val checksum = StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(
		ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
		ownerIdentity = ownerIdentity,
		ownerRevision = ownerRevision,
		terminalState = terminalState,
		lastAdmissionOrdinal = row.lastAdmissionOrdinal,
		lastSourceSequence = row.lastSourceSequence,
		providerFlushOutcome = retirementEvidence.providerFlushOutcome,
		registrationRemovalOutcome = retirementEvidence.registrationRemovalOutcome,
		registrationTimelineChecksum = timelineChecksum,
	)
	return StepsCountDomainCompletenessMarkerEntity(
		ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
		ownerIdentity = ownerIdentity,
		ownerRevision = ownerRevision,
		terminalState = terminalState,
		lastAdmissionOrdinal = row.lastAdmissionOrdinal,
		lastSourceSequence = row.lastSourceSequence,
		providerFlushOutcome = retirementEvidence.providerFlushOutcome,
		registrationRemovalOutcome = retirementEvidence.registrationRemovalOutcome,
		registrationTimelineChecksum = timelineChecksum,
		evidenceChecksum = checksum,
	)
}

internal suspend fun StepsCountDomainStore.recordSessionCompletenessInCountDomain(
	row: SourceSessionCompletenessEntity,
	retirementEvidence: StepsCountDomainRetirementEvidence,
): StepsCountDomainWriteResult = authenticateCountDomainWrite {
	recordSessionCompletenessAuthenticated(row, retirementEvidence)
}

private suspend fun StepsCountDomainStore.canonicalProjectionCommittedThrough(admissionOrdinal: Long): Boolean =
	database.sourceProjectionStateDao().productLanesByProjection(
		SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
	).any { lane ->
		lane.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL &&
			lane.activationOrdinal <= admissionOrdinal &&
			lane.contiguousAdmissionOrdinal >= admissionOrdinal &&
			lane.captureAdmissionCutoffOrdinal?.let { it >= admissionOrdinal } != false
	}
