package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.CellCapturedFactDao
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull

internal suspend fun AppDatabase.quiesceCellCaptureForConsentDeletion(
	dao: CellCapturedFactDao,
	expectedCollectedDataEpoch: Long,
	policy: SourcePolicyEntity,
	revokedConsent: SourceConsentEpochEntity,
	limits: CellCapturedMaintenanceLimits,
) {
	val demands = dao.directCellCaptureDemandsForDeletion(
		CELL_SOURCE,
		limits.maximumDirectDemands + 1,
	)
	if (demands.size > limits.maximumDirectDemands) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	if (demands.any { demand -> demand.status == SourceDemandEntity.STATUS_ACTIVE }) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.DIRECT_DEMAND_NOT_QUIESCED)
	}
	authenticateRetiringCellCaptureDemands(
		demands = demands,
		revokedConsent = revokedConsent,
	)
	val registrations = dao.cellRegistrationsForDeletion(
		CELL_SOURCE,
		limits.maximumNonterminalRegistrations + 1,
	)
	if (registrations.size > limits.maximumNonterminalRegistrations) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	registrations.forEach { registration ->
		authenticateCellCaptureDeletionBarrier(
			registration = registration,
			expectedCollectedDataEpoch = expectedCollectedDataEpoch,
			currentPolicy = policy,
			limits = limits,
		)
	}
	if (demands.isNotEmpty() && dao.retireCellCaptureDemandsForDeletion(
			sourceKind = CELL_SOURCE,
			bootId = revokedConsent.effectiveBootId,
			elapsedRealtimeNanos = revokedConsent.effectiveElapsedRealtimeNanos,
			wallTimeMs = revokedConsent.effectiveWallTimeMs,
		) != demands.size
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.DIRECT_DEMAND_NOT_QUIESCED)
	}
}

/**
 * Authenticates the exact direct capture demands fenced by the consent change before retiring
 * them. A prepared BLOCKED demand was never provider authority; a RETIRING demand keeps its first
 * complete, monotonic source-clock boundary while the current provider authorization and callback
 * barrier independently prove that it can no longer admit capture.
 */
