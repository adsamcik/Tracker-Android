package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity

/**
 * Proves that a retained WAL-only scope was genuinely capture-authorized before it can receive a
 * no-resurrection fence. This deliberately does not decode provider payload: deletion neither
 * materializes nor classifies it, but it must not trust caller-shaped scope scalars either.
 */
@Suppress("LongMethod")
internal suspend fun AppDatabase.authenticateCapturedCellWalScopeForDeletion(
	wal: SourceEventWalEntity,
	limits: CellCapturedMaintenanceLimits,
	historicalMember: SourceAuthorizationEntity? = null,
	purposeItems: CellPurposeItems.Qualified? = null,
	selectedStartNanos: Long = wal.observedElapsedNanos,
	selectedEndNanos: Long = wal.observedElapsedNanos,
): CellCapturedRunScope {
	val (scope, manifestRevision, configurationRevision) = resolveCapturedCellWalManifestAddress(wal, historicalMember)
	val logicalTrackingId = scope.logicalTrackingId
	val serviceRunId = scope.serviceRunId
	val (policyRevision, consentEpoch, leaseGeneration) = resolveCapturedCellWalCaptureBinding(wal, historicalMember)
	val physicalFingerprint = requireNotNull(wal.physicalConfigurationFingerprint)
	val authorizationRevision = historicalMember?.authorizationRevision ?: requireNotNull(wal.authorizationRevision)
	val authorizationFingerprint = historicalMember?.authorizationFingerprint ?:
        requireNotNull(wal.authorizationFingerprint)
	val purposeMask = historicalMember?.purposeEligibilityMask ?: wal.authorizationPurposeEligibilityMask
	val observedStart = requireNotNull(wal.observedIntervalStartNanos)
	// Earlier children may be stale. The delivery's latest observed child anchors its
	// capture attribution; retained facts qualify their individual children separately.
	val capturePoint = selectedStartNanos
	requireCapturedCellWalTimes(wal, observedStart)

	authenticateCapturedCellWalDelivery(wal, physicalFingerprint, observedStart)

	authenticateHistoricalCellPlan(
		configurationRevision,
		policyRevision,
		physicalFingerprint,
		limits,
	)

	val policy = authenticateCapturedCellWalPolicy(wal, policyRevision, consentEpoch, capturePoint)

	val registrationInterval = authenticateCapturedCellWalRegistration(
		wal, physicalFingerprint, authorizationRevision, capturePoint,
	)
	val registrationEnd = registrationInterval.last

	val window = readCapturedCellWalAuthorizationWindow(
		wal, limits, authorizationRevision, purposeItems, registrationEnd,
	)
	val authorization = window.authorization
	val authorizationEnd = window.callbackEndNanos
	val captureMember = authorization.authorizedMembers.singleOrNull { member ->
		member.matchesCapturedCellWalMember(logicalTrackingId, serviceRunId, manifestRevision,
			leaseGeneration, policyRevision, consentEpoch)
	}
	val demands = readCapturedCellWalDemands(wal, authorization, authorizationRevision, captureMember)
	requireCapturedCellWalAuthorizationMembers(window, demands, purposeItems)
	requireCapturedCellWalAuthorizationEffect(
		wal, window, purposeItems, authorizationFingerprint, purposeMask, capturePoint, selectedEndNanos,
	)

	val (run, cutoff) = readCapturedCellWalSession(
		wal, scope, leaseGeneration, purposeItems, capturePoint, selectedEndNanos,
	)
	val (manifest, binding, nextManifestStart) = readCapturedCellWalManifest(
		run, scope, manifestRevision, limits,
	)
	val manifestEnd = nextManifestStart
		?: minOf(requireNotNull(cutoff).endForBoot(wal.clockDomainId), registrationEnd, authorizationEnd)
	val segmentId = run.sessionSegmentId
	val segment = segmentId?.let { sessionSegmentDao().getById(it) }
	val wallInterval = capturedCellPointWallInterval(wal, selectedStartNanos, selectedEndNanos)
		?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val exactBinding = requireCapturedCellWalManifestIdentity(
		binding, manifest, scope, consentEpoch, policyRevision, configurationRevision, wal.clockDomainId,
	)
	requireCapturedCellWalManifestEffect(
		exactBinding, manifest, capturePoint, selectedEndNanos, manifestEnd, policy.qosCode,
	)
	requireCapturedCellWalSegmentBounds(segment, scope, wallInterval)
	return scope
}

