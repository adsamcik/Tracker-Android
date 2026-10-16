package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.tracker.source.runtime.hasExactAmbientStepsRetentionBinding
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.HistoricalAmbientAuthority
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.AmbientAuthority
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.Companion.SOURCE_KIND

internal suspend fun AmbientStepsFactImporter.historicalAmbientHandoffAuthority(
	command: AmbientStepsProviderHandoffCommand,
	lifecycle: CollectedDataLifecycleSnapshot,
	successor: AmbientAuthority,
	predecessorRegistration: ProviderRegistrationGenerationEntity,
): HistoricalAmbientAuthority? {
	val brokerDao = database.sourceBrokerDao()
	val cutoverElapsedRealtimeNanos = requireNotNull(predecessorRegistration.retiredElapsedRealtimeNanos)
	val authLookupTime = cutoverElapsedRealtimeNanos.takeIf { it > 0L }?.minus(1L)
		?: return null
	val predecessorAuthorization = brokerDao.authorizationAt(
		SOURCE_KIND,
		predecessorRegistration.registrationGeneration,
		predecessorRegistration.clockDomainId,
		authLookupTime,
	).toAuthorizationSnapshotOrNull()
		?: return null
	val cutoffAuthorization = brokerDao.authorizationAt(
		SOURCE_KIND,
		predecessorRegistration.registrationGeneration,
		predecessorRegistration.clockDomainId,
		cutoverElapsedRealtimeNanos,
	).toAuthorizationSnapshotOrNull()
		?: return null
	return historicalHandoffDemandsAndPolicy(
		command, lifecycle, successor, predecessorRegistration,
		predecessorAuthorization, cutoffAuthorization,
	)
}

private suspend fun AmbientStepsFactImporter.historicalHandoffDemandsAndPolicy(
	command: AmbientStepsProviderHandoffCommand,
	lifecycle: CollectedDataLifecycleSnapshot,
	successor: AmbientAuthority,
	predecessorRegistration: ProviderRegistrationGenerationEntity,
	predecessorAuthorization: SourceAuthorizationSnapshot,
	cutoffAuthorization: SourceAuthorizationSnapshot,
): HistoricalAmbientAuthority? {
	val brokerDao = database.sourceBrokerDao()
	val predecessorProvider = requireNotNull(predecessorRegistration.ambientStepsProviderOrNull())
	val cutoverWallTimeMs = requireNotNull(predecessorRegistration.retiredAtMs)
	val cutoverElapsedRealtimeNanos = requireNotNull(predecessorRegistration.retiredElapsedRealtimeNanos)
	val cutoffDemandIds = cutoffAuthorization.members.mapNotNull { it.demandId }.distinct()
	val cutoffDemands = if (cutoffDemandIds.isEmpty()) {
		emptyList()
	} else {
		brokerDao.demandsByIds(cutoffDemandIds)
	}
	if (!cutoffAuthorization.matchesHandoffCutoff(
			predecessorAuthorization, successor, predecessorProvider, cutoffDemands,
			cutoverWallTimeMs, cutoverElapsedRealtimeNanos,
		)
	) {
		return null
	}
	val demandIds = predecessorAuthorization.members.mapNotNull { it.demandId }.distinct()
	if (demandIds.isEmpty()) {
		return null
	}
	val predecessorDemands = brokerDao.demandsByIds(demandIds)
	if (!predecessorAuthorization.isExactRetiredAmbientAuthorization(
			registration = predecessorRegistration,
			demands = predecessorDemands,
			provider = predecessorProvider,
			cutoverBootId = command.observedBootId,
			cutoverElapsedRealtimeNanos = cutoverElapsedRealtimeNanos,
			cutoverWallTimeMs = cutoverWallTimeMs,
		)
	) {
		return null
	}
	return readHistoricalHandoffPolicy(
		lifecycle, predecessorRegistration, predecessorAuthorization, predecessorDemands,
	)
}

