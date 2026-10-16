package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.runtime.hasExactAmbientStepsRetentionBinding
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
internal fun ProviderRegistrationGenerationEntity.isAcceptedAmbientState(
	state: SourceRegistrationStateEntity,
	lifecycle: CollectedDataLifecycleSnapshot,
	boundary: AmbientStepsImportBoundary,
): Boolean =
	sourceKind == state.sourceKind &&
		registrationGeneration == state.registrationGeneration &&
		sourceInstanceId == state.sourceInstanceId &&
		clockDomainId == state.clockDomainId &&
		collectedDataEpoch == state.collectedDataEpoch &&
		collectedDataEpoch == lifecycle.epoch &&
		ownerScope == state.ownerScope &&
		providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE &&
		providerProcessIncarnationId == null &&
		status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE &&
		acceptedAtMs?.let { it <= boundary.observedAtMs } == true &&
		acceptedElapsedRealtimeNanos?.let { it <= boundary.observedElapsedRealtimeNanos } == true &&
		clockDomainId == boundary.observedBootId &&
		state.appliedRevision != null

internal fun SourcePolicyEntity.isCurrentAmbientPolicy(
	boundary: AmbientStepsImportBoundary,
): Boolean = ambientPersistenceEligible && ambientConsentEpoch != null &&
	isEffectiveAtOrBefore(
		boundary.observedBootId,
		boundary.observedElapsedRealtimeNanos,
		boundary.observedAtMs,
	)

internal fun SourceConsentEpochEntity?.isExactEligibleAmbient(
	policy: SourcePolicyEntity,
	boundary: AmbientStepsImportBoundary,
): Boolean =
	policy.hasExactEligibleAmbientConsentReference(this) &&
		requireNotNull(this).isEffectiveAtOrBefore(
			boundary.observedBootId,
			boundary.observedElapsedRealtimeNanos,
			boundary.observedAtMs,
		)

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
internal fun SourceDemandEntity.isRetentionEligibleAmbientDemand(
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
	retention: AmbientStepsRetentionAuthorityEntity,
	provider: AmbientStepsProvider,
	boundary: AmbientStepsImportBoundary,
): Boolean = purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
	logicalTrackingId == null && serviceRunId == null && manifestRevision == null &&
	lifecycleLeaseGeneration == null &&
	sourcePolicyRevision == policy.policyRevision &&
	consentEpoch == policy.ambientConsentEpoch && persistenceEligible &&
	requestedBootId == boundary.observedBootId &&
	policy.isEffectiveAtOrBefore(
		requestedBootId,
		requestedElapsedRealtimeNanos,
		requestedAtMs,
	) &&
	consent.isEffectiveAtOrBefore(
		requestedBootId,
		requestedElapsedRealtimeNanos,
		requestedAtMs,
	) &&
	requestedElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos &&
	requestedAtMs <= boundary.observedAtMs &&
	requestedBootId == retention.effectiveBootId &&
	requestedElapsedRealtimeNanos >= retention.effectiveElapsedRealtimeNanos &&
	requestedAtMs >= retention.effectiveWallTimeMs &&
	hasExactAmbientStepsRetentionBinding(retention) &&
	providerMatches(provider)

internal fun SourceDemandEntity.providerMatches(provider: AmbientStepsProvider): Boolean {
	val floor = runCatching { toSourceDemandContract().floor as AmbientStepsAcquisitionFloor }
		.getOrNull() ?: return false
	val mechanism = when (provider) {
		AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS ->
			AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
		AmbientStepsProvider.LOCAL_RECORDING_STEPS ->
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS
	}
	return floor.mechanism == mechanism
}

