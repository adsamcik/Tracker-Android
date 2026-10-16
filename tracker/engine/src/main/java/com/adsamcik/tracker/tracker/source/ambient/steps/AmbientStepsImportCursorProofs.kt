package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.HistoricalAmbientAuthority
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.AmbientAuthority

internal suspend fun AmbientStepsFactImporter.hasExactCursorSiblings(
	cursor: AmbientStepsImportCursorEntity,
): Boolean {
	val stateDao = database.ambientStepsImportStateDao()
	val gaps = stateDao.gaps(cursor.registrationGeneration)
	if (!gaps.hasExactCursorGapJournal(cursor)) {
		return false
	}
	val transitions = stateDao.authorityTransitions(cursor.registrationGeneration)
	if (!transitions.hasExactCursorAuthorityJournal(cursor)) {
		return false
	}
	val latestTransition = transitions.lastOrNull() ?: return true
	return latestTransition.matchesCursorAuthority(cursor)
}

private fun List<AmbientStepsImportGapEntity>.hasExactCursorGapJournal(
	cursor: AmbientStepsImportCursorEntity,
): Boolean = size.toLong() == cursor.lastGapSequence && withIndex().none { (index, gap) ->
	gap.gapSequence != index + 1L || gap.registrationGeneration != cursor.registrationGeneration ||
		gap.provider != cursor.provider || gap.sourceInstanceId != cursor.sourceInstanceId ||
		gap.collectedDataEpoch != cursor.collectedDataEpoch || gap.recordedAtMs > cursor.updatedAtMs
}

private fun List<AmbientStepsImportAuthorityTransitionEntity>.hasExactCursorAuthorityJournal(
	cursor: AmbientStepsImportCursorEntity,
): Boolean = size.toLong() == cursor.authorityTransitionSequence && withIndex().none { (index, transition) ->
	transition.transitionSequence != index + 1L || transition.registrationGeneration != cursor.registrationGeneration ||
		transition.provider != cursor.provider || transition.sourceInstanceId != cursor.sourceInstanceId ||
		transition.collectedDataEpoch != cursor.collectedDataEpoch || transition.recordedAtMs > cursor.updatedAtMs
}

private fun AmbientStepsImportAuthorityTransitionEntity.matchesCursorAuthority(
	cursor: AmbientStepsImportCursorEntity,
): Boolean = toAuthorizationRevision == cursor.authorizationRevision &&
	toAuthorizationFingerprint == cursor.authorizationFingerprint &&
	toAuthorizationEffectiveBootId == cursor.authorizationEffectiveBootId &&
	toAuthorizationEffectiveElapsedRealtimeNanos == cursor.authorizationEffectiveElapsedRealtimeNanos &&
	toAuthorizationEffectiveWallTimeMs == cursor.authorizationEffectiveWallTimeMs &&
	toSourcePolicyRevision == cursor.sourcePolicyRevision && toAmbientConsentEpoch == cursor.ambientConsentEpoch &&
	effectiveBoundaryTimeMs == cursor.eligibleFromTimeMs &&
	toContinuitySegmentGeneration <= cursor.continuitySegmentGeneration

// Compares one saved cursor with every field of the supplied historical authority tuple.
@Suppress("CyclomaticComplexMethod")
internal fun AmbientStepsImportCursorEntity.matchesHistoricalAuthority(
	authority: AmbientStepsFactImporter.HistoricalAmbientAuthority,
	expectedStatus: String,
): Boolean = registrationGeneration == authority.registration.registrationGeneration &&
	provider == authority.provider.name && sourceInstanceId == authority.registration.sourceInstanceId &&
	registrationClockDomainId == authority.registration.clockDomainId &&
	registrationAcceptedAtMs == authority.registration.acceptedAtMs &&
	registrationAcceptedElapsedRealtimeNanos == authority.registration.acceptedElapsedRealtimeNanos &&
	authorizationRevision == authority.authorization.authorizationRevision &&
	authorizationFingerprint == authority.authorization.authorizationFingerprint &&
	authorizationEffectiveBootId == authority.authorization.effectiveBootId &&
	authorizationEffectiveElapsedRealtimeNanos ==
		authority.authorization.effectiveElapsedRealtimeNanos &&
	authorizationEffectiveWallTimeMs == authority.authorizationEffectiveWallTimeMs &&
	sourcePolicyRevision == authority.policy.policyRevision &&
	ambientConsentEpoch == authority.consent.epoch &&
	retentionScope == authority.retention.scope &&
	retentionPolicyId == authority.retention.opaquePolicyId &&
	retentionApprovalRevision == authority.retention.approvalRevision &&
	collectedDataEpoch == authority.lifecycle.epoch &&
	eligibleFromTimeMs == authority.privacyFloorTimeMs &&
	status == expectedStatus

internal fun AmbientStepsImportCursorEntity.matchesRegistrationAuthority(
	authority: AmbientStepsFactImporter.AmbientAuthority,
): Boolean = registrationGeneration == authority.registration.registrationGeneration &&
	provider == authority.provider.name &&
	sourceInstanceId == authority.registration.sourceInstanceId &&
	registrationClockDomainId == authority.registration.clockDomainId &&
	registrationAcceptedAtMs == authority.registration.acceptedAtMs &&
	registrationAcceptedElapsedRealtimeNanos == authority.registration.acceptedElapsedRealtimeNanos &&
	collectedDataEpoch == authority.lifecycle.epoch &&
	status == AmbientStepsImportCursorEntity.STATUS_ACTIVE

// Compares one stored cursor with the supplied authorization snapshot field by field.
@Suppress("CyclomaticComplexMethod")
internal fun AmbientStepsImportCursorEntity.matchesCurrentAuthorization(
	authority: AmbientStepsFactImporter.AmbientAuthority,
): Boolean = authorizationRevision == authority.authorization.authorizationRevision &&
	authorizationFingerprint == authority.authorization.authorizationFingerprint &&
	authorizationEffectiveBootId == authority.authorization.effectiveBootId &&
	authorizationEffectiveElapsedRealtimeNanos ==
	authority.authorization.effectiveElapsedRealtimeNanos &&
	authorizationEffectiveWallTimeMs == authority.authorizationEffectiveWallTimeMs &&
	sourcePolicyRevision == authority.policy.policyRevision &&
	ambientConsentEpoch == authority.policy.ambientConsentEpoch &&
	retentionScope == authority.retention.scope &&
	retentionPolicyId == authority.retention.opaquePolicyId &&
	retentionApprovalRevision == authority.retention.approvalRevision &&
	eligibleFromTimeMs == authority.privacyFloorTimeMs
