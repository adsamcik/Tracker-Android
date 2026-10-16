package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.dao.WifiCapturedFactDao
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
import com.adsamcik.tracker.shared.base.database.hasExactRadioSessionCaptureCallbackBarrier
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContract
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURE_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_SOURCE

internal suspend fun WifiCapturedFactMaintenance.quiesceCaptureForConsentDeletion(
	dao: WifiCapturedFactDao,
	expectedCollectedDataEpoch: Long,
	policy: SourcePolicyEntity,
	revokedConsent: SourceConsentEpochEntity,
	limits: WifiCapturedMaintenanceLimits,
) {
	val demands = dao.captureDemandsForDeletion(
		WIFI_SOURCE,
		CAPTURE_PURPOSE,
		limits.maximumDirectDemands + 1,
	)
	if (demands.size > limits.maximumDirectDemands) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	if (demands.any { demand -> demand.status == SourceDemandEntity.STATUS_ACTIVE }) {
		block(WifiCapturedSourceDeletionBlockedReason.CAPTURE_DEMAND_NOT_QUIESCED)
	}
	authenticateRetiringWifiCaptureDemands(
		demands = demands,
		revokedPolicyRevision = revokedConsent.policyRevision,
	)
	val registrations = dao.registrationsForDeletion(
		WIFI_SOURCE,
		limits.maximumNonterminalRegistrations + 1,
	)
	if (registrations.size > limits.maximumNonterminalRegistrations) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	registrations.forEach { registration ->
		authenticateWifiCaptureDeletionBarrier(
			registration = registration,
			expectedCollectedDataEpoch = expectedCollectedDataEpoch,
			currentPolicy = policy,
			limits = limits,
		)
	}
	if (demands.isNotEmpty() && dao.retireCaptureDemandsForDeletion(
			sourceKind = WIFI_SOURCE,
			capturePurpose = CAPTURE_PURPOSE,
			bootId = revokedConsent.effectiveBootId,
			elapsedRealtimeNanos = revokedConsent.effectiveElapsedRealtimeNanos,
			wallTimeMs = revokedConsent.effectiveWallTimeMs,
		) != demands.size
	) {
		block(WifiCapturedSourceDeletionBlockedReason.CAPTURE_DEMAND_NOT_QUIESCED)
	}
}

/**
 * Authenticates the exact direct capture demands fenced by the consent change before retiring
 * them. A prepared BLOCKED demand was never provider authority; RETIRING keeps its first
 * complete source-clock boundary while the callback barrier proves it can no longer admit
 * session capture.
 */
private suspend fun WifiCapturedFactMaintenance.authenticateRetiringWifiCaptureDemands(
	demands: List<SourceDemandEntity>,
	revokedPolicyRevision: Long,
) {
	for (demand in demands) {
		val stateShape = demand.hasWifiCaptureRetirementShape()
		val policy = database.sourcePolicyDao().policyAtRevision(
			demand.sourcePolicyRevision,
			WIFI_SOURCE,
		)
		val consent = database.sourcePolicyDao().consentEpoch(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			demand.consentEpoch,
		)
		authenticateRetiringWifiCaptureDemand(demand, stateShape, revokedPolicyRevision, policy, consent)
	}
}

/**
 * Wi-Fi may remain ACTIVE for independently authorized CONTROL or AMBIENT_PRODUCT demand. The
 * latest authorization must reconstruct exactly from current active demands, while the source
 * owner must have acknowledged every historical product-capable callback revision after
 * draining its FIFO.
 */