private suspend fun AppDatabase.authenticateRetiringCellCaptureDemands(
	demands: List<SourceDemandEntity>,
	revokedConsent: SourceConsentEpochEntity,
) {
	for (demand in demands) {
		val stateShape = demand.hasRetiringCellCaptureShape()
		val policy = sourcePolicyDao().policyAtRevision(demand.sourcePolicyRevision, CELL_SOURCE)
		val consent = sourcePolicyDao().consentEpoch(
			CELL_SOURCE,
			CAPTURE_PURPOSE,
			demand.consentEpoch,
		)
		requireRetiringCellCaptureIdentity(demand, stateShape)
		if (demand.sourcePolicyRevision >= revokedConsent.policyRevision || policy == null || consent == null) {
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		requireRetiringCellCaptureGrant(demand, policy, consent)
		requireRetiringCellCaptureRequestTime(demand, policy, consent)
	}
}

/**
 * A compatible Cell provider may remain ACTIVE for CONTROL after capture consent is revoked. Its
 * latest authorization must be reconstructed from the exact active demands, and the provider
 * owner must have acknowledged the complete historical capture revision after draining its FIFO.
 */
private suspend fun AppDatabase.authenticateCellCaptureDeletionBarrier(
	registration: ProviderRegistrationGenerationEntity,
	expectedCollectedDataEpoch: Long,
	currentPolicy: SourcePolicyEntity,
	limits: CellCapturedMaintenanceLimits,
) {
	requireQuiescentCellRegistration(registration, expectedCollectedDataEpoch)

	val authorization = readQuiescentCellAuthorization(registration, currentPolicy, limits)

	val maximumCaptureRevision = sourceBrokerDao().maximumCaptureAuthorizationRevision(
		CELL_SOURCE,
		registration.registrationGeneration,
	)
	when {
		registration.captureCallbackBarrierAuthorizationRevision < maximumCaptureRevision ->
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
		registration.captureCallbackBarrierAuthorizationRevision > maximumCaptureRevision &&
			!hasExactRadioSessionCaptureCallbackBarrier(registration, authorization, maximumCaptureRevision) ->
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

/** Independently policy-authenticated CONTROL and Ambient may keep the shared provider resident. */
private suspend fun AppDatabase.authenticateCurrentCellDemands(
	demands: List<SourceDemandEntity>,
	currentPolicy: SourcePolicyEntity,
	authorization: SourceAuthorizationSnapshot,
) {
	for (demand in demands) {
		if (demand.sourceKind != CELL_SOURCE) {
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		if (demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE) {
			continue
		}
		if (demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT) {
			authenticateCurrentAmbientCellDemand(demand, currentPolicy, authorization)
			continue
		}
		if (demand.purpose !in setOf(
				SourceBrokerPurpose.CONTROL_AUTOSTART,
				SourceBrokerPurpose.CONTROL_CONTINUATION,
			)
		) {
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		authenticateCurrentControlCellDemand(demand)
	}
}


private const val CONTROL_POLICY_PURPOSE = "CONTROL"

private fun SourceDemandEntity.hasRetiringCellCaptureShape(): Boolean = when (status) {
	SourceDemandEntity.STATUS_RETIRING -> hasCellCaptureRetirementBoundary()
	SourceDemandEntity.STATUS_BLOCKED ->
		retireBootId == null && retireElapsedRealtimeNanos == null && retiredAtMs == null
	else -> false
}

private fun SourceDemandEntity.hasCellCaptureRetirementBoundary(): Boolean {
	val retireBootId = retireBootId
	val retireElapsedNanos = retireElapsedRealtimeNanos
	val retiredAtMs = retiredAtMs
	if (retireBootId == null || retireElapsedNanos == null || retiredAtMs == null) {
		return false
	}
	return retiredAtMs >= requestedAtMs &&
		(retireBootId != requestedBootId || retireElapsedNanos >= requestedElapsedRealtimeNanos)
}

private fun requireRetiringCellCaptureIdentity(demand: SourceDemandEntity, stateShape: Boolean) {
	if (!stateShape || demand.sourceKind != CELL_SOURCE ||
		demand.purpose != CAPTURE_PURPOSE || !demand.persistenceEligible
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (demand.logicalTrackingId.isNullOrBlank() || demand.serviceRunId.isNullOrBlank() ||
		demand.manifestRevision == null || demand.lifecycleLeaseGeneration == null
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun requireRetiringCellCaptureGrant(
	demand: SourceDemandEntity,
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
) {
	if (!policy.enabled || !policy.capturePersistenceEligible ||
		policy.captureConsentEpoch != demand.consentEpoch || policy.qosCode != demand.qosCode
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!consent.eligible || !consent.persistenceEligible || consent.policyRevision > policy.policyRevision) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun requireRetiringCellCaptureRequestTime(
	demand: SourceDemandEntity,
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
) {
	if (consent.effectiveWallTimeMs > demand.requestedAtMs ||
		(consent.effectiveBootId == demand.requestedBootId &&
			consent.effectiveElapsedRealtimeNanos > demand.requestedElapsedRealtimeNanos)
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	requireCellPolicyAtDemandRequest(demand, policy)
}

private fun requireCellPolicyAtDemandRequest(demand: SourceDemandEntity, policy: SourcePolicyEntity) {
	if (demand.requestedBootId != policy.effectiveBootId ||
		demand.requestedElapsedRealtimeNanos < policy.effectiveElapsedRealtimeNanos ||
		demand.requestedAtMs < policy.effectiveWallTimeMs
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun requireQuiescentCellRegistration(
	registration: ProviderRegistrationGenerationEntity,
	expectedCollectedDataEpoch: Long,
) {
	val activeShape = registration.hasActiveCellCaptureDeletionShape()
	val retiringShape = registration.hasRetiringCellCaptureDeletionShape()
	if ((!activeShape && !retiringShape) || registration.sourceKind != CELL_SOURCE ||
		registration.ownerScope != "source-broker:$CELL_SOURCE"
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
	}
	if (registration.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
		registration.providerProcessIncarnationId.isNullOrBlank() ||
		registration.collectedDataEpoch != expectedCollectedDataEpoch
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
	}
}

private fun ProviderRegistrationGenerationEntity.hasActiveCellCaptureDeletionShape(): Boolean {
	if (status != ProviderRegistrationGenerationEntity.STATUS_ACTIVE ||
		acceptedAtMs == null || acceptedElapsedRealtimeNanos == null
	) {
		return false
	}
	return retiredAtMs == null && retiredElapsedRealtimeNanos == null && failureCode == null
}

private fun ProviderRegistrationGenerationEntity.hasRetiringCellCaptureDeletionShape(): Boolean {
	if (status != ProviderRegistrationGenerationEntity.STATUS_RETIRING ||
		acceptedAtMs == null || acceptedElapsedRealtimeNanos == null
	) {
		return false
	}
	return retiredAtMs != null && retiredElapsedRealtimeNanos != null && !failureCode.isNullOrBlank()
}

private suspend fun AppDatabase.readQuiescentCellAuthorization(
	registration: ProviderRegistrationGenerationEntity,
	currentPolicy: SourcePolicyEntity,
	limits: CellCapturedMaintenanceLimits,
): SourceAuthorizationSnapshot {
	val latestRevision = cellCapturedFactDao().maximumRegistrationAuthorizationRevision(
		CELL_SOURCE,
		registration.registrationGeneration,
	)
	if (latestRevision <= 0L) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val rows = boundedAuthorizationMembers(registration.registrationGeneration, latestRevision, limits)
	val authorization = try {
		rows.toAuthorizationSnapshotOrNull()
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		null
	} ?: blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val activeDemands = cellCapturedFactDao().activeCellDemandsForDeletion(
		CELL_SOURCE,
		limits.maximumDirectDemands + 1,
	)
	if (activeDemands.size > limits.maximumDirectDemands) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	authenticateCurrentCellDemands(activeDemands, currentPolicy, authorization)
	val expectedRows = SourceBrokerAuthorization.rows(
		sourceKind = CELL_SOURCE,
		registrationGeneration = registration.registrationGeneration,
		authorizationRevision = latestRevision,
		demands = activeDemands,
		effectiveBootId = authorization.effectiveBootId,
		effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs = rows.first().effectiveWallTimeMs,
	)
	if (rows.sortedBy { row -> row.memberId } != expectedRows.sortedBy { row -> row.memberId }) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (authorization.authorizedMembers.any { member ->
			member.persistenceEligible && member.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
	}
	return authorization
}

private suspend fun AppDatabase.authenticateCurrentAmbientCellDemand(
	demand: SourceDemandEntity,
	currentPolicy: SourcePolicyEntity,
	authorization: SourceAuthorizationSnapshot,
) {
	val policy = sourcePolicyDao().policyAtRevision(demand.sourcePolicyRevision, CELL_SOURCE)
	val consent = sourcePolicyDao().latestConsentEpoch(CELL_SOURCE, SourceBrokerPurpose.AMBIENT_PRODUCT)
	if (policy == null || !policy.hasExactEligibleAmbientConsentReference(consent) ||
		!currentPolicy.hasExactEligibleAmbientConsentReference(consent) || consent == null
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (policy.ambientConsentEpoch != demand.consentEpoch || currentPolicy.ambientConsentEpoch != demand.consentEpoch) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	requireSessionlessAmbientCellDemand(demand)
	requireAmbientCellDemandRequestTime(demand, policy, consent, authorization)
}

private fun requireSessionlessAmbientCellDemand(demand: SourceDemandEntity) {
	if (!demand.persistenceEligible || demand.status != SourceDemandEntity.STATUS_ACTIVE) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (demand.logicalTrackingId != null || demand.serviceRunId != null ||
		demand.manifestRevision != null || demand.lifecycleLeaseGeneration != null
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (demand.retireBootId != null || demand.retireElapsedRealtimeNanos != null || demand.retiredAtMs != null) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun requireAmbientCellDemandRequestTime(
	demand: SourceDemandEntity,
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
	authorization: SourceAuthorizationSnapshot,
) {
	if (consent.policyRevision > policy.policyRevision ||
		!policy.isEffectiveAtOrBefore(
			demand.requestedBootId, demand.requestedElapsedRealtimeNanos, demand.requestedAtMs,
		) ||
		!consent.isEffectiveAtOrBefore(
			demand.requestedBootId, demand.requestedElapsedRealtimeNanos, demand.requestedAtMs,
		)
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (demand.requestedBootId != authorization.effectiveBootId ||
		demand.requestedElapsedRealtimeNanos > authorization.effectiveElapsedRealtimeNanos
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.authenticateCurrentControlCellDemand(demand: SourceDemandEntity) {
	val policy = sourcePolicyDao().policyAtRevision(demand.sourcePolicyRevision, CELL_SOURCE)
	val consent = sourcePolicyDao().consentEpoch(
		CELL_SOURCE,
		CONTROL_POLICY_PURPOSE,
		demand.consentEpoch,
	)
	if (policy == null || consent == null || policy.controlConsentEpoch != demand.consentEpoch) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (policy.controlPersistenceEligible != demand.persistenceEligible ||
		!consent.eligible || consent.persistenceEligible != demand.persistenceEligible
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (consent.policyRevision > policy.policyRevision) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	requireCellPolicyAtDemandRequest(demand, policy)
}
