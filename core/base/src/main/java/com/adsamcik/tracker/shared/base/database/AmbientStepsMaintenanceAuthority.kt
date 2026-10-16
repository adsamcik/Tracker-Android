package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore

// Keep missing authorization and exact member/demand rejection exits in their original read order.
@Suppress("ReturnCount")
internal suspend fun AppDatabase.authenticateAmbientFactAuthority(
	fact: AmbientStepsFactRevisionEntity,
	cursor: AmbientStepsImportCursorEntity,
	limits: AmbientStepsMaintenanceLimits,
): Boolean {
	val authorizationRevision = fact.authorizationRevision ?: return false
	val rows = sourceBrokerDao().authorizationRevisionBounded(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		cursor.registrationGeneration,
		authorizationRevision,
		limits.maximumAuthorizationMembersPerRevision + 1,
	)
	requireAmbientStepsMaintenanceBound(
		rows.size <= limits.maximumAuthorizationMembersPerRevision,
		"Ambient Steps authorization-member maintenance bound exceeded",
	)
	val first = rows.firstOrNull() ?: return false
	if (first.authorizationRevision != authorizationRevision ||
		first.purposeEligibilityMask != SourceBrokerPurpose.MASK_AMBIENT_PRODUCT ||
		rows.any { row ->
		row.hasConflictingAmbientStepsAuthority(first, fact, cursor)
	}) {
		return false
	}
	return authenticateAmbientStepsMaintenanceDemands(fact, cursor, first, rows)
}

private suspend fun AppDatabase.authenticateAmbientStepsMaintenanceDemands(
	fact: AmbientStepsFactRevisionEntity,
	cursor: AmbientStepsImportCursorEntity,
	first: SourceAuthorizationEntity,
	rows: List<SourceAuthorizationEntity>,
): Boolean {
	val demandIds = rows.mapNotNull(SourceAuthorizationEntity::demandId)
	if (demandIds.size != rows.size || demandIds.distinct().size != demandIds.size) {
		return false
	}
	val demands = demandIds.chunked(AUTHORITY_QUERY_BATCH_SIZE).flatMap { ids ->
		sourceBrokerDao().demandsByIds(ids)
	}
	if (demands.size != demandIds.size || demands.map(SourceDemandEntity::demandId).toSet() !=
		demandIds.toSet() || SourceBrokerAuthorization.fingerprint(demands) != fact.authorizationFingerprint
	) {
		return false
	}
	if (demands.any { demand ->
		demand.hasConflictingAmbientStepsAuthority(fact, cursor, first)
	}) {
		return false
	}
	return hasAmbientStepsPolicyRetentionAuthority(fact, first, demands)
}

// Resolve the original policy, consent and retention references after exact demand reconstruction.
@Suppress("ReturnCount")
private suspend fun AppDatabase.hasAmbientStepsPolicyRetentionAuthority(
	fact: AmbientStepsFactRevisionEntity,
	first: SourceAuthorizationEntity,
	demands: List<SourceDemandEntity>,
): Boolean {
	val policyRevision = fact.sourcePolicyRevision ?: return false
	val consentEpoch = fact.ambientConsentEpoch ?: return false
	val policy = sourcePolicyDao().policyAtRevision(
		policyRevision,
		SourceDestinationOwnerEntity.SOURCE_STEPS,
	) ?: return false
	val consent = sourcePolicyDao().consentEpoch(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceBrokerPurpose.AMBIENT_PRODUCT,
		consentEpoch,
	) ?: return false
	val retention = qualifiedAmbientStepsMaintenanceRetention(fact, first, policyRevision, consentEpoch)
		?: return false
	if (demands.hasPrematureAmbientAuthorityRequest(policy, consent, retention)) {
		return false
	}
	return policy.hasAmbientAuthority(
		consent,
		requireNotNull(fact.windowStartTimeMs),
		first,
	)
}

