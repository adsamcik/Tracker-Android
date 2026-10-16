package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.Preflight
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.AuthorityResolution
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.CursorResolution
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.AmbientAuthority
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.Companion.SOURCE_KIND
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.Companion.OWNER_SCOPE

internal suspend fun AmbientStepsFactImporter.resolvePreflight(
	boundary: AmbientStepsImportBoundary,
	lifecycle: CollectedDataLifecycleSnapshot,
): Preflight {
	if (startupGate?.permitsNewCollection == false) {
		return Preflight.Outcome(ineligible(AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE))
	}
	val authority = when (val resolved = resolveAuthority(boundary, lifecycle)) {
		is AuthorityResolution.Outcome -> return Preflight.Outcome(resolved.result)
		is AuthorityResolution.Ready -> resolved.authority
	}
	val storedCursor = database.ambientStepsImportStateDao().cursor(
		authority.registration.registrationGeneration,
	)
	val cursorResolution = prepareCursor(authority, storedCursor, boundary)
	if (cursorResolution is CursorResolution.Outcome) {
		return Preflight.Outcome(cursorResolution.result)
	}
	cursorResolution as CursorResolution.Ready
	return resolvePreparedCursorWindow(authority, cursorResolution.cursor, boundary, lifecycle)
}

private suspend fun AmbientStepsFactImporter.resolvePreparedCursorWindow(
	authority: AmbientAuthority,
	effectiveCursor: AmbientStepsImportCursorEntity,
	boundary: AmbientStepsImportBoundary,
	lifecycle: CollectedDataLifecycleSnapshot,
): Preflight {
	val resume = reconcileImportResumeBoundary(effectiveCursor, boundary)
	if (resume != null) {
		return resume
	}
	val retention = reconcileImportRetentionBoundary(effectiveCursor, boundary, lifecycle)
	if (retention != null) {
		return retention
	}
	val calendar = reconcileImportCalendarBoundary(effectiveCursor, boundary)
	if (calendar != null) {
		return calendar
	}
	return planPreparedImportWindow(authority, effectiveCursor, boundary)
}

private suspend fun AmbientStepsFactImporter.reconcileImportResumeBoundary(
	effectiveCursor: AmbientStepsImportCursorEntity,
	boundary: AmbientStepsImportBoundary,
): Preflight.Outcome? {
	val resumeAtMs = startupGate?.collectionResumeBoundary?.wallTimeMs?.let(::roundForwardToSecond)
	if (resumeAtMs != null && resumeAtMs > effectiveCursor.importedThroughTimeMs) {
		if (resumeAtMs > boundary.throughTimeMs) {
			return Preflight.Outcome(
				AmbientStepsImportResult.NoEvidence(AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE),
			)
		}
		advanceAcrossGap(
			effectiveCursor, AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED,
			resumeAtMs, boundary.zoneId.id, boundary,
		)
		return Preflight.Outcome(
			AmbientStepsImportResult.Gap(
				AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED,
				effectiveCursor.importedThroughTimeMs, resumeAtMs,
			),
		)
	}
	return null
}

private suspend fun AmbientStepsFactImporter.reconcileImportRetentionBoundary(
	effectiveCursor: AmbientStepsImportCursorEntity,
	boundary: AmbientStepsImportBoundary,
	lifecycle: CollectedDataLifecycleSnapshot,
): Preflight.Outcome? {
	val retainedFromMs = lifecycle.retainedFromMs?.let(::roundForwardToSecond)
	if (retainedFromMs != null && retainedFromMs > effectiveCursor.importedThroughTimeMs) {
		advanceAcrossGap(
			cursor = effectiveCursor,
			reason = AmbientStepsImportGapReason.RETENTION_ADVANCED,
			toTimeMs = retainedFromMs,
			nextZoneId = effectiveCursor.lastObservedZoneId,
			boundary = boundary,
		)
		return Preflight.Outcome(
			AmbientStepsImportResult.Gap(
				reason = AmbientStepsImportGapReason.RETENTION_ADVANCED,
				fromTimeMs = effectiveCursor.importedThroughTimeMs,
				toTimeMs = retainedFromMs,
			),
		)
	}
	return null
}

private suspend fun AmbientStepsFactImporter.reconcileImportCalendarBoundary(
	effectiveCursor: AmbientStepsImportCursorEntity,
	boundary: AmbientStepsImportBoundary,
): Preflight.Outcome? {
	if (effectiveCursor.lastObservedZoneId != boundary.zoneId.id) {
		if (boundary.throughTimeMs < effectiveCursor.importedThroughTimeMs) {
			return Preflight.Outcome(
				AmbientStepsImportResult.NoEvidence(
					AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
				),
			)
		}
		advanceAcrossGap(
			cursor = effectiveCursor,
			reason = AmbientStepsImportGapReason.ZONE_CHANGED,
			toTimeMs = boundary.throughTimeMs,
			nextZoneId = boundary.zoneId.id,
			boundary = boundary,
		)
		return Preflight.Outcome(
			AmbientStepsImportResult.Gap(
				reason = AmbientStepsImportGapReason.ZONE_CHANGED,
				fromTimeMs = effectiveCursor.importedThroughTimeMs,
				toTimeMs = boundary.throughTimeMs,
			),
		)
	}
	return null
}

