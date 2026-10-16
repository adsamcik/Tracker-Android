package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.RadioPurposeItems
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadDecodeResult
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
import com.adsamcik.tracker.shared.base.database.qualifyWifiPurposeItems
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContract
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.ACCEPTED_REGISTRATION_STATES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURE_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.NONCAPTURE_BROKER_PURPOSES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_SOURCE

/**
 * Authenticates the physical/configuration/authorization envelope of a current pure noncapture
 * row without turning that row into captured history or a deletion-staleness authority. A
 * receive-time callback is not linked to an active-attempt plan revision; its exact physical
 * configuration remains bound by the registration fingerprint.
 */
internal suspend fun WifiCapturedFactMaintenance.authenticateNoncaptureWal(
	wal: SourceEventWalEntity,
	limits: WifiCapturedMaintenanceLimits,
	portable: WifiPortableAuthoritySnapshot? = null,
) {
	val physicalFingerprint = requireNotNull(wal.physicalConfigurationFingerprint)
	val authorizationRevision = requireNotNull(wal.authorizationRevision)
	val authorizationFingerprint = requireNotNull(wal.authorizationFingerprint)
	val observedStart = requireNotNull(wal.observedIntervalStartNanos)
	val registration = authenticatedNoncaptureWifiRegistration(wal, physicalFingerprint, observedStart, portable)
	val authorization = if (portable != null) {
		portable.authorizationsByKey[wal.registrationGeneration to authorizationRevision]
	} else {
		boundedAuthorizationMembers(
			wal.registrationGeneration,
			authorizationRevision,
			limits,
		).toSnapshotOrNull()
	}
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
		authenticateCurrentNoncaptureWifiItems(wal, registration, authorization, portable)
		return
	}
	authenticateLegacyNoncaptureWifiWal(wal, registration, authorization, authorizationFingerprint, limits, portable)
}

private suspend fun WifiCapturedFactMaintenance.authenticateHistoricalNoncaptureDemand(
	demand: SourceDemandEntity,
	authorization: SourceAuthorizationSnapshot,
) {
	val directPurpose = demand.nonCaptureDirectPurpose()
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val policyPurpose = demand.policyPurpose()
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val policy = database.sourcePolicyDao().policyAtRevision(demand.sourcePolicyRevision, WIFI_SOURCE)
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val (policyConsentEpoch, policyPersistenceEligible) = policy.authorityFor(policyPurpose)
	val consent = database.sourcePolicyDao().consentEpoch(
		WIFI_SOURCE,
		policyPurpose,
		demand.consentEpoch,
	) ?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val expectedContract = runCatching {
		policy.historicalWifiDemandContract(directPurpose)
	}.getOrNull()
	val actualContract = runCatching { demand.toSourceDemandContract() }.getOrNull()
	val consentPrecedesDemand = consent.isEffectiveAtOrBefore(
		demand.requestedBootId, demand.requestedElapsedRealtimeNanos, demand.requestedAtMs)
	val policyPrecedesDemand = policy.isEffectiveAtOrBefore(
		demand.requestedBootId, demand.requestedElapsedRealtimeNanos, demand.requestedAtMs)
	val demandPrecedesAuthorization = demand.precedesAuthorization(authorization)
	if (demand.sourceKind != WIFI_SOURCE || demand.purpose !in NONCAPTURE_BROKER_PURPOSES) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!demand.matchesHistoricalWifiPolicy(
		policy, directPurpose, policyConsentEpoch, policyPersistenceEligible, expectedContract, actualContract,
	)) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!consent.matchesHistoricalWifiDemandConsent(
		policy, demand, consentPrecedesDemand, policyPrecedesDemand, demandPrecedesAuthorization,
	)) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

internal suspend fun WifiCapturedFactMaintenance.authenticateCaptureGroups(
	wal: SourceEventWalEntity,
	payload: WifiResultSnapshotPayload,
	evidence: SourceEvidenceState,
	limits: WifiCapturedMaintenanceLimits,
	portable: WifiPortableAuthoritySnapshot? = null,
): List<AuthenticatedWifiWal> {
	if (wal.payloadVersion != WIFI_WAL_PAYLOAD_VERSION) {
		return listOf(authenticateCaptureWal(wal, payload, evidence, limits, portable))
	}
	val registration = if (portable != null) {
		portable.registrationsByGeneration[wal.registrationGeneration]
	} else {
		database.sourceBrokerDao().registration(WIFI_SOURCE, wal.registrationGeneration)
	}
	val start = registration?.acceptedElapsedRealtimeNanos
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val end = registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
	val decoded = (WifiWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload)
		as? WifiWalPayloadDecodeResult.Decoded)?.payload
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val items = (if (portable != null) {
		portable.qualifyPurposeItems(wal, decoded, start, end)
	} else {
		database.qualifyWifiPurposeItems(wal, decoded, start, end)
	})
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val members = items.captureMembersForPortableScope(portable)
	return members.map { authenticateCaptureWal(wal, payload, evidence, limits, portable, it, items) }
}

