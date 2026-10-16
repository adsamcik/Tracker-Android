package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.SourceDeliveryUnitIdentityRow
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull

internal suspend fun AppDatabase.authenticateCapturedCellWalDelivery(
	wal: SourceEventWalEntity, physicalFingerprint: String, observedStart: Long,
) {
	val sequenceRow = sourceEventWalDao().getBySourceSequence(
		CELL_SOURCE,
		wal.sourceInstanceId,
		wal.sourceSequence,
	)
	val completeDelivery = sourceEventWalDao().deliveryUnitsBounded(
		CELL_SOURCE,
		wal.capturedCollectedDataEpoch,
		wal.clockDomainId,
		requireNotNull(wal.deliveryIdentity),
		2,
	).singleOrNull()
	if (!sequenceRow.hasExactCapturedCellSequence(wal) ||
		!completeDelivery.hasExactCapturedCellDelivery(wal, physicalFingerprint, observedStart)
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun SourceEventWalEntity?.hasExactCapturedCellSequence(wal: SourceEventWalEntity): Boolean =
	this != null && eventId == wal.eventId && admissionOrdinal == wal.admissionOrdinal &&
		integrityIdentity == wal.integrityIdentity

// One complete immutable delivery-row correspondence; no policy, traversal or current authority is evaluated.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceDeliveryUnitIdentityRow?.hasExactCapturedCellDelivery(
	wal: SourceEventWalEntity, physicalFingerprint: String, observedStart: Long,
): Boolean = this != null && eventId == wal.eventId && admissionOrdinal == wal.admissionOrdinal &&
	deliveryUnitIndex == 0 && deliveryUnitCount == 1 && sourceInstanceId == wal.sourceInstanceId &&
	registrationGeneration == wal.registrationGeneration && physicalConfigurationFingerprint == physicalFingerprint &&
	authorizationRevision == wal.authorizationRevision && observedElapsedNanos == wal.observedElapsedNanos &&
	observedIntervalStartNanos == observedStart && payloadVersion == wal.payloadVersion &&
	payloadChecksum == wal.payloadChecksum

internal suspend fun AppDatabase.authenticateCapturedCellWalPolicy(
	wal: SourceEventWalEntity, policyRevision: Long, consentEpoch: Long, capturePoint: Long,
): SourcePolicyEntity {
	val policy = sourcePolicyDao().policyAtRevision(policyRevision, CELL_SOURCE)
	val consent = sourcePolicyDao().consentEpoch(CELL_SOURCE, CAPTURE_PURPOSE, consentEpoch)
	if (policy == null || consent == null) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	requireCapturedCellWalPolicy(policy, consentEpoch, wal.clockDomainId, capturePoint)
	requireCapturedCellWalConsent(consent, policyRevision, wal.clockDomainId, capturePoint)
	return policy
}

private fun requireCapturedCellWalPolicy(
	policy: SourcePolicyEntity, consentEpoch: Long, clockDomainId: String, capturePoint: Long,
) {
	if (!policy.enabled || !policy.capturePersistenceEligible || policy.captureConsentEpoch != consentEpoch) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (policy.effectiveBootId != clockDomainId || policy.effectiveElapsedRealtimeNanos > capturePoint) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun requireCapturedCellWalConsent(
	consent: SourceConsentEpochEntity, policyRevision: Long, clockDomainId: String, capturePoint: Long,
) {
	if (!consent.eligible || !consent.persistenceEligible || consent.policyRevision > policyRevision) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (consent.effectiveBootId != clockDomainId || consent.effectiveElapsedRealtimeNanos > capturePoint) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

internal suspend fun AppDatabase.authenticateCapturedCellWalRegistration(
	wal: SourceEventWalEntity, physicalFingerprint: String, authorizationRevision: Long, capturePoint: Long,
): LongRange {
	val registration = sourceBrokerDao().registration(CELL_SOURCE, wal.registrationGeneration)
		?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val registrationInterval = authenticateCellRegistration(
		registration = registration,
		expectedSourceInstanceId = wal.sourceInstanceId,
		expectedCollectedDataEpoch = wal.capturedCollectedDataEpoch,
		expectedClockDomainId = wal.clockDomainId,
		expectedPhysicalFingerprint = physicalFingerprint,
		expectedAuthorizationRevision = authorizationRevision,
	)
	val registrationStart = registrationInterval.first
	val registrationEnd = registrationInterval.last
	if (capturePoint < registrationStart || capturePoint >= registrationEnd) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return registrationInterval
}

internal suspend fun AppDatabase.readCapturedCellWalAuthorizationWindow(
	wal: SourceEventWalEntity, limits: CellCapturedMaintenanceLimits, authorizationRevision: Long,
	purposeItems: CellPurposeItems.Qualified?, registrationEnd: Long,
): CellCapturedAuthorizationWindow {
	val authorizationRows = boundedAuthorizationMembers(
		wal.registrationGeneration,
		authorizationRevision,
		limits,
	)
	val authorization = authorizationRows.toAuthorizationSnapshotOrNull()
		?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val endAuthorization = if (purposeItems == null) {
		boundedCapturedCellAuthorizationAt(
		wal.registrationGeneration,
		wal.clockDomainId,
		wal.observedElapsedNanos,
		limits,
	)
	} else {
		null
	}
	val nextAuthorization = readNextCapturedCellWalAuthorization(wal, limits, authorizationRevision, authorization)
	val authorizationEnd = purposeItems?.continuousUntilNanos ?: minOf(
		registrationEnd,
		nextAuthorization?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE,
	)
	return CellCapturedAuthorizationWindow(authorization, null, endAuthorization, authorizationEnd)
}

internal suspend fun AppDatabase.readCapturedCellWalDemands(
	wal: SourceEventWalEntity, authorization: SourceAuthorizationSnapshot, authorizationRevision: Long,
	captureMember: SourceAuthorizationEntity?,
): CellCapturedAuthorizationDemands {
	val demandIds = authorization.authorizedMembers.mapNotNull { member -> member.demandId }
	val demands = sourceBrokerDao().demandsByIds(demandIds)
	val recomputed = runCatching {
		SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = wal.registrationGeneration,
			authorizationRevision = authorizationRevision,
			demands = demands,
			effectiveBootId = authorization.effectiveBootId,
			effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
		)
	}.getOrNull()
	return CellCapturedAuthorizationDemands(captureMember, demandIds, demands, recomputed)
}

internal fun requireCapturedCellWalAuthorizationMembers(
	window: CellCapturedAuthorizationWindow, demands: CellCapturedAuthorizationDemands,
	purposeItems: CellPurposeItems.Qualified?,
) {
	val authorization = window.authorization
	if (authorization.isDenied || demands.member == null ||
		(purposeItems == null && window.end != authorization)
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (demands.ids.distinct().size != authorization.authorizedMembers.size ||
		demands.demands.size != authorization.authorizedMembers.size ||
		demands.recomputed?.sortedBy { it.memberId } != authorization.members.sortedBy { it.memberId }
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

// Keep the original immutable authorization window inputs explicit rather than adding a context model.
@Suppress("LongParameterList")
internal fun requireCapturedCellWalAuthorizationEffect(
	wal: SourceEventWalEntity, window: CellCapturedAuthorizationWindow, purposeItems: CellPurposeItems.Qualified?,
	authorizationFingerprint: String, purposeMask: Long, capturePoint: Long, selectedEndNanos: Long,
) {
	val authorization = window.authorization
	if (authorization.authorizationFingerprint != authorizationFingerprint ||
		authorization.purposeEligibilityMask != purposeMask || authorization.effectiveBootId != wal.clockDomainId
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if ((purposeItems?.continuousFromNanos ?: authorization.effectiveElapsedRealtimeNanos) > capturePoint ||
		selectedEndNanos >= window.callbackEndNanos
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.readNextCapturedCellWalAuthorization(
	wal: SourceEventWalEntity, limits: CellCapturedMaintenanceLimits, authorizationRevision: Long,
	authorization: SourceAuthorizationSnapshot,
): SourceAuthorizationSnapshot? {
	val nextRows = cellCapturedFactDao().maintenanceNextAuthorizationMembers(
		CELL_SOURCE,
		wal.registrationGeneration,
		authorizationRevision,
		limits.maximumAuthorizationMembers + 1,
	)
	if (nextRows.size > limits.maximumAuthorizationMembers) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val nextAuthorization = if (nextRows.isEmpty()) {
		null
	} else {
		nextRows.toAuthorizationSnapshotOrNull()
		?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (nextAuthorization != null &&
		(nextAuthorization.effectiveBootId != wal.clockDomainId ||
			nextAuthorization.effectiveElapsedRealtimeNanos <
				authorization.effectiveElapsedRealtimeNanos)
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return nextAuthorization
}

