package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken

@Suppress("LongParameterList")
internal fun nativeReceipt(
	ownerKind: String,
	scopeIdentity: String,
	ownerIdentity: String,
	ownerRevision: Long,
	counterDomainToken: StepsCounterDomainToken,
	registrationGeneration: Long,
	collectedDataEpoch: Long,
	authorityRevision: Long,
	authorityFingerprint: String,
	coverageKind: String,
	coverageVersion: Int,
	effectChecksum: String,
	completionEvidenceChecksum: String?,
): StepsCountDomainReceiptEntity {
	val domainIdentity =
		StepsCountDomainReceiptIntegrity.counterDomainIdentity(counterDomainToken)
	val countDomainVersion = StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION
	val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
		domainIdentity = domainIdentity,
		ownerKind = ownerKind,
		scopeIdentity = scopeIdentity,
		ownerIdentity = ownerIdentity,
		ownerRevision = ownerRevision,
		registrationGeneration = registrationGeneration,
		collectedDataEpoch = collectedDataEpoch,
		authorityRevision = authorityRevision,
		authorityFingerprint = authorityFingerprint,
		coverageKind = coverageKind,
		coverageVersion = coverageVersion,
		countDomainVersion = countDomainVersion,
		effectChecksum = effectChecksum,
		completionEvidenceChecksum = completionEvidenceChecksum,
	)
	return StepsCountDomainReceiptEntity(
		receiptIdentity = receiptIdentity,
		domainIdentity = domainIdentity,
		ownerKind = ownerKind,
		scopeIdentity = scopeIdentity,
		ownerIdentity = ownerIdentity,
		ownerRevision = ownerRevision,
		registrationGeneration = registrationGeneration,
		collectedDataEpoch = collectedDataEpoch,
		authorityRevision = authorityRevision,
		authorityFingerprint = authorityFingerprint,
		coverageKind = coverageKind,
		coverageVersion = coverageVersion,
		countDomainVersion = countDomainVersion,
		effectChecksum = effectChecksum,
		completionEvidenceChecksum = completionEvidenceChecksum,
	)
}

internal fun nativeReceiptFrom(
	source: StepsCountDomainReceiptEntity,
	ownerKind: String,
	scopeIdentity: String,
	ownerIdentity: String,
	ownerRevision: Long,
	coverageKind: String,
	coverageVersion: Int,
	effectChecksum: String,
	completionEvidenceChecksum: String?,
): StepsCountDomainReceiptEntity {
	val receiptIdentity = StepsCountDomainReceiptIntegrity.receiptIdentity(
		domainIdentity = source.domainIdentity,
		ownerKind = ownerKind,
		scopeIdentity = scopeIdentity,
		ownerIdentity = ownerIdentity,
		ownerRevision = ownerRevision,
		registrationGeneration = source.registrationGeneration,
		collectedDataEpoch = source.collectedDataEpoch,
		authorityRevision = source.authorityRevision,
		authorityFingerprint = source.authorityFingerprint,
		coverageKind = coverageKind,
		coverageVersion = coverageVersion,
		countDomainVersion = source.countDomainVersion,
		effectChecksum = effectChecksum,
		completionEvidenceChecksum = completionEvidenceChecksum,
	)
	return source.copy(
		receiptIdentity = receiptIdentity,
		ownerKind = ownerKind,
		scopeIdentity = scopeIdentity,
		ownerIdentity = ownerIdentity,
		ownerRevision = ownerRevision,
		coverageKind = coverageKind,
		coverageVersion = coverageVersion,
		effectChecksum = effectChecksum,
		completionEvidenceChecksum = completionEvidenceChecksum,
	)
}

internal fun StepsCountDomainStoredOwner.isAuthentic(): Boolean {
	val receipt = receipt ?: return false
	return owner.matchesBoundReceipt(receipt) && hasExpectedCompletenessReceipt(receipt)
}

internal fun StepsCountDomainStoredOwner.hasExpectedCompletenessReceipt(
	receipt: StepsCountDomainReceiptEntity,
): Boolean =
	if (owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS) {
		completenessMarker?.let {
			it.terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE &&
				it.evidenceChecksum == receipt.completionEvidenceChecksum
		} == true
	} else {
		completenessMarker == null && receipt.completionEvidenceChecksum == null
	}

internal fun SourceSessionCompletenessEntity.hasExactCompleteRetirement(
	evidence: StepsCountDomainRetirementEvidence,
): Boolean = registrationGeneration > 0L &&
	appDrainComplete &&
	providerCoverage == "CALLBACKS_ENTERED_BEFORE_BARRIER" &&
	stopStatus == "COMPLETE" &&
	unresolvedSequenceStart == null &&
	unresolvedSequenceEnd == null &&
	lastAdmissionOrdinal != null &&
	lastSourceSequence != null &&
	evidence.providerFlushOutcome in
		setOf("COMPLETE", "NOT_SUPPORTED", "NOT_REQUESTED") &&
	evidence.registrationRemovalOutcome in setOf("REMOVED", "NOT_REGISTERED")

internal fun SourceSessionCompletenessEntity.hasTerminalRetirement(
	evidence: StepsCountDomainRetirementEvidence,
): Boolean = StepsRetirementTerminality.isTerminal(
	stopStatus = stopStatus,
	registrationRemovalOutcome = evidence.registrationRemovalOutcome,
	providerFlushOutcome = evidence.providerFlushOutcome,
	providerCoverage = providerCoverage,
	appDrainComplete = appDrainComplete,
	lastAdmissionOrdinal = lastAdmissionOrdinal,
	unresolvedSequenceStart = unresolvedSequenceStart,
	unresolvedSequenceEnd = unresolvedSequenceEnd,
)

internal fun StepsCountDomainRetirementEvidence.hasFinalRegistrationRemoval(): Boolean =
	registrationRemovalOutcome in setOf("REMOVED", "NOT_REGISTERED")

internal fun StepsCountDomainCompletenessMarkerEntity.asUnproven():
	StepsCountDomainCompletenessMarkerEntity {
	if (terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN) {
		return this
	}
	val checksum = StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(
		ownerKind = ownerKind,
		ownerIdentity = ownerIdentity,
		ownerRevision = ownerRevision,
		terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN,
		lastAdmissionOrdinal = lastAdmissionOrdinal,
		lastSourceSequence = lastSourceSequence,
		providerFlushOutcome = providerFlushOutcome,
		registrationRemovalOutcome = registrationRemovalOutcome,
		registrationTimelineChecksum = registrationTimelineChecksum,
	)
	return copy(
		terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN,
		evidenceChecksum = checksum,
	)
}

internal fun StepsCountDomainCompletenessMarkerEntity.matches(
	owner: StepsCountDomainOwnerRevisionEntity,
): Boolean = ownerKind == owner.ownerKind &&
	ownerIdentity == owner.ownerIdentity &&
	ownerRevision == owner.ownerRevision &&
	when (owner.operation) {
		StepsCountDomainOwnerRevisionEntity.OPERATION_BIND ->
			terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE
		StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN ->
			terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN
		else -> false
	}

internal fun SourceEventWalEntity.matchesTerminalStepsCompleteness(row: SourceSessionCompletenessEntity): Boolean {
	val sameProviderOwner = logicalTrackingId == row.logicalTrackingId && serviceRunId == row.serviceRunId &&
		sourceInstanceId == row.sourceInstanceId && registrationGeneration == row.registrationGeneration
	return sameProviderOwner && sourceSequence == row.lastSourceSequence
}