internal fun SourceAuthorizationSnapshot.isExactRetiredAmbientAuthorization(
	registration: ProviderRegistrationGenerationEntity,
	demands: List<SourceDemandEntity>,
	provider: AmbientStepsProvider,
	cutoverBootId: String,
	cutoverElapsedRealtimeNanos: Long,
	cutoverWallTimeMs: Long,
): Boolean {
	val effectiveWallTimeMs = members.firstOrNull()?.effectiveWallTimeMs ?: return false
	val authorizedDemandIds = members.mapNotNull { it.demandId }.toSet()
	return hasRetiredAmbientAuthorizationBoundary(
		registration, demands, cutoverBootId, cutoverElapsedRealtimeNanos, cutoverWallTimeMs, effectiveWallTimeMs,
	) && authorizedDemandIds.isNotEmpty() &&
		authorizedDemandIds == demands.map(SourceDemandEntity::demandId).toSet() &&
		hasExactRetiredAmbientMembers(effectiveWallTimeMs) && demands.all { demand ->
			demand.hasExactRetiredAmbientScope(registration, provider) &&
				demand.hasExactRetiredAmbientBoundary(
					this, cutoverBootId, cutoverElapsedRealtimeNanos, cutoverWallTimeMs, effectiveWallTimeMs,
				)
		}
}

private fun SourceAuthorizationSnapshot.hasRetiredAmbientAuthorizationBoundary(
	registration: ProviderRegistrationGenerationEntity,
	demands: List<SourceDemandEntity>,
	cutoverBootId: String,
	cutoverElapsedRealtimeNanos: Long,
	cutoverWallTimeMs: Long,
	effectiveWallTimeMs: Long,
): Boolean = !isDenied && purposeEligibilityMask == SourceBrokerPurpose.MASK_AMBIENT_PRODUCT &&
	authorizationFingerprint == SourceBrokerAuthorization.fingerprint(demands) &&
	effectiveBootId == registration.clockDomainId && effectiveBootId == cutoverBootId &&
	effectiveElapsedRealtimeNanos < cutoverElapsedRealtimeNanos && effectiveWallTimeMs <= cutoverWallTimeMs

private fun SourceAuthorizationSnapshot.hasExactRetiredAmbientMembers(effectiveWallTimeMs: Long): Boolean =
	members.all { member ->
		!member.isDenyAll && member.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
			member.persistenceEligible && member.effectiveBootId == effectiveBootId &&
			member.effectiveElapsedRealtimeNanos == effectiveElapsedRealtimeNanos &&
			member.effectiveWallTimeMs == effectiveWallTimeMs
	}

private fun SourceDemandEntity.hasExactRetiredAmbientScope(
	registration: ProviderRegistrationGenerationEntity,
	provider: AmbientStepsProvider,
): Boolean = sourceKind == registration.sourceKind && purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
	logicalTrackingId == null && serviceRunId == null && manifestRevision == null && lifecycleLeaseGeneration == null &&
	persistenceEligible && providerMatches(provider)

private fun SourceDemandEntity.hasExactRetiredAmbientBoundary(
	authorization: SourceAuthorizationSnapshot,
	cutoverBootId: String,
	cutoverElapsedRealtimeNanos: Long,
	cutoverWallTimeMs: Long,
	effectiveWallTimeMs: Long,
): Boolean = status == SourceDemandEntity.STATUS_RETIRED && retireBootId == cutoverBootId &&
	retireElapsedRealtimeNanos == cutoverElapsedRealtimeNanos && retiredAtMs == cutoverWallTimeMs &&
	requestedBootId == authorization.effectiveBootId &&
	requestedElapsedRealtimeNanos <= authorization.effectiveElapsedRealtimeNanos && requestedAtMs <= effectiveWallTimeMs

internal fun SourcePolicyEntity.isHistoricalAmbientPolicy(
	consent: SourceConsentEpochEntity,
	authorization: SourceAuthorizationSnapshot,
	retention: AmbientStepsRetentionAuthorityEntity,
	cutoverWallTimeMs: Long,
): Boolean {
	val authorizationWallTimeMs = authorization.members.firstOrNull()?.effectiveWallTimeMs
		?: return false
	return hasExactEligibleAmbientConsentReference(consent) &&
		isEffectiveAtOrBefore(
			authorization.effectiveBootId,
			authorization.effectiveElapsedRealtimeNanos,
			authorizationWallTimeMs,
		) &&
		consent.isEffectiveAtOrBefore(
			authorization.effectiveBootId,
			authorization.effectiveElapsedRealtimeNanos,
			authorizationWallTimeMs,
		) &&
		retention.isEffectiveAtOrBefore(
			authorization.effectiveBootId,
			authorization.effectiveElapsedRealtimeNanos,
			authorizationWallTimeMs,
		) &&
		authorizationWallTimeMs <= cutoverWallTimeMs &&
		authorization.members.all { member ->
			member.sourcePolicyRevision == policyRevision &&
				member.consentEpoch == consent.epoch
		}
}