private suspend fun AppDatabase.qualifiedAmbientStepsMaintenanceRetention(
	fact: AmbientStepsFactRevisionEntity,
	first: SourceAuthorizationEntity,
	policyRevision: Long,
	consentEpoch: Long,
): AmbientStepsRetentionAuthorityEntity? {
	val retentionScope = fact.retentionScope
	val retentionPolicyId = fact.retentionPolicyId
	val retentionApprovalRevision = fact.retentionApprovalRevision
	if (retentionScope == null || retentionPolicyId == null || retentionApprovalRevision == null) {
		return null
	}
	val retention = ambientStepsFactRevisionDao().retentionAuthorityAt(
		retentionScope,
		first.effectiveBootId,
		first.effectiveElapsedRealtimeNanos,
	) ?: return null
	if (retention.hasConflictingAmbientFactAuthority(
		fact, first, retentionPolicyId, retentionApprovalRevision, policyRevision, consentEpoch,
	)
	) {
		return null
	}
	return retention
}

// One saved retention row matches its exact original fact, consent and authorization boundary.
@Suppress("CyclomaticComplexMethod")
private fun AmbientStepsRetentionAuthorityEntity.hasConflictingAmbientFactAuthority(
	fact: AmbientStepsFactRevisionEntity,
	first: SourceAuthorizationEntity,
	retentionPolicyId: String,
	retentionApprovalRevision: Long,
	policyRevision: Long,
	consentEpoch: Long,
): Boolean =
	!AmbientStepsRetentionAuthorityIntegrity.isAuthentic(this) ||
		!this.isActive ||
		this.opaquePolicyId != retentionPolicyId ||
		this.approvalRevision != retentionApprovalRevision ||
		this.sourcePolicyRevision != policyRevision ||
		this.ambientConsentEpoch != consentEpoch ||
		this.collectedDataEpoch != fact.collectedDataEpoch ||
		this.effectiveBootId != first.effectiveBootId ||
		this.effectiveElapsedRealtimeNanos > first.effectiveElapsedRealtimeNanos ||
		this.effectiveWallTimeMs > first.effectiveWallTimeMs

private fun List<SourceDemandEntity>.hasPrematureAmbientAuthorityRequest(
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
	retention: AmbientStepsRetentionAuthorityEntity,
): Boolean = any { demand ->
	!policy.isEffectiveAtOrBefore(
		demand.requestedBootId,
		demand.requestedElapsedRealtimeNanos,
		demand.requestedAtMs,
	) ||
		!consent.isEffectiveAtOrBefore(
			demand.requestedBootId,
			demand.requestedElapsedRealtimeNanos,
			demand.requestedAtMs,
		) ||
		!retention.isEffectiveAtOrBefore(
			demand.requestedBootId,
			demand.requestedElapsedRealtimeNanos,
			demand.requestedAtMs,
		)
}

// One saved member must match its complete historical ambient fact, clock and first-member tuple.
@Suppress("CyclomaticComplexMethod")
private fun SourceAuthorizationEntity.hasConflictingAmbientStepsAuthority(
	first: SourceAuthorizationEntity,
	fact: AmbientStepsFactRevisionEntity,
	cursor: AmbientStepsImportCursorEntity,
): Boolean =
	this.sourceKind != first.sourceKind ||
		this.registrationGeneration != first.registrationGeneration ||
		this.authorizationRevision != first.authorizationRevision ||
		this.authorizationFingerprint != fact.authorizationFingerprint || this.isDenyAll ||
		this.purposeEligibilityMask != first.purposeEligibilityMask ||
		this.effectiveBootId != first.effectiveBootId ||
		this.effectiveElapsedRealtimeNanos != first.effectiveElapsedRealtimeNanos ||
		this.effectiveWallTimeMs != first.effectiveWallTimeMs ||
		this.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT || !this.persistenceEligible ||
		this.sourcePolicyRevision != fact.sourcePolicyRevision ||
		this.consentEpoch != fact.ambientConsentEpoch || this.logicalTrackingId != null ||
		this.serviceRunId != null || this.manifestRevision != null ||
		this.lifecycleLeaseGeneration != null || this.effectiveBootId != cursor.registrationClockDomainId ||
		this.effectiveWallTimeMs > requireNotNull(fact.windowStartTimeMs) ||
		this.effectiveWallTimeMs != first.effectiveWallTimeMs