private fun AmbientStepsFactImporter.planPreparedImportWindow(
	authority: AmbientAuthority,
	effectiveCursor: AmbientStepsImportCursorEntity,
	boundary: AmbientStepsImportBoundary,
): Preflight {
	if (boundary.throughTimeMs < effectiveCursor.importedThroughTimeMs) {
		return Preflight.Outcome(
			AmbientStepsImportResult.NoEvidence(
				AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
			),
		)
	}
	val plan = planner.planProgressive(
		segmentStartTimeMs = effectiveCursor.segmentStartTimeMs,
		importedThroughTimeMs = effectiveCursor.importedThroughTimeMs,
		throughTimeMs = boundary.throughTimeMs,
		zoneId = boundary.zoneId,
	)
	val window = plan.windows.singleOrNull()
		?: return Preflight.Outcome(
			AmbientStepsImportResult.NoEvidence(
				AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
			),
		)
	return Preflight.Ready(
		authority = authority,
		storedCursor = effectiveCursor,
		window = window,
	)
}

internal suspend fun AmbientStepsFactImporter.resolveAuthority(
	boundary: AmbientStepsImportBoundary,
	lifecycle: CollectedDataLifecycleSnapshot,
): AuthorityResolution {
	val brokerDao = database.sourceBrokerDao()
	val state = database.sourceRegistrationStateDao().get(SOURCE_KIND, OWNER_SCOPE)
		?: return AuthorityResolution.Outcome(
			ineligible(AmbientStepsImportIneligibleReason.NO_ACTIVE_REGISTRATION),
		)
	val registration = brokerDao.registration(SOURCE_KIND, state.registrationGeneration)
		?: return AuthorityResolution.Outcome(
			ineligible(AmbientStepsImportIneligibleReason.NO_ACTIVE_REGISTRATION),
		)
	val provider = acceptedAmbientImportProvider(registration, state, lifecycle, boundary)
		?: return AuthorityResolution.Outcome(ineligible(AmbientStepsImportIneligibleReason.REGISTRATION_NOT_ACCEPTED))
	return resolveCurrentAmbientImportPolicy(boundary, lifecycle, state, registration, provider)
}

private fun acceptedAmbientImportProvider(
	registration: ProviderRegistrationGenerationEntity,
	state: SourceRegistrationStateEntity,
	lifecycle: CollectedDataLifecycleSnapshot,
	boundary: AmbientStepsImportBoundary,
): AmbientStepsProvider? {
	if (!registration.isAcceptedAmbientState(state, lifecycle, boundary)) {
		return null
	}
	return registration.ambientStepsProviderOrNull()
}

private suspend fun AmbientStepsFactImporter.resolveCurrentAmbientImportPolicy(
	boundary: AmbientStepsImportBoundary,
	lifecycle: CollectedDataLifecycleSnapshot,
	state: SourceRegistrationStateEntity,
	registration: ProviderRegistrationGenerationEntity,
	provider: AmbientStepsProvider,
): AuthorityResolution {
	val policyDao = database.sourcePolicyDao()
	val policyAuthority = policyDao.authority()
	if (policyAuthority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) {
		return AuthorityResolution.Outcome(
			ineligible(AmbientStepsImportIneligibleReason.POLICY_INACTIVE),
		)
	}
	val policy = policyDao.policyAtRevision(policyAuthority.currentPolicyRevision, SOURCE_KIND)
	if (policy == null || !policy.isCurrentAmbientPolicy(boundary)) {
		return AuthorityResolution.Outcome(
			ineligible(AmbientStepsImportIneligibleReason.AMBIENT_POLICY_INELIGIBLE),
		)
	}
	if (state.appliedRevision != policy.policyRevision) {
		return AuthorityResolution.Outcome(
			ineligible(AmbientStepsImportIneligibleReason.AMBIENT_POLICY_INELIGIBLE),
		)
	}
	return resolveCurrentAmbientImportConsent(boundary, lifecycle, state, registration, provider, policy)
}