// Compare only one saved authorized member with the complete immutable capture identity.
@Suppress("ComplexCondition")
private fun SourceAuthorizationEntity.matchesCapturedCellWalMember(
	logicalTrackingId: String, serviceRunId: String, manifestRevision: Long,
	leaseGeneration: Long, policyRevision: Long, consentEpoch: Long,
): Boolean = purpose == CAPTURE_PURPOSE && persistenceEligible &&
	this.logicalTrackingId == logicalTrackingId && this.serviceRunId == serviceRunId &&
	this.manifestRevision == manifestRevision && lifecycleLeaseGeneration == leaseGeneration &&
	sourcePolicyRevision == policyRevision && this.consentEpoch == consentEpoch

private suspend fun AppDatabase.readCapturedCellWalSession(
	wal: SourceEventWalEntity, scope: CellCapturedRunScope, leaseGeneration: Long,
	purposeItems: CellPurposeItems.Qualified?, capturePoint: Long, selectedEndNanos: Long,
): Pair<SourceServiceRunEntity, SavedSessionCutoff> {
	val sessionDao = sourceSessionDao()
	val run = sessionDao.serviceRun(scope.serviceRunId)
	val session = sessionDao.session(scope.logicalTrackingId)
	val cutoff = session?.let { savedSessionCutoff(it) }
	if (run == null || session == null) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	requireCapturedCellWalSessionIdentity(run, session, scope, leaseGeneration)
	requireCapturedCellWalAdmissionTime(wal, run, session, cutoff, purposeItems, capturePoint, selectedEndNanos)
	return run to requireNotNull(cutoff)
}