// One saved demand must match the complete fact purpose, ownership and original request boundary.
@Suppress("CyclomaticComplexMethod")
private fun SourceDemandEntity.hasConflictingAmbientStepsAuthority(
	fact: AmbientStepsFactRevisionEntity,
	cursor: AmbientStepsImportCursorEntity,
	first: SourceAuthorizationEntity,
): Boolean =
	this.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
		this.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT || !this.persistenceEligible ||
		this.sourcePolicyRevision != fact.sourcePolicyRevision ||
		this.consentEpoch != fact.ambientConsentEpoch || this.logicalTrackingId != null ||
		this.serviceRunId != null || this.manifestRevision != null ||
		this.lifecycleLeaseGeneration != null || this.requestedBootId != cursor.registrationClockDomainId ||
		this.requestedElapsedRealtimeNanos > first.effectiveElapsedRealtimeNanos ||
		this.requestedAtMs > first.effectiveWallTimeMs

internal fun AmbientStepsFactRevisionEntity.hasAmbientStepsMaintenanceHistoricalAuthority(
	cursor: AmbientStepsImportCursorEntity,
	transitions: List<AmbientStepsImportAuthorityTransitionEntity>,
): Boolean {
	val generation = continuitySegmentGeneration
	val start = windowStartTimeMs
	val end = windowEndTimeMs
	if (generation == null || start == null || end == null) {
		return false
	}
	if (transitions.isEmpty()) {
		return matchesAmbientStepsMaintenanceCursorAuthority(cursor) &&
			start >= cursor.eligibleFromTimeMs && end <= cursor.importedThroughTimeMs
	}
	var phaseStart = cursor.registrationAcceptedAtMs
	var firstSegmentGeneration = 1L
	// The separately authenticated initial wall and immutable tuple constrain each correction phase.
	val matchedTransition = transitions.any { transition ->
		val matches = matchesAmbientStepsMaintenanceTransitionPhase(
			transition, generation, start, end, firstSegmentGeneration, phaseStart,
		)
		firstSegmentGeneration = transition.toContinuitySegmentGeneration
		phaseStart = transition.effectiveBoundaryTimeMs
		matches
	}
	return matchedTransition || matchesAmbientStepsMaintenanceCursorPhase(
		cursor, generation, start, end, firstSegmentGeneration, phaseStart,
	)
}

private fun AmbientStepsFactRevisionEntity.matchesAmbientStepsMaintenanceTransitionPhase(
	transition: AmbientStepsImportAuthorityTransitionEntity,
	generation: Long,
	start: Long,
	end: Long,
	firstSegmentGeneration: Long,
	phaseStart: Long,
): Boolean = generation in firstSegmentGeneration..transition.fromContinuitySegmentGeneration &&
	start >= phaseStart && end <= transition.effectiveBoundaryTimeMs &&
	matchesAmbientStepsMaintenanceTransitionAuthority(transition)

private fun AmbientStepsFactRevisionEntity.matchesAmbientStepsMaintenanceCursorPhase(
	cursor: AmbientStepsImportCursorEntity,
	generation: Long,
	start: Long,
	end: Long,
	firstSegmentGeneration: Long,
	phaseStart: Long,
): Boolean = generation in firstSegmentGeneration..cursor.continuitySegmentGeneration &&
	start >= phaseStart && end <= cursor.importedThroughTimeMs &&
	matchesAmbientStepsMaintenanceCursorAuthority(cursor)

private fun AmbientStepsFactRevisionEntity.matchesAmbientStepsMaintenanceCursorAuthority(
	cursor: AmbientStepsImportCursorEntity,
): Boolean = authorizationRevision == cursor.authorizationRevision &&
	authorizationFingerprint == cursor.authorizationFingerprint &&
	sourcePolicyRevision == cursor.sourcePolicyRevision && ambientConsentEpoch == cursor.ambientConsentEpoch &&
	retentionScope == cursor.retentionScope && retentionPolicyId == cursor.retentionPolicyId &&
	retentionApprovalRevision == cursor.retentionApprovalRevision

private fun AmbientStepsFactRevisionEntity.matchesAmbientStepsMaintenanceTransitionAuthority(
	transition: AmbientStepsImportAuthorityTransitionEntity,
): Boolean = authorizationRevision == transition.fromAuthorizationRevision &&
	authorizationFingerprint == transition.fromAuthorizationFingerprint &&
	sourcePolicyRevision == transition.fromSourcePolicyRevision &&
	ambientConsentEpoch == transition.fromAmbientConsentEpoch