private suspend fun AmbientStepsFactImporter.resolveCurrentAmbientImportConsent(
	boundary: AmbientStepsImportBoundary,
	lifecycle: CollectedDataLifecycleSnapshot,
	state: SourceRegistrationStateEntity,
	registration: ProviderRegistrationGenerationEntity,
	provider: AmbientStepsProvider,
	policy: SourcePolicyEntity,
): AuthorityResolution {
	val consent = database.sourcePolicyDao().latestConsentEpoch(SOURCE_KIND, SourceBrokerPurpose.AMBIENT_PRODUCT)
	if (!consent.isExactEligibleAmbient(policy, boundary)) {
		return AuthorityResolution.Outcome(
			ineligible(AmbientStepsImportIneligibleReason.AMBIENT_CONSENT_INELIGIBLE),
		)
	}
	val retention = database.ambientStepsFactRevisionDao().latestRetentionAuthority(
		AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
	)
	if (retention == null || !retention.hasCurrentAmbientGrant(policy, requireNotNull(consent), lifecycle) ||
		!retention.hasCurrentAmbientBoundary(boundary)
	) {
		return AuthorityResolution.Outcome(
			ineligible(AmbientStepsImportIneligibleReason.RETENTION_AUTHORITY_UNAVAILABLE),
		)
	}
	return resolveAmbientImportDemandAuthority(
		boundary, lifecycle, state, registration, provider, policy, requireNotNull(consent), retention,
	)
}

// The immutable registration/policy/consent tuple stays explicit; no partial authority or mutable carrier is created.
@Suppress("LongParameterList")
private suspend fun AmbientStepsFactImporter.resolveAmbientImportDemandAuthority(
	boundary: AmbientStepsImportBoundary,
	lifecycle: CollectedDataLifecycleSnapshot,
	state: SourceRegistrationStateEntity,
	registration: ProviderRegistrationGenerationEntity,
	provider: AmbientStepsProvider,
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
	retention: AmbientStepsRetentionAuthorityEntity,
): AuthorityResolution {
	val brokerDao = database.sourceBrokerDao()
	val demands = SourceProviderPurposeScope.selectDemands(
		SOURCE_KIND,
		OWNER_SCOPE,
		brokerDao.authorizationDemands(SOURCE_KIND),
	)
	if (demands.isEmpty() || demands.any { demand ->
		!demand.isRetentionEligibleAmbientDemand(
			policy,
			requireNotNull(consent),
			retention,
			provider,
			boundary,
		)
	}) {
		return AuthorityResolution.Outcome(
			ineligible(AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE),
		)
	}
	val authorization = brokerDao.latestAuthorization(SOURCE_KIND, state.registrationGeneration)
		.toAuthorizationSnapshotOrNull()
	if (!authorization.isRetentionEligibleAmbientAuthorization(
			demands,
			policy,
			requireNotNull(consent),
			retention,
			registration,
			boundary,
		)
	) {
		return AuthorityResolution.Outcome(
			ineligible(AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE),
		)
	}
	val exactAuthorization = requireNotNull(authorization)
	val authorizationEffectiveWallTimeMs = exactAuthorization.members.first().effectiveWallTimeMs

	return resolveAmbientImportDestinationAuthority(
		lifecycle, state, registration, provider, policy, consent, retention, demands,
		exactAuthorization, authorizationEffectiveWallTimeMs,
	)
}

// Assembles the existing complete authority only after its original destination and lifecycle checks.
@Suppress("LongParameterList")
private suspend fun AmbientStepsFactImporter.resolveAmbientImportDestinationAuthority(
	lifecycle: CollectedDataLifecycleSnapshot,
	state: SourceRegistrationStateEntity,
	registration: ProviderRegistrationGenerationEntity,
	provider: AmbientStepsProvider,
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
	retention: AmbientStepsRetentionAuthorityEntity,
	demands: List<SourceDemandEntity>,
	exactAuthorization: SourceAuthorizationSnapshot,
	authorizationEffectiveWallTimeMs: Long,
): AuthorityResolution {
	val owner = database.sourceDestinationOwnerDao().get(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
	)
	if (owner?.owner != SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS) {
		return AuthorityResolution.Outcome(
			ineligible(AmbientStepsImportIneligibleReason.DESTINATION_OWNER_INELIGIBLE),
		)
	}
	val evidenceState = database.sourceEvidenceStateDao().get()
	if (evidenceState != null && (
		evidenceState.collectedDataEpoch > lifecycle.epoch ||
			evidenceState.retainedFromMs.isAfter(lifecycle.retainedFromMs)
		)
	) {
		return AuthorityResolution.Outcome(
			stale(AmbientStepsImportStaleReason.LIFECYCLE_CHANGED),
		)
	}
	return AuthorityResolution.Ready(
		AmbientAuthority(
			state = state,
			registration = registration,
			provider = provider,
			authorization = exactAuthorization,
			authorizationEffectiveWallTimeMs = authorizationEffectiveWallTimeMs,
			policy = policy,
			consent = requireNotNull(consent),
			retention = retention,
			demands = demands,
			ownerGeneration = owner.ownerGeneration,
			lifecycle = lifecycle,
		),
	)
}

private fun Long?.isAfter(other: Long?): Boolean = when {
	this == null -> false
	other == null -> true
	else -> this > other
}