internal fun SourceAuthorizationSnapshot?.isRetentionEligibleAmbientAuthorization(
	demands: List<SourceDemandEntity>,
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
	retention: AmbientStepsRetentionAuthorityEntity,
	registration: ProviderRegistrationGenerationEntity,
	boundary: AmbientStepsImportBoundary,
): Boolean {
	if (this == null) {
		return false
	}
	return hasCurrentAmbientAuthorizationBoundary(demands, registration, boundary) &&
		hasEffectiveAmbientPolicyTuple(policy, consent, retention) &&
		hasRetentionEligibleAmbientMembers(demands, policy, boundary)
}

internal fun AmbientStepsRetentionAuthorityEntity.hasCurrentAmbientGrant(
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
	lifecycle: CollectedDataLifecycleSnapshot,
): Boolean = AmbientStepsRetentionAuthorityIntegrity.isAuthentic(this) &&
	hasHistoricalAmbientGrant(policy.policyRevision, consent.epoch, lifecycle)

internal fun AmbientStepsRetentionAuthorityEntity.hasCurrentAmbientBoundary(
	boundary: AmbientStepsImportBoundary,
): Boolean = effectiveBootId == boundary.observedBootId &&
	effectiveElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos && effectiveWallTimeMs <= boundary.observedAtMs

private fun SourceAuthorizationSnapshot.hasCurrentAmbientAuthorizationBoundary(
	demands: List<SourceDemandEntity>,
	registration: ProviderRegistrationGenerationEntity,
	boundary: AmbientStepsImportBoundary,
): Boolean = !isDenied && purposeEligibilityMask == SourceBrokerPurpose.MASK_AMBIENT_PRODUCT &&
	authorizationFingerprint == SourceBrokerAuthorization.fingerprint(demands) &&
	effectiveBootId == registration.clockDomainId && effectiveBootId == boundary.observedBootId &&
	effectiveElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos

private fun SourceAuthorizationSnapshot.hasEffectiveAmbientPolicyTuple(
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
	retention: AmbientStepsRetentionAuthorityEntity,
): Boolean = policy.isEffectiveAtOrBefore(
	effectiveBootId, effectiveElapsedRealtimeNanos, members.first().effectiveWallTimeMs,
) && consent.isEffectiveAtOrBefore(
	effectiveBootId, effectiveElapsedRealtimeNanos, members.first().effectiveWallTimeMs,
) && retention.isEffectiveAtOrBefore(
	effectiveBootId, effectiveElapsedRealtimeNanos, members.first().effectiveWallTimeMs,
)

private fun SourceAuthorizationSnapshot.hasRetentionEligibleAmbientMembers(
	demands: List<SourceDemandEntity>,
	policy: SourcePolicyEntity,
	boundary: AmbientStepsImportBoundary,
): Boolean = members.mapNotNull { it.demandId }.toSet() == demands.map { it.demandId }.toSet() &&
	members.all { member ->
		!member.isDenyAll && member.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
			member.sourcePolicyRevision == policy.policyRevision && member.consentEpoch == policy.ambientConsentEpoch &&
			member.persistenceEligible && member.effectiveWallTimeMs <= boundary.observedAtMs &&
			member.effectiveWallTimeMs == members.first().effectiveWallTimeMs
	}

internal fun AmbientStepsRetentionAuthorityEntity.hasHistoricalAmbientGrant(
	policyRevision: Long,
	consentEpoch: Long,
	lifecycle: CollectedDataLifecycleSnapshot,
): Boolean = isActive && sourcePolicyRevision == policyRevision && ambientConsentEpoch == consentEpoch &&
	collectedDataEpoch == lifecycle.epoch && retainedFromMs == lifecycle.retainedFromMs