internal fun List<AmbientStepsImportAuthorityTransitionEntity>.hasExactAmbientStepsMaintenanceAuthorityChain(
	cursor: AmbientStepsImportCursorEntity,
): Boolean = if (isEmpty()) {
	cursor.authorityTransitionSequence == 0L
} else {
	zipWithNext().all { (previous, next) -> next.followsAmbientStepsMaintenanceTransition(previous) } &&
		last().endsAtAmbientStepsMaintenanceCursor(cursor)
}

private fun AmbientStepsImportAuthorityTransitionEntity.followsAmbientStepsMaintenanceTransition(
	previous: AmbientStepsImportAuthorityTransitionEntity,
): Boolean = fromContinuitySegmentGeneration >= previous.toContinuitySegmentGeneration &&
	fromAuthorizationRevision == previous.toAuthorizationRevision &&
	fromAuthorizationFingerprint == previous.toAuthorizationFingerprint &&
	fromSourcePolicyRevision == previous.toSourcePolicyRevision &&
	fromAmbientConsentEpoch == previous.toAmbientConsentEpoch &&
	effectiveBoundaryTimeMs >= previous.effectiveBoundaryTimeMs && recordedAtMs >= previous.recordedAtMs

private fun AmbientStepsImportAuthorityTransitionEntity.endsAtAmbientStepsMaintenanceCursor(
	cursor: AmbientStepsImportCursorEntity,
): Boolean = toContinuitySegmentGeneration <= cursor.continuitySegmentGeneration &&
	toAuthorizationRevision == cursor.authorizationRevision &&
	toAuthorizationFingerprint == cursor.authorizationFingerprint &&
	toAuthorizationEffectiveBootId == cursor.authorizationEffectiveBootId &&
	toAuthorizationEffectiveElapsedRealtimeNanos == cursor.authorizationEffectiveElapsedRealtimeNanos &&
	toAuthorizationEffectiveWallTimeMs == cursor.authorizationEffectiveWallTimeMs &&
	toSourcePolicyRevision == cursor.sourcePolicyRevision && toAmbientConsentEpoch == cursor.ambientConsentEpoch &&
	effectiveBoundaryTimeMs <= cursor.segmentStartTimeMs

private fun SourcePolicyEntity.hasAmbientAuthority(
	consent: SourceConsentEpochEntity,
	windowStartTimeMs: Long,
	authorization: SourceAuthorizationEntity,
): Boolean = sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
	hasExactEligibleAmbientConsentReference(consent) &&
	isEffectiveAtOrBefore(
		authorization.effectiveBootId,
		authorization.effectiveElapsedRealtimeNanos,
		authorization.effectiveWallTimeMs,
	) &&
	consent.isEffectiveAtOrBefore(
		authorization.effectiveBootId,
		authorization.effectiveElapsedRealtimeNanos,
		authorization.effectiveWallTimeMs,
	) &&
	authorization.effectiveWallTimeMs <= windowStartTimeMs &&
	consent.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT

internal fun ProviderRegistrationGenerationEntity.matchesAmbientStepsMaintenanceCursor(
	cursor: AmbientStepsImportCursorEntity,
): Boolean = sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
	registrationGeneration == cursor.registrationGeneration &&
	sourceInstanceId == cursor.sourceInstanceId && clockDomainId == cursor.registrationClockDomainId &&
	ownerScope == SourceProviderPurposeScope.exactOwnerScope(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
	) && physicalConfigurationFingerprint ==
	"ambient-steps-provider:v1:mechanism=${cursor.provider}" &&
	collectedDataEpoch == cursor.collectedDataEpoch && acceptedAtMs == cursor.registrationAcceptedAtMs &&
	acceptedElapsedRealtimeNanos == cursor.registrationAcceptedElapsedRealtimeNanos

internal fun AmbientStepsFactRevisionEntity.hasSameAmbientStepsMaintenanceOrigin(
	other: AmbientStepsFactRevisionEntity,
): Boolean = provider == other.provider && registrationGeneration == other.registrationGeneration &&
	continuitySegmentGeneration == other.continuitySegmentGeneration &&
	sourceInstanceId == other.sourceInstanceId && windowStartTimeMs == other.windowStartTimeMs &&
	structuralEpochDay == other.structuralEpochDay && storedZoneId == other.storedZoneId &&
	structuralDayStartTimeMs == other.structuralDayStartTimeMs &&
	structuralDayEndTimeMs == other.structuralDayEndTimeMs

private const val AUTHORITY_QUERY_BATCH_SIZE = 256
