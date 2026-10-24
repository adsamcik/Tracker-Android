package com.adsamcik.tracker.tracker.source.location

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalFailure
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.model.AltitudeContractVersions
import com.adsamcik.tracker.tracker.source.ingress.continuousAmbientSensorMembers
import com.adsamcik.tracker.tracker.source.model.LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.sourceQualityFromStableFlags

/** Reads independently allowed all-day Location inside the adapter's existing Room snapshot. */
internal suspend fun AppDatabase.qualifyAmbientLocationPhysical(
	physical: LocationPhysicalEvidence,
	registrationPlan: suspend (SourceEventWalEntity) -> LocationPlan?,
	exactMembership: (SourceEventWalEntity, SourceAuthorizationSnapshot, List<SourceDemandEntity>) -> Boolean,
	bootClockDomainProvider: BootClockDomainProvider,
): LocationAmbientQualification {
	return try {
		val wal = physical.wal
		if (wal.authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_AMBIENT_PRODUCT == 0L) {
			return LocationAmbientQualification.NotEligible
		}
		val lifecycle = ambientLocationLifecycle(wal)
		val (authorization, demands) = ambientLocationAuthorization(wal, exactMembership)
		val demand = ambientSourceDemandOrNull(authorization, demands)
			?: return LocationAmbientQualification.NotEligible
		val plan = registrationPlan(wal) ?: rejectAmbientLocation(LocationWalAdapterRejection.MISSING_HISTORICAL_PLAN)
		authenticateAmbientLocationRegistration(wal, plan, bootClockDomainProvider)
		val policy = ambientLocationPolicy(wal, demand)
		val retention = ambientLocationRetention(wal, demand)
		buildAmbientLocationCommand(physical, demand, plan, policy, retention, lifecycle)
	} catch (failure: LocationPhysicalEvidenceRejected) {
		LocationAmbientQualification.Rejected(failure.reason)
	}
}

private suspend fun AppDatabase.ambientLocationLifecycle(wal: SourceEventWalEntity): SourceEvidenceState {
	val lifecycle = sourceEvidenceStateDao().get()
		?: rejectAmbientLocation(LocationWalAdapterRejection.DELETED_EVIDENCE)
	if (lifecycle.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
		wal.admissionOrdinal <= lifecycle.deletedSourceEventHighWaterOrdinal) {
		rejectAmbientLocation(LocationWalAdapterRejection.DELETED_EVIDENCE)
	}
	if (collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
		rejectAmbientLocation(LocationWalAdapterRejection.RETAINED_EVIDENCE)
	}
	requireAmbientLocationQuality(wal)
	val earliest = ambientLocationWallIntervalStart(wal)
	if (wal.observedElapsedNanos <= 0L || wal.receivedElapsedNanos < wal.observedElapsedNanos ||
		lifecycle.retainedFromMs?.let { earliest < it } == true) {
		rejectAmbientLocation(LocationWalAdapterRejection.RETAINED_EVIDENCE)
	}
	return lifecycle
}