private suspend fun AmbientStepsFactImporter.readHistoricalHandoffPolicy(
	lifecycle: CollectedDataLifecycleSnapshot,
	predecessorRegistration: ProviderRegistrationGenerationEntity,
	predecessorAuthorization: SourceAuthorizationSnapshot,
	predecessorDemands: List<SourceDemandEntity>,
): HistoricalAmbientAuthority? {
	val predecessorPolicyRevision = predecessorAuthorization.members
		.mapNotNull { it.sourcePolicyRevision }.distinct().singleOrNull()
		?: return null
	val predecessorConsentEpoch = predecessorAuthorization.members
		.mapNotNull { it.consentEpoch }.distinct().singleOrNull()
		?: return null
	val policyDao = database.sourcePolicyDao()
	val predecessorPolicy = policyDao.policyAtRevision(predecessorPolicyRevision, SOURCE_KIND)
		?: return null
	return readHistoricalHandoffRetention(
		lifecycle, predecessorRegistration, predecessorAuthorization, predecessorDemands,
		predecessorPolicy, predecessorPolicyRevision, predecessorConsentEpoch,
	)
}

// These original policy, consent and registration rows form one complete historical grant.
@Suppress("LongParameterList")
private suspend fun AmbientStepsFactImporter.readHistoricalHandoffRetention(
	lifecycle: CollectedDataLifecycleSnapshot,
	predecessorRegistration: ProviderRegistrationGenerationEntity,
	predecessorAuthorization: SourceAuthorizationSnapshot,
	predecessorDemands: List<SourceDemandEntity>,
	predecessorPolicy: SourcePolicyEntity,
	predecessorPolicyRevision: Long,
	predecessorConsentEpoch: Long,
): HistoricalAmbientAuthority? {
	val policyDao = database.sourcePolicyDao()
	val cutoverWallTimeMs = requireNotNull(predecessorRegistration.retiredAtMs)
	val predecessorConsent = policyDao.consentEpoch(
		SOURCE_KIND,
		SourceBrokerPurpose.AMBIENT_PRODUCT,
		predecessorConsentEpoch,
	) ?: return null
	val predecessorRetention = historicalHandoffRetention(
		predecessorAuthorization, predecessorPolicyRevision, predecessorConsentEpoch, lifecycle,
	) ?: return null
	if (predecessorDemands.any { demand ->
			!demand.hasExactAmbientStepsRetentionBinding(predecessorRetention)
		}
	) {
		return null
	}
	return if (!predecessorPolicy.isHistoricalAmbientPolicy(
			predecessorConsent,
			predecessorAuthorization,
			predecessorRetention,
			cutoverWallTimeMs,
		)
	) {
		null
	} else {
		HistoricalAmbientAuthority(
			registration = predecessorRegistration,
			provider = requireNotNull(predecessorRegistration.ambientStepsProviderOrNull()),
			authorization = predecessorAuthorization,
			policy = predecessorPolicy,
			consent = predecessorConsent,
			retention = predecessorRetention,
			lifecycle = lifecycle,
		)
	}
}

private fun SourceAuthorizationSnapshot.matchesHandoffCutoff(
	predecessorAuthorization: SourceAuthorizationSnapshot,
	successor: AmbientAuthority,
	predecessorProvider: AmbientStepsProvider,
	cutoffDemands: List<SourceDemandEntity>,
	cutoverWallTimeMs: Long,
	cutoverElapsedRealtimeNanos: Long,
): Boolean = authorizationRevision > predecessorAuthorization.authorizationRevision &&
	authorizationFingerprint == successor.authorization.authorizationFingerprint &&
	purposeEligibilityMask == SourceBrokerPurpose.MASK_AMBIENT_PRODUCT &&
	effectiveElapsedRealtimeNanos == cutoverElapsedRealtimeNanos &&
	members.none { it.effectiveWallTimeMs != cutoverWallTimeMs } &&
	cutoffDemands.none { it.providerMatches(predecessorProvider) }

private suspend fun AmbientStepsFactImporter.historicalHandoffRetention(
	predecessorAuthorization: SourceAuthorizationSnapshot,
	predecessorPolicyRevision: Long,
	predecessorConsentEpoch: Long,
	lifecycle: CollectedDataLifecycleSnapshot,
): AmbientStepsRetentionAuthorityEntity? {
	return database.ambientStepsFactRevisionDao().retentionAuthorityAt(
		AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		predecessorAuthorization.effectiveBootId,
		predecessorAuthorization.effectiveElapsedRealtimeNanos,
	)?.takeIf(AmbientStepsRetentionAuthorityIntegrity::isAuthentic)
		?.takeIf { retention ->
			retention.hasHistoricalAmbientGrant(predecessorPolicyRevision, predecessorConsentEpoch, lifecycle)
		}
}