private fun requireCapturedCellWalSessionIdentity(
	run: SourceServiceRunEntity, session: LogicalTrackingSessionEntity,
	scope: CellCapturedRunScope, leaseGeneration: Long,
) {
	if (run.logicalTrackingId != scope.logicalTrackingId || !session.hasValidCapturedCellHistoricalShape() ||
		!run.hasValidCapturedCellHistoricalShape(session)
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, leaseGeneration) ||
		session.lifecycleLeaseGeneration < leaseGeneration
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

// These are the original admission inputs; keep them explicit instead of introducing another context model.
@Suppress("LongParameterList")
private fun requireCapturedCellWalAdmissionTime(
	wal: SourceEventWalEntity, run: SourceServiceRunEntity, session: LogicalTrackingSessionEntity,
	cutoff: SavedSessionCutoff?, purposeItems: CellPurposeItems.Qualified?, capturePoint: Long, selectedEndNanos: Long,
) {
	if (run.startedElapsedNanos > capturePoint) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (purposeItems == null && session.finalAdmissionOrdinal != null &&
		wal.admissionOrdinal > session.finalAdmissionOrdinal
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if ((session.cutoffAtMs == null) != (session.cutoffElapsedNanos == null) ||
		selectedEndNanos >= requireNotNull(cutoff).endForBoot(wal.clockDomainId)
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.readCapturedCellWalManifest(
	run: SourceServiceRunEntity, scope: CellCapturedRunScope, manifestRevision: Long,
	limits: CellCapturedMaintenanceLimits,
): Triple<SessionManifestVersionEntity, SessionManifestSourceEntity?, Long?> {
	val manifests = sourceSessionDao().manifestsForServiceRun(scope.serviceRunId, limits.maximumManifestsPerRun + 1)
	val sources = trackingHistoryReadDao().manifestSources(
		listOf(scope.serviceRunId),
		limits.maximumSourcesPerRun + 1,
	)
	requireCapturedCellWalManifestTimeline(run, manifests, sources, limits)
	val manifestIndex = manifests.indexOfFirst { manifest ->
		manifest.logicalTrackingId == scope.logicalTrackingId &&
			manifest.manifestRevision == manifestRevision
	}
	if (manifestIndex < 0) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val manifest = manifests[manifestIndex]
	val membership = sources.filter { source ->
		source.logicalTrackingId == scope.logicalTrackingId && source.manifestRevision == manifestRevision
	}
	if (membership.size > limits.maximumSourcesPerManifest) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val binding = membership.singleOrNull { source ->
		source.sourceKind == CELL_SOURCE && source.purpose == CAPTURE_PURPOSE
	}
	return Triple(manifest, binding, manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos)
}

// Capture the complete original manifest identity inputs without an additional carrier object.
@Suppress("LongParameterList")
private fun requireCapturedCellWalManifestIdentity(
	binding: SessionManifestSourceEntity?, manifest: SessionManifestVersionEntity, scope: CellCapturedRunScope,
	consentEpoch: Long, policyRevision: Long, configurationRevision: Long, clockDomainId: String,
): SessionManifestSourceEntity {
	if (binding == null) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!binding.isExactCapturedCellWalBinding(consentEpoch, manifest.sessionMode) ||
		manifest.serviceRunId != scope.serviceRunId || manifest.sourcePolicyRevision != policyRevision ||
		manifest.acquisitionPlanRevision != configurationRevision
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (manifest.effectiveBootId != clockDomainId) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return binding
}

private fun requireCapturedCellWalManifestEffect(
	binding: SessionManifestSourceEntity, manifest: SessionManifestVersionEntity, capturePoint: Long,
	selectedEndNanos: Long, manifestEnd: Long, policyQosCode: Int,
) {
	if (manifest.effectiveElapsedRealtimeNanos > capturePoint || selectedEndNanos >= manifestEnd) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (manifest.zoneId.isBlank() || !hasValidCapturedCellZone(manifest.zoneId) || policyQosCode != binding.qosCode) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun requireCapturedCellWalSegmentBounds(
	segment: SessionSegment?, scope: CellCapturedRunScope, wallInterval: LongRange,
) {
	if (segment == null) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (segment.logicalTrackingId != scope.logicalTrackingId || segment.serviceRunId != scope.serviceRunId ||
		wallInterval.first < segment.startTimeMs || wallInterval.last > segment.endTimeMs
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.resolveCapturedCellWalManifestAddress(
	wal: SourceEventWalEntity, historicalMember: SourceAuthorizationEntity?,
): Triple<CellCapturedRunScope, Long, Long> {
	val logicalTrackingId = requireNotNull(historicalMember?.logicalTrackingId ?: wal.logicalTrackingId)
	val serviceRunId = requireNotNull(historicalMember?.serviceRunId ?: wal.serviceRunId)
	val manifestRevision = requireNotNull(historicalMember?.manifestRevision ?: wal.sessionManifestRevision)
	val configurationRevision = if (historicalMember != null) {
		sourceSessionDao().manifest(logicalTrackingId, manifestRevision)?.acquisitionPlanRevision
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	} else {
		requireNotNull(wal.configRevision)
	}
	return Triple(CellCapturedRunScope(logicalTrackingId, serviceRunId), manifestRevision, configurationRevision)
}

private fun resolveCapturedCellWalCaptureBinding(
	wal: SourceEventWalEntity, historicalMember: SourceAuthorizationEntity?,
): Triple<Long, Long, Long> {
	val policyRevision = requireNotNull(historicalMember?.sourcePolicyRevision ?: wal.sourcePolicyRevision)
	val consentEpoch = requireNotNull(historicalMember?.consentEpoch ?: wal.captureConsentEpoch)
	val leaseGeneration = requireNotNull(historicalMember?.lifecycleLeaseGeneration ?: wal.lifecycleLeaseGeneration)
	return Triple(policyRevision, consentEpoch, leaseGeneration)
}

private fun requireCapturedCellWalTimes(wal: SourceEventWalEntity, observedStart: Long) {
	if (observedStart < 0L || wal.observedElapsedNanos < observedStart ||
		wal.receivedElapsedNanos < wal.observedElapsedNanos
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (wal.wallTimeMs == null || wal.wallTimeMs < 0L ||
		wal.wallTimeUncertaintyMs == null || wal.wallTimeUncertaintyMs < 0L
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun requireCapturedCellWalManifestTimeline(
	run: SourceServiceRunEntity, manifests: List<SessionManifestVersionEntity>,
	sources: List<SessionManifestSourceEntity>, limits: CellCapturedMaintenanceLimits,
) {
	if (manifests.size > limits.maximumManifestsPerRun || sources.size > limits.maximumSourcesPerRun ||
		!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests) ||
		manifests.any { manifest -> !hasAuthenticCapturedCellWalManifestMembers(manifest, sources) }
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

internal suspend fun AppDatabase.authenticateLegacyCapturedCellWalCarrier(
	wal: SourceEventWalEntity,
	limits: CellCapturedMaintenanceLimits,
): CellCapturedWalCarrier? {
	val captureBindingFields = listOf(
		wal.logicalTrackingId,
		wal.serviceRunId,
		wal.sourcePolicyRevision,
		wal.captureConsentEpoch,
		wal.sessionManifestRevision,
		wal.lifecycleLeaseGeneration,
	)
	val captureEligible = wal.authorizationPurposeEligibilityMask and
		SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
	if (!captureEligible && captureBindingFields.all { field -> field == null }) {
		return null
	}
	if (!hasValidLegacyCapturedCellEnvelope(wal, captureBindingFields, captureEligible)) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val scope = authenticateCapturedCellWalScopeForDeletion(wal, limits)
	return CellCapturedWalCarrier(
		wal.eventId,
		wal.admissionOrdinal,
		scope,
		wal.sourceInstanceId,
		wal.registrationGeneration,
		requireNotNull(wal.sourcePolicyRevision),
		requireNotNull(wal.captureConsentEpoch),
		wal.capturedCollectedDataEpoch,
		wal.integrityIdentity,
		capturedCellEarliestCoveredWallTime(
			requireNotNull(wal.wallTimeMs),
			requireNotNull(wal.wallTimeUncertaintyMs),
			wal.observedElapsedNanos,
			requireNotNull(wal.observedIntervalStartNanos),
		),
	)
}


// The list is the original fixed legacy nullable-field group; this predicate reads only one immutable WAL row.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun hasValidLegacyCapturedCellEnvelope(
	wal: SourceEventWalEntity, captureBindingFields: List<Any?>, captureEligible: Boolean,
): Boolean {
	val captureEnvelopeFields = captureBindingFields + listOf(
		wal.configRevision,
		wal.physicalConfigurationFingerprint,
		wal.authorizationRevision,
		wal.authorizationFingerprint,
	)
	return !(!captureEligible || captureEnvelopeFields.any { field -> field == null } ||
		wal.logicalTrackingId.isNullOrBlank() || wal.serviceRunId.isNullOrBlank() ||
		wal.deliveryIdentity?.matches(LOWERCASE_SHA_256) != true ||
		wal.deliveryUnitIndex != 0 || wal.deliveryUnitCount != 1 || wal.sourceSequence < 0L ||
		wal.configRevision!! <= 0L || wal.authorizationRevision!! <= 0L ||
		wal.sourcePolicyRevision!! <= 0L || wal.captureConsentEpoch!! < 0L ||
		wal.sessionManifestRevision!! <= 0L || wal.lifecycleLeaseGeneration!! <= 0L ||
		wal.planAttribution != CAPTURED_REGISTRATION_PLAN_ATTRIBUTION ||
		wal.activityAutomationEpoch != null
	)
}

private const val CAPTURED_REGISTRATION_PLAN_ATTRIBUTION = 0

private val LOWERCASE_SHA_256 = Regex("^[0-9a-f]{64}$")

private fun hasAuthenticCapturedCellWalManifestMembers(
	manifest: SessionManifestVersionEntity, sources: List<SessionManifestSourceEntity>,
): Boolean = SessionManifestIntegrity.verify(
	manifest,
	sources.filter { source ->
		source.logicalTrackingId == manifest.logicalTrackingId &&
			source.manifestRevision == manifest.manifestRevision
	},
)