private suspend fun WifiCapturedFactMaintenance.authenticatedNoncaptureWifiRegistration(
	wal: SourceEventWalEntity,
	physicalFingerprint: String,
	observedStart: Long,
	portable: WifiPortableAuthoritySnapshot?,
): ProviderRegistrationGenerationEntity {
	val brokerDao = database.sourceBrokerDao()
	val registration = if (portable != null) {
		portable.registrationsByGeneration[wal.registrationGeneration]
	} else {
		brokerDao.registration(WIFI_SOURCE, wal.registrationGeneration)
	}
	val registrationStart = registration?.acceptedElapsedRealtimeNanos
	val registrationEnd = registration?.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
	if (registration == null || registrationStart == null) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!registration.hasNoncaptureWifiRegistrationIdentity(wal, physicalFingerprint)) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (wal.payloadVersion != WIFI_WAL_PAYLOAD_VERSION &&
		(observedStart < registrationStart || wal.observedElapsedNanos >= registrationEnd)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	return registration
}

private fun ProviderRegistrationGenerationEntity.hasNoncaptureWifiRegistrationIdentity(
	wal: SourceEventWalEntity,
	physicalFingerprint: String,
): Boolean = hasValidHistoricalShape() && sourceInstanceId == wal.sourceInstanceId &&
	ownerScope == "source-broker:$WIFI_SOURCE" &&
	providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
	this.physicalConfigurationFingerprint == physicalFingerprint && collectedDataEpoch == wal.capturedCollectedDataEpoch &&
	clockDomainId == wal.clockDomainId && status in ACCEPTED_REGISTRATION_STATES

