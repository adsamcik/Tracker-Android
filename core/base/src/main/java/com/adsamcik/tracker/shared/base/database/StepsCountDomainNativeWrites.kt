package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken

private suspend fun StepsCountDomainStore.recordSessionWalAuthenticated(
	row: SourceEventWalEntity,
	counterDomainToken: StepsCounterDomainToken?,
): StepsCountDomainWriteResult {
	sessionWalWriteFailure(row, counterDomainToken)?.let { return it }
	val logicalTrackingId = requireNotNull(row.logicalTrackingId)
	val serviceRunId = requireNotNull(row.serviceRunId)
	val authorityRevision = requireNotNull(row.authorizationRevision)
	val authorityFingerprint = requireNotNull(row.authorizationFingerprint)

	val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
		row.admissionOrdinal,
		row.eventId,
	)
	val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
		logicalTrackingId,
		serviceRunId,
	)
	val latestOwner = database.openHelper.writableDatabase.queryLatestOwner(
		StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
		scopeIdentity,
		ownerIdentity,
	)
	val hasGenerationSafeToken =
		row.payloadVersion >= StepsCounterDomainToken.COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION &&
			counterDomainToken != null
	if (latestOwner?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN &&
		hasGenerationSafeToken
	) {
		return StepsCountDomainWriteResult.UNPROVEN
	}
	// Payload v6 carried one token through COUNTER_RESET and therefore cannot prove that any
	// retained window stayed within one physical counter epoch. Generation-safe authority starts
	// at v7; all older WAL remains decodable for fact recovery but is permanently UNPROVEN here.
	if (!hasGenerationSafeToken) {
		return appendTerminalUnproven(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			ownerEffectChecksum = row.integrityIdentity,
			linkedAtMs = row.createdAtMs,
		)
	}
	return appendBoundOwner(
		receipt = nativeReceipt(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = 1L,
			counterDomainToken = counterDomainToken,
			registrationGeneration = row.registrationGeneration,
			collectedDataEpoch = row.capturedCollectedDataEpoch,
			authorityRevision = authorityRevision,
			authorityFingerprint = authorityFingerprint,
			coverageKind = StepsCountDomainReceiptEntity.COVERAGE_ADMITTED_WINDOW,
			coverageVersion = row.payloadVersion,
			effectChecksum = row.integrityIdentity,
			completionEvidenceChecksum = null,
		),
		scopeIdentity = scopeIdentity,
		ownerEffectChecksum = row.integrityIdentity,
		linkedAtMs = row.createdAtMs,
	)
}

private suspend fun StepsCountDomainStore.recordSessionFactAuthenticated(
	fact: StepFactRevisionEntity,
): StepsCountDomainWriteResult {
	sessionFactWriteFailure(fact)?.let { return it }
	val admissionOrdinal = requireNotNull(fact.sourceAdmissionOrdinal)
	val eventId = requireNotNull(fact.sourceEventId)
	val scopeIdentity = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
		requireNotNull(fact.logicalTrackingId),
		requireNotNull(fact.serviceRunId),
	)
	val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
		fact.writerProjectionId,
		fact.writerProjectionVersion,
		fact.logicalFactId,
	)
	val walOwner = loadExactOwner(
		StepsCountDomainOwnerLookupKey(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(admissionOrdinal, eventId),
			1L,
		),
		scopeIdentity,
	)
	if (walOwner == null ||
		walOwner.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN
	) {
		return appendUnprovenSessionFact(fact, scopeIdentity, ownerIdentity)
	}
	val walReceipt = walOwner.receipt
	val coverageKind = sessionFactCoverageKind(fact)
	if (!walOwner.authenticatesSessionFact(scopeIdentity, fact.collectedDataEpoch) || coverageKind == null) {
		return StepsCountDomainWriteResult.IDENTITY_CONFLICT
	}
	val receipt = nativeReceiptFrom(
		source = requireNotNull(walReceipt),
		ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
		scopeIdentity = scopeIdentity,
		ownerIdentity = ownerIdentity,
		ownerRevision = fact.semanticRevision,
		coverageKind = coverageKind,
		coverageVersion = fact.writerProjectionVersion,
		effectChecksum = fact.effectChecksum,
		completionEvidenceChecksum = null,
	)
	return appendBoundOwner(
		receipt = receipt,
		scopeIdentity = scopeIdentity,
		ownerEffectChecksum = fact.effectChecksum,
		linkedAtMs = fact.appliedAtMs,
	)
}

private suspend fun StepsCountDomainStore.recordAmbientFactAuthenticated(
	fact: AmbientStepsFactRevisionEntity,
	counterDomainToken: StepsCounterDomainToken?,
): StepsCountDomainWriteResult {
	ambientFactWriteFailure(fact)?.let { return it }
	val ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
		fact.writerId,
		fact.writerVersion,
		fact.logicalFactId,
	)
	val scopeIdentity =
		StepsCountDomainReceiptIntegrity.ambientFactScopeIdentity(fact.logicalFactId)
	val latestOwner = database.openHelper.writableDatabase.queryLatestOwner(
		StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
		scopeIdentity,
		ownerIdentity,
	)
	if (latestOwner?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN ||
		counterDomainToken == null || (fact.semanticRevision > 1L && latestOwner == null)
	) {
		return appendTerminalUnproven(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
			scopeIdentity = scopeIdentity,
			ownerIdentity = ownerIdentity,
			ownerRevision = fact.semanticRevision,
			ownerEffectChecksum = fact.effectChecksum,
			linkedAtMs = fact.appliedAtMs,
		)
	}
	val receipt = nativeReceipt(
		ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
		scopeIdentity = scopeIdentity,
		ownerIdentity = ownerIdentity,
		ownerRevision = fact.semanticRevision,
		counterDomainToken = requireNotNull(counterDomainToken),
		registrationGeneration = requireNotNull(fact.registrationGeneration),
		collectedDataEpoch = fact.collectedDataEpoch,
		authorityRevision = requireNotNull(fact.authorizationRevision),
		authorityFingerprint = requireNotNull(fact.authorizationFingerprint),
		coverageKind = StepsCountDomainReceiptEntity.COVERAGE_AMBIENT_AGGREGATE,
		coverageVersion = fact.writerVersion,
		effectChecksum = fact.effectChecksum,
		completionEvidenceChecksum = null,
	)
	return appendBoundOwner(
		receipt = receipt,
		scopeIdentity = scopeIdentity,
		ownerEffectChecksum = fact.effectChecksum,
		linkedAtMs = fact.appliedAtMs,
	)
}