private fun requireAmbientLocationQuality(wal: SourceEventWalEntity) {
	val knownQualityMask = SourceQualityFlag.entries.fold(0L) { mask, flag -> mask or flag.bit }
	if (wal.qualityFlags and knownQualityMask != wal.qualityFlags ||
		wal.qualityConfidence?.let { !it.isFinite() || it !in 0f..1f } == true) {
		rejectAmbientLocation(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
	}
}

private fun ambientLocationWallIntervalStart(wal: SourceEventWalEntity): Long {
	val wall = wal.wallTimeMs ?: rejectAmbientLocation(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
	val uncertainty = wal.wallTimeUncertaintyMs
		?: rejectAmbientLocation(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
	val earliest = runCatching { Math.subtractExact(wall, uncertainty) }.getOrNull()
	if (earliest == null || earliest < 0L || uncertainty < 0L) {
		rejectAmbientLocation(LocationWalAdapterRejection.RETAINED_EVIDENCE)
	}
	if (wal.receivedWallTimeMs?.let { it < 0L } != false ||
		runCatching { Math.addExact(wall, uncertainty) }.isFailure) {
		rejectAmbientLocation(LocationWalAdapterRejection.RETAINED_EVIDENCE)
	}
	return earliest
}

private suspend fun AppDatabase.ambientLocationAuthorization(
	wal: SourceEventWalEntity,
	exactMembership: (SourceEventWalEntity, SourceAuthorizationSnapshot, List<SourceDemandEntity>) -> Boolean,
): Pair<SourceAuthorizationSnapshot, List<SourceDemandEntity>> {
	val broker = sourceBrokerDao()
	val revision = wal.authorizationRevision ?: rejectAmbientLocation(LocationWalAdapterRejection.MISSING_AUTHORIZATION)
	val rows = broker.authorizationRevisionBounded(LOCATION_SOURCE, wal.registrationGeneration,
		revision, LOCATION_WAL_AUTHORIZATION_MEMBER_LIMIT + 1)
	if (rows.size > LOCATION_WAL_AUTHORIZATION_MEMBER_LIMIT) {
		rejectAmbientLocation(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
	}
	val authorization = rows.toAuthorizationSnapshotOrNull()
		?: rejectAmbientLocation(LocationWalAdapterRejection.MISSING_AUTHORIZATION)
	val observed = broker.authorizationAtBounded(LOCATION_SOURCE, wal.registrationGeneration, wal.clockDomainId,
		wal.observedElapsedNanos, LOCATION_WAL_AUTHORIZATION_MEMBER_LIMIT + 1).toAuthorizationSnapshotOrNull()
	val ids = authorization.authorizedMembers.mapNotNull { it.demandId }
	val demands = broker.demandsByIds(ids)
	val recomputed = runCatching {
		SourceBrokerAuthorization.rows(LOCATION_SOURCE, wal.registrationGeneration, revision, demands,
			authorization.effectiveBootId, authorization.effectiveElapsedRealtimeNanos,
			authorization.members.first().effectiveWallTimeMs)
	}.getOrNull()
	requireAmbientLocationAuthorizationShape(authorization, observed, ids, demands)
	if (observed != authorization && broker.continuousAmbientSensorMembers(authorization,
		authorization.effectiveElapsedRealtimeNanos, wal.observedElapsedNanos).none {
			it.demandId == ambientSourceDemandOrNull(authorization, demands)?.demandId
		}) {
		rejectAmbientLocation(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
	}
	if (recomputed?.sortedBy { it.memberId } != rows.sortedBy { it.memberId } ||
		authorization.authorizationFingerprint != wal.authorizationFingerprint ||
		!exactMembership(wal, authorization, demands) || authorization.effectiveBootId != wal.clockDomainId) {
		rejectAmbientLocation(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
	}
	return authorization to demands
}

private fun requireAmbientLocationAuthorizationShape(
	authorization: SourceAuthorizationSnapshot,
	observed: SourceAuthorizationSnapshot?,
	ids: List<String>,
	demands: List<SourceDemandEntity>,
) {
	if (authorization.isDenied || observed == null ||
		ids.distinct().size != authorization.authorizedMembers.size || demands.size != ids.size) {
		rejectAmbientLocation(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
	}
}

private fun ambientSourceDemandOrNull(
	authorization: SourceAuthorizationSnapshot,
	demands: List<SourceDemandEntity>,
): SourceDemandEntity? {
	val member = authorization.authorizedMembers.singleOrNull {
		it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && it.persistenceEligible &&
			it.logicalTrackingId == null && it.serviceRunId == null
	} ?: return null
	return demands.singleOrNull { it.demandId == member.demandId }
		?: rejectAmbientLocation(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH)
}

private suspend fun AppDatabase.authenticateAmbientLocationRegistration(
	wal: SourceEventWalEntity,
	plan: LocationPlan,
	bootClockDomainProvider: BootClockDomainProvider,
) {
	val proof = authenticateHistoricalProviderRegistration(
		sourceKind = LOCATION_SOURCE,
		registrationGeneration = wal.registrationGeneration,
		sourceInstanceId = wal.sourceInstanceId,
		clockDomainId = wal.clockDomainId,
		physicalConfigurationFingerprint = requireNotNull(wal.physicalConfigurationFingerprint),
		collectedDataEpoch = wal.capturedCollectedDataEpoch,
		expectedProviderResidency = if (plan.passiveSystemDelivery) {
			ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE
		} else {
			ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND
		},
		observedStartNanos = wal.observedElapsedNanos,
		observedEndNanos = wal.observedElapsedNanos,
		observedWallTimeMs = requireNotNull(wal.wallTimeMs),
		currentClockDomainId = bootClockDomainProvider.current(),
	)
	if (proof is ProviderRegistrationIntervalAuthentication.Rejected) {
		rejectAmbientLocation(if (proof.reason == ProviderRegistrationIntervalFailure.MISSING_REGISTRATION) {
			LocationWalAdapterRejection.MISSING_REGISTRATION
		} else {
			LocationWalAdapterRejection.REGISTRATION_MISMATCH
		})
	}
}

private suspend fun AppDatabase.ambientLocationPolicy(
	wal: SourceEventWalEntity, demand: SourceDemandEntity,
): SourcePolicyEntity {
	val dao = sourcePolicyDao()
	val policy = dao.policyAtRevision(demand.sourcePolicyRevision, LOCATION_SOURCE)
		?: rejectAmbientLocation(LocationWalAdapterRejection.MISSING_POLICY)
	val consent = dao.consentEpoch(LOCATION_SOURCE, SourceBrokerPurpose.AMBIENT_PRODUCT, demand.consentEpoch)
	if (!policy.hasExactEligibleAmbientConsentReference(consent) ||
		(policy.effectiveBootId == wal.clockDomainId && policy.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos)) {
		rejectAmbientLocation(LocationWalAdapterRejection.CONSENT_MISMATCH)
	}
	if (consent == null ||
		(consent.effectiveBootId == wal.clockDomainId && consent.effectiveElapsedRealtimeNanos > wal.observedElapsedNanos)) {
		rejectAmbientLocation(LocationWalAdapterRejection.CONSENT_MISMATCH)
	}
	return policy
}

private suspend fun AppDatabase.ambientLocationRetention(
	wal: SourceEventWalEntity,
	demand: SourceDemandEntity,
): AmbientLocationRetentionAuthorityEntity {
	val retention = locationPurposeDao().retentionAuthorityAt(
		AmbientLocationRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, wal.clockDomainId, wal.observedElapsedNanos)
		?: rejectAmbientLocation(LocationWalAdapterRejection.RETAINED_EVIDENCE)
	if (!retention.matchesAmbientLocation(wal, demand)) {
		rejectAmbientLocation(LocationWalAdapterRejection.RETAINED_EVIDENCE)
	}
	return retention
}

private fun AmbientLocationRetentionAuthorityEntity.matchesAmbientLocation(
	wal: SourceEventWalEntity,
	demand: SourceDemandEntity,
): Boolean = isActive && AmbientLocationRetentionAuthorityIntegrity.isAuthentic(this) &&
	approvalRevision == demand.liveAmbientRetentionApprovalRevision &&
	opaquePolicyId == demand.liveAmbientRetentionPolicyId &&
	sourcePolicyRevision == demand.sourcePolicyRevision && ambientConsentEpoch == demand.consentEpoch &&
	collectedDataEpoch == wal.capturedCollectedDataEpoch && effectiveWallTimeMs <= requireNotNull(wal.wallTimeMs)

private fun buildAmbientLocationCommand(
	physical: LocationPhysicalEvidence,
	demand: SourceDemandEntity,
	plan: LocationPlan,
	policy: SourcePolicyEntity,
	retention: AmbientLocationRetentionAuthorityEntity,
	lifecycle: SourceEvidenceState,
): LocationAmbientQualification {
	val wal = physical.wal
	val maximumAccuracy = policy.locationRequiredAccuracyMeters?.toFloat()
		?: rejectAmbientLocation(LocationWalAdapterRejection.POLICY_MISMATCH)
	val maximumAge = runCatching { Math.multiplyExact(demand.maximumAgeMs, LOCATION_WAL_NANOS_PER_MILLISECOND) }
		.getOrDefault(Long.MAX_VALUE)
	val quality = sourceQualityFromStableFlags(wal.qualityFlags, wal.qualityConfidence)
	if (physical.payload.isMock == null || wal.payloadVersion !in setOf(LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
		LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION) || wal.receivedElapsedNanos - wal.observedElapsedNanos > maximumAge) {
		rejectAmbientLocation(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
	}
	if (SourceQualityFlag.CACHED in quality.flags ||
		(SourceQualityFlag.APPROXIMATE in quality.flags) == plan.preciseLocationAvailable ||
		LocationQualifiedObservationQualifier.payloadRejection(physical.payload, maximumAccuracy) != null) {
		rejectAmbientLocation(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
	}
	return LocationAmbientQualification.Qualified(LocationAmbientFactCommand(wal, physical.payload,
		LocationAmbientAuthority(demand.demandId, demand.sourcePolicyRevision, demand.consentEpoch,
			retention.opaquePolicyId, retention.approvalRevision, lifecycle.collectedDataEpoch,
			lifecycle.retainedFromMs, maximumAge, maximumAccuracy),
		LocationWalAcquisitionMetadata(plan.canonicalAcquisitionMode(), plan.canonicalRequestPriority(), maximumAccuracy,
			policy.qosCode.toLocationPolicyTier(), policy.qosCode.toLocationPolicyName(),
			PROTECTED_LOCATION_CANONICAL_CURATION_VERSION, AltitudeContractVersions.MODEL_VERSION,
			AltitudeContractVersions.ESTIMATOR_VERSION, AltitudeContractVersions.CALIBRATION_VERSION)))
}

private fun rejectAmbientLocation(reason: LocationWalAdapterRejection): Nothing =
	throw LocationPhysicalEvidenceRejected(reason)

private const val LOCATION_SOURCE = 1