private suspend fun WifiCapturedFactMaintenance.authenticateCurrentNoncaptureWifiItems(
	wal: SourceEventWalEntity,
	registration: ProviderRegistrationGenerationEntity,
	authorization: SourceAuthorizationSnapshot,
	portable: WifiPortableAuthoritySnapshot?,
) {
	val registrationStart = requireNotNull(registration.acceptedElapsedRealtimeNanos)
	val registrationEnd = registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
	val payload = (WifiWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload)
		as? WifiWalPayloadDecodeResult.Decoded)?.payload
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val items = (if (portable != null) {
		portable.qualifyPurposeItems(wal, payload, registrationStart, registrationEnd)
	}
	else {
		database.qualifyWifiPurposeItems(wal, payload, registrationStart, registrationEnd)
	})
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (items.callbackAuthorization != authorization) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	authenticateNoncaptureWifiItemDemands(items, portable)
	if (portable != null && portable.plansForRegistration(registration).none { plan ->
		authorization.authorizedMembers.all { member ->
			portable.planSatisfiesDemand(plan, items.immutableDemands.getValue(requireNotNull(member.demandId)))
		}
	}) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun WifiCapturedFactMaintenance.authenticateNoncaptureWifiItemDemands(
	items: RadioPurposeItems,
	portable: WifiPortableAuthoritySnapshot?,
) {
	for (snapshot in items.authorizationTimeline) {
		for (member in snapshot.authorizedMembers) {
			val demand = items.immutableDemands[member.demandId]
				?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			if (demand.purpose !in NONCAPTURE_BROKER_PURPOSES) {
				continue
			}
			if (portable != null) {
				if (!portable.authenticatesHistoricalDemand(demand, snapshot)) {
					block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
			} else {
				authenticateHistoricalNoncaptureDemand(demand, snapshot)
			}
		}
	}

}

private suspend fun WifiCapturedFactMaintenance.authenticateLegacyNoncaptureWifiWal(
	wal: SourceEventWalEntity,
	registration: ProviderRegistrationGenerationEntity,
	authorization: SourceAuthorizationSnapshot,
	authorizationFingerprint: String,
	limits: WifiCapturedMaintenanceLimits,
	portable: WifiPortableAuthoritySnapshot?,
) {
	val observedStart = requireNotNull(wal.observedIntervalStartNanos)
	val startAuthorization = if (portable != null) {
		portable.authorizationAt(wal.registrationGeneration, wal.clockDomainId, observedStart)
	} else {
		boundedAuthorizationAt(wal.registrationGeneration, wal.clockDomainId, observedStart, limits)
	}
	val endAuthorization = if (portable != null) {
		portable.authorizationAt(wal.registrationGeneration, wal.clockDomainId, wal.observedElapsedNanos)
	} else {
		boundedAuthorizationAt(
			wal.registrationGeneration,
			wal.clockDomainId,
			wal.observedElapsedNanos,
			limits,
		)
	}
	val demands = loadAuthenticatedLegacyWifiDemands(
		wal, authorization, startAuthorization, endAuthorization, authorizationFingerprint, portable,
	)
	authenticateNoncaptureWifiDemands(demands, authorization, portable)
	authenticatePortableNoncaptureWifiPlan(portable, registration, demands)
}

private suspend fun WifiCapturedFactMaintenance.loadAuthenticatedLegacyWifiDemands(
	wal: SourceEventWalEntity,
	authorization: SourceAuthorizationSnapshot,
	startAuthorization: SourceAuthorizationSnapshot?,
	endAuthorization: SourceAuthorizationSnapshot?,
	authorizationFingerprint: String,
	portable: WifiPortableAuthoritySnapshot?,
): List<SourceDemandEntity> {
	val brokerDao = database.sourceBrokerDao()
	val authorizationRevision = requireNotNull(wal.authorizationRevision)
	val observedStart = requireNotNull(wal.observedIntervalStartNanos)
	val members = authorization.authorizedMembers
	val demandIds = members.mapNotNull { member -> member.demandId }
	val demands = if (portable != null) {
		demandIds.mapNotNull(portable.demandsById::get)
	} else {
		brokerDao.demandsByIds(demandIds)
	}
	val recomputedAuthorization = runCatching {
		SourceBrokerAuthorization.rows(
			WIFI_SOURCE,
			wal.registrationGeneration,
			authorizationRevision,
			demands,
			authorization.effectiveBootId,
			authorization.effectiveElapsedRealtimeNanos,
			authorization.members.first().effectiveWallTimeMs,
		)
	}.getOrNull()
	val qualified = authorization.qualifiedForPortableFreshness(
		demands.associateBy(SourceDemandEntity::demandId),
		wal.observedElapsedNanos,
		wal.receivedElapsedNanos,
	) ?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (!authorization.hasLegacyWifiObservationAuthority(startAuthorization, endAuthorization, qualified)) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (qualified.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	requireExactLegacyWifiMembers(demandIds, demands, recomputedAuthorization, authorization)
	if (authorization.authorizationFingerprint != authorizationFingerprint ||
		authorization.effectiveBootId != wal.clockDomainId ||
		authorization.effectiveElapsedRealtimeNanos > observedStart
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return demands
}

private fun SourceAuthorizationSnapshot.hasLegacyWifiObservationAuthority(
	start: SourceAuthorizationSnapshot?,
	end: SourceAuthorizationSnapshot?,
	qualified: SourceAuthorizationSnapshot,
): Boolean = !isDenied && start == this && end == this &&
	qualified.authorizedMembers.none { it.purpose !in NONCAPTURE_BROKER_PURPOSES }

private fun requireExactLegacyWifiMembers(
	demandIds: List<String>,
	demands: List<SourceDemandEntity>,
	recomputed: List<SourceAuthorizationEntity>?,
	authorization: SourceAuthorizationSnapshot,
) {
	if (demandIds.distinct().size != authorization.authorizedMembers.size ||
		demands.size != authorization.authorizedMembers.size ||
		recomputed?.sortedBy { it.memberId } != authorization.members.sortedBy { it.memberId }
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun WifiCapturedFactMaintenance.authenticateNoncaptureWifiDemands(
	demands: List<SourceDemandEntity>,
	authorization: SourceAuthorizationSnapshot,
	portable: WifiPortableAuthoritySnapshot?,
) {
	demands.forEach { demand ->
		if (portable == null) {
			authenticateHistoricalNoncaptureDemand(demand, authorization)
		} else if (!portable.authenticatesHistoricalDemand(demand, authorization)) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}

}

private fun authenticatePortableNoncaptureWifiPlan(
	portable: WifiPortableAuthoritySnapshot?,
	registration: ProviderRegistrationGenerationEntity,
	demands: List<SourceDemandEntity>,
) {
	if (portable != null && portable.plansForRegistration(registration).none { plan ->
		demands.all { demand ->
			runCatching { demand.toSourceDemandContract() }.getOrNull()
				?.let(plan::satisfiesExactWifiContract) == true
		}
	}) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun SourceDemandEntity.matchesHistoricalWifiPolicy(
	policy: SourcePolicyEntity,
	directPurpose: DirectSourceDemandPurpose,
	policyConsentEpoch: Long?,
	policyPersistenceEligible: Boolean,
	expectedContract: SourceDemandContract?,
	actualContract: SourceDemandContract?,
): Boolean = policy.historicalWifiQosCode(directPurpose) == qosCode && policyConsentEpoch == consentEpoch &&
	policyPersistenceEligible == persistenceEligible && expectedContract != null && actualContract == expectedContract

internal fun SourceConsentEpochEntity.matchesHistoricalWifiDemandConsent(
	policy: SourcePolicyEntity,
	demand: SourceDemandEntity,
	consentPrecedesDemand: Boolean,
	policyPrecedesDemand: Boolean,
	demandPrecedesAuthorization: Boolean,
): Boolean = eligible && persistenceEligible == demand.persistenceEligible && policyRevision <= policy.policyRevision &&
	consentPrecedesDemand && policyPrecedesDemand && demandPrecedesAuthorization

private fun RadioPurposeItems.captureMembersForPortableScope(
	portable: WifiPortableAuthoritySnapshot?,
): List<SourceAuthorizationEntity> = historicalMembers().filter {
	it.purpose == CAPTURE_PURPOSE && it.persistenceEligible &&
		forDemand(requireNotNull(it.demandId)).isNotEmpty() &&
		(portable == null || it.logicalTrackingId == portable.session.logicalTrackingId &&
			it.serviceRunId in portable.runsById)
}.map { member ->
	authorizationTimeline.flatMap { it.authorizedMembers }.last { it.demandId == member.demandId }
}