private fun StepsCountDomainStore.sessionWalWriteFailure(
	row: SourceEventWalEntity,
	token: StepsCounterDomainToken?,
): StepsCountDomainWriteResult? {
	if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
		row.authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
	) {
		return StepsCountDomainWriteResult.NOT_APPLICABLE
	}
	writeSchemaFailure()?.let { return it }
	if (row.logicalTrackingId == null || row.serviceRunId == null ||
		row.authorizationRevision == null || row.authorizationFingerprint == null
	) {
		return StepsCountDomainWriteResult.UNPROVEN
	}
	return if (!row.hasQualifiedIntegrity() || !row.hasCompatibleCounterTokenVersion(token)) {
		StepsCountDomainWriteResult.IDENTITY_CONFLICT
	} else {
		null
	}
}

private fun SourceEventWalEntity.hasCompatibleCounterTokenVersion(token: StepsCounterDomainToken?): Boolean =
	if (token == null) {
		payloadVersion != StepsCounterDomainToken.MINIMUM_DURABLE_PAYLOAD_VERSION
	} else {
		payloadVersion >= StepsCounterDomainToken.MINIMUM_DURABLE_PAYLOAD_VERSION
	}

private fun StepsCountDomainStore.sessionFactWriteFailure(fact: StepFactRevisionEntity): StepsCountDomainWriteResult? {
	if (fact.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL ||
		fact.operation != StepFactRevisionEntity.OPERATION_UPSERT
	) {
		return StepsCountDomainWriteResult.NOT_APPLICABLE
	}
	return writeSchemaFailure() ?: if (!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(fact)) {
		StepsCountDomainWriteResult.IDENTITY_CONFLICT
	} else {
		null
	}
}

private suspend fun StepsCountDomainStore.appendUnprovenSessionFact(
	fact: StepFactRevisionEntity,
	scopeIdentity: String,
	ownerIdentity: String,
): StepsCountDomainWriteResult = appendTerminalUnproven(
	ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
	scopeIdentity = scopeIdentity,
	ownerIdentity = ownerIdentity,
	ownerRevision = fact.semanticRevision,
	ownerEffectChecksum = fact.effectChecksum,
	linkedAtMs = fact.appliedAtMs,
)

private fun StepsCountDomainStoredOwner.authenticatesSessionFact(scope: String, epoch: Long): Boolean =
	receipt != null && isAuthentic() && owner.scopeIdentity == scope && receipt.collectedDataEpoch == epoch

private fun StepsCountDomainStore.ambientFactWriteFailure(
	fact: AmbientStepsFactRevisionEntity,
): StepsCountDomainWriteResult? {
	if (fact.operation != AmbientStepsFactRevisionEntity.OPERATION_UPSERT ||
		fact.originKind != AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE
	) {
		return StepsCountDomainWriteResult.NOT_APPLICABLE
	}
	return writeSchemaFailure() ?: if (!AmbientStepsFactIntegrity.hasValidEffectChecksum(fact)) {
		StepsCountDomainWriteResult.IDENTITY_CONFLICT
	} else {
		null
	}
}

private fun sessionFactCoverageKind(fact: StepFactRevisionEntity): String? = when (fact.coverageKind) {
	StepFactRevisionEntity.COVERAGE_BASELINE -> StepsCountDomainReceiptEntity.COVERAGE_BASELINE
	StepFactRevisionEntity.COVERAGE_COVERED -> StepsCountDomainReceiptEntity.COVERAGE_COVERED
	StepFactRevisionEntity.COVERAGE_RESET_GAP -> StepsCountDomainReceiptEntity.COVERAGE_RESET_GAP
	StepFactRevisionEntity.COVERAGE_PARTIAL -> StepsCountDomainReceiptEntity.COVERAGE_PARTIAL
	else -> null
}

internal suspend fun StepsCountDomainStore.recordSessionWalInCountDomain(
	row: SourceEventWalEntity,
	counterDomainToken: StepsCounterDomainToken?,
): StepsCountDomainWriteResult = authenticateCountDomainWrite {
	recordSessionWalAuthenticated(row, counterDomainToken)
}

internal suspend fun StepsCountDomainStore.recordSessionFactInCountDomain(
	fact: StepFactRevisionEntity,
): StepsCountDomainWriteResult = authenticateCountDomainWrite {
	recordSessionFactAuthenticated(fact)
}

internal suspend fun StepsCountDomainStore.recordAmbientFactInCountDomain(
	fact: AmbientStepsFactRevisionEntity,
	counterDomainToken: StepsCounterDomainToken?,
): StepsCountDomainWriteResult = authenticateCountDomainWrite {
	recordAmbientFactAuthenticated(fact, counterDomainToken)
}

