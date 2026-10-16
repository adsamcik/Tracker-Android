package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.util.UUID

internal fun requireSourceRegistrationRequest(
	source: SourceKind,
	physicalConfigurationFingerprint: String,
	purposeEligibilityMask: Long,
) {
	require(source != SourceKind.ACTIVITY) {
		"Activity registrations are system-rearmable and must use ActivityRegistrationArbiter"
	}
	require(physicalConfigurationFingerprint.isNotBlank())
	require(
		purposeEligibilityMask > 0L &&
			(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK.inv()) == 0L,
	)
}

internal fun requireSourceRegistrationRefreshRequest(
	source: SourceKind,
	expectedRegistration: SourceRegistration,
	physicalConfigurationFingerprint: String,
	purposeEligibilityMask: Long,
) {
	require(source != SourceKind.ACTIVITY) {
		"Activity registrations are system-rearmable and must use ActivityRegistrationArbiter"
	}
	require(physicalConfigurationFingerprint.isNotBlank())
	require(expectedRegistration.state.sourceKind == source.stableCode)
	require(
		purposeEligibilityMask > 0L &&
			(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK.inv()) == 0L,
	)
}

// Construct the original next pointer only after allocating its generation; UUID creation stays in its original branch.
@Suppress("LongParameterList")
internal fun nextSourceRegistrationState(
	source: SourceKind,
	current: SourceRegistrationStateEntity?,
	ownerScope: String,
	registrationGeneration: Long,
	appliedRevision: Long,
	collectedDataEpoch: Long,
	clockDomainId: String,
	updatedAtMs: Long,
): SourceRegistrationStateEntity {
	return when {
		current == null -> SourceRegistrationStateEntity(
			sourceKind = source.stableCode,
			ownerScope = ownerScope,
			sourceInstanceId = UUID.randomUUID().toString(),
			clockDomainId = clockDomainId,
			registrationGeneration = registrationGeneration,
			nextSequence = 0L,
			appliedRevision = appliedRevision,
			collectedDataEpoch = collectedDataEpoch,
			updatedAtMs = updatedAtMs,
		)
		current.clockDomainId == clockDomainId && current.collectedDataEpoch == collectedDataEpoch ->
			current.copy(
				registrationGeneration = registrationGeneration,
				appliedRevision = appliedRevision,
				updatedAtMs = updatedAtMs,
			)
		else -> current.copy(
			sourceInstanceId = UUID.randomUUID().toString(),
			clockDomainId = clockDomainId,
			registrationGeneration = registrationGeneration,
			nextSequence = 0L,
			appliedRevision = appliedRevision,
			collectedDataEpoch = collectedDataEpoch,
			updatedAtMs = updatedAtMs,
		)
	}
}

internal fun ProviderRegistrationGenerationEntity.isCompatibleSystemLocationReservation(
	requestedResidency: String,
	expectedOwnerScope: String,
	systemPointer: SourceRegistrationStateEntity?,
	expectedFingerprint: String,
	expectedBootId: String,
	expectedEpoch: Long,
): Boolean = requestedResidency == ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE &&
	status != ProviderRegistrationGenerationEntity.STATUS_RETIRING && ownerScope == expectedOwnerScope &&
	systemPointer?.registrationGeneration == registrationGeneration &&
	systemPointer?.sourceInstanceId == sourceInstanceId && physicalConfigurationFingerprint == expectedFingerprint &&
	clockDomainId == expectedBootId && collectedDataEpoch == expectedEpoch

internal fun SourceRegistrationStateEntity?.canReuseProviderReservation(
	physical: ProviderRegistrationGenerationEntity?,
	expectedBootId: String,
	expectedEpoch: Long,
	requestedResidency: String,
	processIncarnationId: String,
	expectedFingerprint: String,
): Boolean {
	if (this == null || physical == null) {
		return false
	}
	return clockDomainId == expectedBootId && collectedDataEpoch == expectedEpoch &&
		physical.hasReusableRegistrationOwner(requestedResidency, processIncarnationId, expectedFingerprint)
}

private fun ProviderRegistrationGenerationEntity.hasReusableRegistrationOwner(
	requestedResidency: String,
	processIncarnationId: String,
	expectedFingerprint: String,
): Boolean = hasExactRegistrationProcess(requestedResidency, processIncarnationId) &&
	status in setOf(
		ProviderRegistrationGenerationEntity.STATUS_RESERVED, ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
	) &&
	physicalConfigurationFingerprint == expectedFingerprint

private fun ProviderRegistrationGenerationEntity.hasExactRegistrationProcess(
	requestedResidency: String,
	processIncarnationId: String,
): Boolean = providerResidency == requestedResidency && providerProcessIncarnationId ==
	if (requestedResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND) {
		processIncarnationId
	} else {
		null
	}

internal fun SourceRegistrationStateEntity.matchesRefreshedRegistrationIdentity(
	expected: SourceRegistrationStateEntity,
	expectedBootId: String,
	expectedEpoch: Long,
): Boolean = sourceInstanceId == expected.sourceInstanceId &&
	registrationGeneration == expected.registrationGeneration &&
	clockDomainId == expected.clockDomainId && collectedDataEpoch == expected.collectedDataEpoch &&
	clockDomainId == expectedBootId && collectedDataEpoch == expectedEpoch

internal fun ProviderRegistrationGenerationEntity.matchesRefreshedRegistrationOwner(
	expected: SourceRegistration,
	requestedResidency: String,
	processIncarnationId: String,
	expectedFingerprint: String,
): Boolean = hasExactRegistrationProcess(requestedResidency, processIncarnationId) &&
	status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE &&
	expected.physicalConfigurationFingerprint == expectedFingerprint &&
	physicalConfigurationFingerprint == expectedFingerprint

internal fun SourceDemandEntity.isLiveSessionlessRadioAmbientDemand(expectedSourceKind: Int): Boolean =
	sourceKind == expectedSourceKind && status == SourceDemandEntity.STATUS_ACTIVE &&
		logicalTrackingId == null && serviceRunId == null && manifestRevision == null && lifecycleLeaseGeneration == null &&
		retireBootId == null && retireElapsedRealtimeNanos == null && retiredAtMs == null && persistenceEligible

internal fun SourceDemandEntity.hasExactRadioAmbientCallbackGrant(
	current: SourcePolicyEntity,
	historical: SourcePolicyEntity,
	consent: SourceConsentEpochEntity?,
	authorization: SourceAuthorizationSnapshot,
): Boolean = historical.hasExactEligibleAmbientConsentReference(consent) &&
	current.ambientConsentEpoch == consentEpoch && historical.ambientConsentEpoch == consentEpoch &&
	consent != null && consent.policyRevision <= historical.policyRevision &&
	historical.isEffectiveAtOrBefore(requestedBootId, requestedElapsedRealtimeNanos, requestedAtMs) &&
	consent.isEffectiveAtOrBefore(requestedBootId, requestedElapsedRealtimeNanos, requestedAtMs) &&
	requestedBootId == authorization.effectiveBootId &&
	requestedElapsedRealtimeNanos <= authorization.effectiveElapsedRealtimeNanos