private suspend fun WifiCapturedFactMaintenance.authenticateWifiCaptureDeletionBarrier(
	registration: ProviderRegistrationGenerationEntity,
	expectedCollectedDataEpoch: Long,
	currentPolicy: SourcePolicyEntity,
	limits: WifiCapturedMaintenanceLimits,
) {
	requireWifiCaptureDeletionProvider(registration, expectedCollectedDataEpoch)

	val authorization = loadWifiCaptureDeletionAuthorization(registration, currentPolicy, limits)
	val maximumProductRevision = database.sourceBrokerDao().maximumRadioSessionCaptureAuthorizationRevision(
		WIFI_SOURCE,
		registration.registrationGeneration,
	)
	when {
		registration.captureCallbackBarrierAuthorizationRevision < maximumProductRevision ->
			block(WifiCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
		registration.captureCallbackBarrierAuthorizationRevision > maximumProductRevision &&
			!database.hasExactRadioSessionCaptureCallbackBarrier(registration, authorization,
                    maximumProductRevision) ->
			block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

/** Current non-session Wi-Fi demands must remain independently policy/consent authorized. */
private suspend fun WifiCapturedFactMaintenance.authenticateCurrentWifiDemands(
	demands: List<SourceDemandEntity>,
	currentPolicy: SourcePolicyEntity,
	authorization: SourceAuthorizationSnapshot,
) {
	for (demand in demands) {
		if (!demand.hasActiveNoncaptureWifiShape()) {
			block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val demandPolicy = database.sourcePolicyDao().policyAtRevision(
			demand.sourcePolicyRevision,
			WIFI_SOURCE,
		) ?: block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val directPurpose = demand.nonCaptureDirectPurpose()
			?: block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val policyConsentPurpose = demand.policyPurpose()
			?: block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val historicalAuthority = demandPolicy.authorityFor(policyConsentPurpose)
		val currentAuthority = currentPolicy.authorityFor(policyConsentPurpose)
		val consent = database.sourcePolicyDao().consentEpoch(
			WIFI_SOURCE,
			policyConsentPurpose,
			demand.consentEpoch,
		) ?: block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val expectedContract = runCatching {
			demandPolicy.historicalWifiDemandContract(directPurpose)
		}.getOrNull()
		val actualContract = runCatching { demand.toSourceDemandContract() }.getOrNull()
		val consentPrecedesDemand = consent.isEffectiveAtOrBefore(
			demand.requestedBootId, demand.requestedElapsedRealtimeNanos, demand.requestedAtMs)
		val policyPrecedesDemand = demandPolicy.isEffectiveAtOrBefore(
			demand.requestedBootId, demand.requestedElapsedRealtimeNanos, demand.requestedAtMs)
		val demandPrecedesAuthorization = demand.precedesAuthorization(authorization)
		if (!demand.matchesCurrentWifiPolicy(
			demandPolicy, directPurpose, historicalAuthority, currentAuthority, expectedContract, actualContract,
		)) {
			block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		if (!consent.matchesHistoricalWifiDemandConsent(
			demandPolicy, demand, consentPrecedesDemand, policyPrecedesDemand, demandPrecedesAuthorization,
		)) {
			block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
}

private fun SourceDemandEntity.hasActiveNoncaptureWifiShape(): Boolean = sourceKind == WIFI_SOURCE &&
	purpose != CAPTURE_PURPOSE && status == SourceDemandEntity.STATUS_ACTIVE && retireBootId == null &&
	retireElapsedRealtimeNanos == null && retiredAtMs == null

private fun SourceDemandEntity.matchesCurrentWifiPolicy(
	policy: SourcePolicyEntity,
	directPurpose: DirectSourceDemandPurpose,
	historicalAuthority: Pair<Long?, Boolean>,
	currentAuthority: Pair<Long?, Boolean>,
	expectedContract: SourceDemandContract?,
	actualContract: SourceDemandContract?,
): Boolean = policy.historicalWifiQosCode(directPurpose) == qosCode && expectedContract != null &&
	actualContract == expectedContract && historicalAuthority.first == consentEpoch &&
	historicalAuthority.second == persistenceEligible && currentAuthority.first == consentEpoch &&
	currentAuthority.second == persistenceEligible

private fun SourceDemandEntity.hasWifiCaptureRetirementShape(): Boolean = when (status) {
	SourceDemandEntity.STATUS_RETIRING -> hasWifiCaptureRetirementBoundary()
	SourceDemandEntity.STATUS_BLOCKED -> retireBootId == null && retireElapsedRealtimeNanos == null && retiredAtMs == null
	else -> false
}

private fun SourceDemandEntity.hasWifiCaptureRetirementBoundary(): Boolean {
	val retireBootId = retireBootId
	val retireElapsedNanos = retireElapsedRealtimeNanos
	val retiredAtMs = retiredAtMs
	return retireBootId != null && retireElapsedNanos != null && retiredAtMs != null &&
		retiredAtMs >= requestedAtMs &&
		(retireBootId != requestedBootId || retireElapsedNanos >= requestedElapsedRealtimeNanos)
}

private fun authenticateRetiringWifiCaptureDemand(
	demand: SourceDemandEntity,
	stateShape: Boolean,
	revokedPolicyRevision: Long,
	policy: SourcePolicyEntity?,
	consent: SourceConsentEpochEntity?,
) {
	if (!demand.hasRetiringWifiCaptureIdentity(stateShape, revokedPolicyRevision) || policy == null || consent == null) {
		block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!policy.matchesRetiringWifiCapturePolicy(demand)) {
		block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!consent.matchesRetiringWifiCaptureConsent(policy, demand)) {
		block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (demand.requestedBootId != policy.effectiveBootId ||
		demand.requestedElapsedRealtimeNanos < policy.effectiveElapsedRealtimeNanos ||
		demand.requestedAtMs < policy.effectiveWallTimeMs
	) {
		block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun SourceDemandEntity.hasRetiringWifiCaptureIdentity(
	stateShape: Boolean,
	revokedPolicyRevision: Long,
): Boolean = stateShape && sourceKind == WIFI_SOURCE && purpose == CAPTURE_PURPOSE && persistenceEligible &&
	!logicalTrackingId.isNullOrBlank() && !serviceRunId.isNullOrBlank() && manifestRevision != null &&
	lifecycleLeaseGeneration != null && sourcePolicyRevision < revokedPolicyRevision

private fun SourcePolicyEntity.matchesRetiringWifiCapturePolicy(demand: SourceDemandEntity): Boolean =
	enabled && capturePersistenceEligible && captureConsentEpoch == demand.consentEpoch && qosCode == demand.qosCode

private fun SourceConsentEpochEntity.matchesRetiringWifiCaptureConsent(
	policy: SourcePolicyEntity,
	demand: SourceDemandEntity,
): Boolean = eligible && persistenceEligible && policyRevision <= policy.policyRevision &&
	effectiveWallTimeMs <= demand.requestedAtMs &&
	(effectiveBootId != demand.requestedBootId || effectiveElapsedRealtimeNanos <= demand.requestedElapsedRealtimeNanos)

private fun requireWifiCaptureDeletionProvider(
	registration: ProviderRegistrationGenerationEntity,
	expectedCollectedDataEpoch: Long,
) {
	val activeShape = registration.hasActiveWifiDeletionProviderShape()
	val retiringShape = registration.hasRetiringWifiDeletionProviderShape()
	if ((!activeShape && !retiringShape) || registration.sourceKind != WIFI_SOURCE ||
		registration.ownerScope != "source-broker:$WIFI_SOURCE"
	) {
		block(WifiCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
	}
	if (registration.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
		registration.providerProcessIncarnationId.isNullOrBlank() ||
		registration.collectedDataEpoch != expectedCollectedDataEpoch
	) {
		block(WifiCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
	}
}

private fun ProviderRegistrationGenerationEntity.hasActiveWifiDeletionProviderShape(): Boolean =
	status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE && acceptedAtMs != null &&
	acceptedElapsedRealtimeNanos != null && retiredAtMs == null && retiredElapsedRealtimeNanos == null &&
	failureCode == null

private fun ProviderRegistrationGenerationEntity.hasRetiringWifiDeletionProviderShape(): Boolean =
	status == ProviderRegistrationGenerationEntity.STATUS_RETIRING && acceptedAtMs != null &&
	acceptedElapsedRealtimeNanos != null && retiredAtMs != null && retiredElapsedRealtimeNanos != null &&
	!failureCode.isNullOrBlank()

private suspend fun WifiCapturedFactMaintenance.loadWifiCaptureDeletionAuthorization(
	registration: ProviderRegistrationGenerationEntity,
	currentPolicy: SourcePolicyEntity,
	limits: WifiCapturedMaintenanceLimits,
): SourceAuthorizationSnapshot {
	val dao = database.wifiCapturedFactDao()
	val latestRevision = dao.maximumRegistrationAuthorizationRevision(
		WIFI_SOURCE,
		registration.registrationGeneration,
	)
	if (latestRevision <= 0L) {
		block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val rows = boundedAuthorizationMembers(
		registration.registrationGeneration,
		latestRevision,
		limits,
	)
	val authorization = rows.toSnapshotOrNull()
		?: block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (authorization.authorizedMembers.any { member ->
			member.persistenceEligible && member.purpose == CAPTURE_PURPOSE
		}
	) {
		block(WifiCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
	}
	val activeDemands = dao.activeDemandsForDeletion(
		WIFI_SOURCE,
		limits.maximumDirectDemands + 1,
	)
	if (activeDemands.size > limits.maximumDirectDemands) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	authenticateCurrentWifiDemands(activeDemands, currentPolicy, authorization)
	val expectedRows = try {
		SourceBrokerAuthorization.rows(
			sourceKind = WIFI_SOURCE,
			registrationGeneration = registration.registrationGeneration,
			authorizationRevision = latestRevision,
			demands = activeDemands,
			effectiveBootId = authorization.effectiveBootId,
			effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = rows.first().effectiveWallTimeMs,
		)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (rows.sortedBy { row -> row.memberId } != expectedRows.sortedBy { row -> row.memberId }) {
		block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return authorization
}
