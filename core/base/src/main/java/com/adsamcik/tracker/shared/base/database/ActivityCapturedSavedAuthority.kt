package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId

internal suspend fun AppDatabase.authenticateActivityCapturedAuthority(
	revision: ActivityCapturedWindowRevisionEntity,
	limits: ActivityCapturedMaintenanceLimits,
) {
	val run = authenticateActivityCapturedSession(revision)
	val binding = authenticateActivityCapturedManifest(revision, run, limits)
	authenticateActivityCapturedPolicy(revision, binding)
	authenticateActivityCapturedPlan(revision)
	authenticateActivityCapturedRegistration(revision)
	authenticateActivityCapturedAuthorization(revision, limits)
}

private suspend fun AppDatabase.authenticateActivityCapturedSession(
	revision: ActivityCapturedWindowRevisionEntity,
): SourceServiceRunEntity {
	val sessionDao = sourceSessionDao()
	val run = sessionDao.serviceRun(revision.serviceRunId)
	val session = sessionDao.session(revision.logicalTrackingId)
	val segment = sessionSegmentDao().getById(revision.sessionSegmentId)
	val sameClockSessionEnd = session?.let { current ->
		savedSessionCutoff(current).retainedEnd(revision.clockDomainId, revision.sessionRunEffectEndNanos)
	} ?: Long.MAX_VALUE
	if (run == null || session == null || segment == null) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	requireCapturedActivityRunScope(revision, run, segment)
	requireCapturedActivitySessionEffect(revision, run, session, sameClockSessionEnd)
	return run
}

private suspend fun AppDatabase.authenticateActivityCapturedManifest(
	revision: ActivityCapturedWindowRevisionEntity,
	run: SourceServiceRunEntity,
	limits: ActivityCapturedMaintenanceLimits,
): SessionManifestSourceEntity {
	val sessionDao = sourceSessionDao()
	val manifests = sessionDao.manifestsForServiceRun(
		revision.serviceRunId,
		limits.maximumManifestsPerRun + 1,
	)
	if (manifests.size > limits.maximumManifestsPerRun ||
		!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests)
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val sources = trackingHistoryReadDao().manifestSources(
		listOf(revision.serviceRunId),
		limits.maximumSourcesPerRun + 1,
	)
	if (sources.size > limits.maximumSourcesPerRun || manifests.any { manifest ->
		!SessionManifestIntegrity.verify(
			manifest,
			sources.filter { source ->
				source.logicalTrackingId == manifest.logicalTrackingId &&
					source.manifestRevision == manifest.manifestRevision
			},
		)
	}) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val manifestIndex = manifests.indexOfFirst { manifest ->
		manifest.manifestRevision == revision.manifestRevision
	}
	if (manifestIndex < 0) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val manifest = manifests[manifestIndex]
	val membership = sources.filter { source ->
		source.logicalTrackingId == manifest.logicalTrackingId &&
			source.manifestRevision == manifest.manifestRevision
	}
	val binding = membership.singleOrNull { source ->
		source.sourceKind == ACTIVITY_CAPTURED_SOURCE_KIND && source.purpose == ACTIVITY_CAPTURED_CAPTURE_PURPOSE
	}
	val nextManifestStart = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
	val authenticatedBinding = requireCapturedActivityManifestBinding(revision, manifest, binding)
	requireCapturedActivityManifestWindow(revision, manifest, nextManifestStart)
	return authenticatedBinding
}

// Compare the complete saved policy/consent correspondence without fragmenting its short-circuit checks.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
private suspend fun AppDatabase.authenticateActivityCapturedPolicy(
	revision: ActivityCapturedWindowRevisionEntity,
	binding: SessionManifestSourceEntity,
) {
	val policy = sourcePolicyDao().policyAtRevision(revision.sourcePolicyRevision, ACTIVITY_CAPTURED_SOURCE_KIND)
	val consent = sourcePolicyDao().consentEpoch(
		ACTIVITY_CAPTURED_SOURCE_KIND,
		ACTIVITY_CAPTURED_CAPTURE_PURPOSE,
		revision.captureConsentEpoch,
	)
	if (policy == null || consent == null || !policy.enabled ||
		!policy.capturePersistenceEligible || policy.captureConsentEpoch != revision.captureConsentEpoch ||
		policy.qosCode != binding.qosCode || policy.effectiveBootId != revision.clockDomainId ||
		policy.effectiveElapsedRealtimeNanos > revision.windowStartElapsedRealtimeNanos ||
		!consent.eligible || !consent.persistenceEligible ||
		consent.policyRevision > revision.sourcePolicyRevision ||
		consent.effectiveBootId != revision.clockDomainId ||
		consent.effectiveElapsedRealtimeNanos > revision.windowStartElapsedRealtimeNanos
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

// Compare the complete saved plan/payload correspondence without fragmenting its short-circuit checks.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
private suspend fun AppDatabase.authenticateActivityCapturedPlan(
	revision: ActivityCapturedWindowRevisionEntity,
) {
	val acquisition = sourcePlanStateDao().revision(revision.configurationRevision)
	val desiredPlans = activityCapturedFactDao().maintenanceDesiredPlans(
		revision.configurationRevision,
		MAX_SOURCES_PER_PLAN + 1,
	)
	val desired = desiredPlans.singleOrNull { plan -> plan.sourceKind == ACTIVITY_CAPTURED_SOURCE_KIND }
	val appliedPlan = activityCapturedFactDao().registrationPlanBinding(
		revision.sourceInstanceId,
		revision.registrationGeneration,
	)
	if (desiredPlans.size > MAX_SOURCES_PER_PLAN || acquisition == null || desired == null ||
		appliedPlan == null || acquisition.sourcePolicyRevision != revision.sourcePolicyRevision ||
		appliedPlan.configurationRevision != revision.configurationRevision ||
		appliedPlan.desiredPlanPayloadVersion != desired.payloadVersion ||
		!appliedPlan.desiredPlanPayload.contentEquals(desired.payload) ||
		appliedPlan.desiredPlanPayloadChecksum != desired.payloadChecksum ||
		appliedPlan.desiredPlanPayloadChecksum != sha256(appliedPlan.desiredPlanPayload) ||
		appliedPlan.physicalConfigurationFingerprint != revision.physicalConfigurationFingerprint ||
		appliedPlan.appliedAtElapsedRealtimeNanos > revision.windowStartElapsedRealtimeNanos
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

// One indexed saved registration and its fact must have the same identity and acceptance bounds.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
private suspend fun AppDatabase.authenticateActivityCapturedRegistration(
	revision: ActivityCapturedWindowRevisionEntity,
) {
	val registration = sourceBrokerDao().registration(ACTIVITY_CAPTURED_SOURCE_KIND, revision.registrationGeneration)
	val providerEnd = registration?.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
	val providerEndCompatible = hasCompatibleCapturedActivityEnd(
		revision.providerAcceptanceEndNanos, providerEnd, revision.windowEndElapsedRealtimeNanos,
	)
	if (registration == null || registration.sourceInstanceId != revision.sourceInstanceId ||
		registration.clockDomainId != revision.clockDomainId ||
		registration.physicalConfigurationFingerprint != revision.physicalConfigurationFingerprint ||
		registration.collectedDataEpoch != revision.collectedDataEpoch ||
		registration.acceptedElapsedRealtimeNanos != revision.providerAcceptanceStartNanos ||
		!providerEndCompatible
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.authenticateActivityCapturedAuthorization(
	revision: ActivityCapturedWindowRevisionEntity,
	limits: ActivityCapturedMaintenanceLimits,
) {
	val authorizationRows = activityCapturedFactDao().maintenanceAuthorizationMembers(
		ACTIVITY_CAPTURED_SOURCE_KIND,
		revision.registrationGeneration,
		revision.authorizationRevision,
		limits.maximumAuthorizationMembers + 1,
	)
	if (authorizationRows.size > limits.maximumAuthorizationMembers) {
		throw ActivityCapturedMaintenanceLimitExceeded()
	}
	val authorization = authorizationRows.toAuthorizationSnapshotOrNull()
	val authorizationEnd = activityCapturedFactDao().nextAuthorizationBoundary(
		ACTIVITY_CAPTURED_SOURCE_KIND,
		revision.registrationGeneration,
		revision.clockDomainId,
		authorization?.effectiveElapsedRealtimeNanos ?: 0L,
		authorization?.authorizationRevision ?: 0L,
	) ?: Long.MAX_VALUE
	val authorizationEndCompatible = hasCompatibleCapturedActivityEnd(
		revision.authorizationEffectEndNanos, authorizationEnd, revision.windowEndElapsedRealtimeNanos,
	)
	val member = authorization?.authorizedMembers?.singleOrNull { candidate ->
		candidate.matchesCapturedActivityMember(revision)
	}
	if (authorization == null || member == null ||
		!authorization.matchesCapturedActivityAuthorization(revision) || !authorizationEndCompatible
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

internal suspend fun AppDatabase.auditCapturedActivityRegistrationPlans(
	limits: ActivityCapturedMaintenanceLimits,
): List<ActivityCapturedRegistrationPlanEntity> {
	val result = loadAuthenticatedActivityRegistrationPlans(limits)
	if (activityCapturedFactDao().registrationPlanBindingCount() != result.size.toLong()) {
		throw ActivityCapturedSourceDeletionBlockedException(
			ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
	return result
}

private suspend fun AppDatabase.loadAuthenticatedActivityRegistrationPlans(
	limits: ActivityCapturedMaintenanceLimits,
): List<ActivityCapturedRegistrationPlanEntity> {
	val result = mutableListOf<ActivityCapturedRegistrationPlanEntity>()
	var sourceInstanceId: String? = null
	var registrationGeneration: Long? = null
	while (true) {
		val remaining = limits.maximumRegistrationPlans - result.size
		if (remaining <= 0) {
			if (activityCapturedFactDao().maintenanceRegistrationPlanPage(
					sourceInstanceId,
					registrationGeneration,
					1,
				).isNotEmpty()
			) {
				throw ActivityCapturedMaintenanceLimitExceeded()
			}
			break
		}
		val page = activityCapturedFactDao().maintenanceRegistrationPlanPage(
			sourceInstanceId,
			registrationGeneration,
			minOf(remaining, REGISTRATION_PLAN_PAGE_SIZE),
		)
		if (page.isEmpty()) {
			break
		}
		for (plan in page) {
			authenticateCapturedActivityRegistrationPlan(plan)
		}
		result += page
		sourceInstanceId = page.last().sourceInstanceId
		registrationGeneration = page.last().registrationGeneration
		if (page.size < minOf(remaining, REGISTRATION_PLAN_PAGE_SIZE)) {
			break
		}
	}
	return result
}

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
private fun SessionManifestSourceEntity.isCapturedActivityBinding(
	revision: ActivityCapturedWindowRevisionEntity,
	sessionMode: String,
): Boolean = sourceKind == ACTIVITY_CAPTURED_SOURCE_KIND && purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
	persistenceEligible && consentEpoch == revision.captureConsentEpoch &&
	outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY &&
	writerOwner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS &&
	writerOwnerGeneration == revision.writerOwnerGeneration &&
	writerProjectionId == ACTIVITY_CAPTURED_WRITER_ID && writerProjectionVersion == ACTIVITY_CAPTURED_WRITER_VERSION &&
	writerBindingGeneration == revision.writerBindingGeneration &&
	SourceWriterGenerationContract.captureModeMaskForKnownBinding(revision.writerBindingGeneration) != null &&
	writerOwnerGeneration == SourceWriterGenerationContract.canonicalOwnerGeneration(revision.writerBindingGeneration) &&
	(sessionMode == "MANUAL" || sessionMode == "AUTOMATIC" &&
		revision.writerBindingGeneration == SourceWriterGenerationContract.FINAL_CAPTURE_BINDING_GENERATION)

private fun hasValidZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun requireCapturedActivityRunScope(
	revision: ActivityCapturedWindowRevisionEntity, run: SourceServiceRunEntity, segment: SessionSegment,
) {
	if (run.logicalTrackingId != revision.logicalTrackingId ||
		run.sessionSegmentId != revision.sessionSegmentId ||
		!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, revision.lifecycleLeaseGeneration) ||
		run.startedElapsedNanos != revision.sessionRunEffectStartNanos
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (segment.logicalTrackingId != revision.logicalTrackingId || segment.serviceRunId != revision.serviceRunId) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun requireCapturedActivitySessionEffect(
	revision: ActivityCapturedWindowRevisionEntity, run: SourceServiceRunEntity,
	session: LogicalTrackingSessionEntity, sameClockSessionEnd: Long,
) {
	// Compare the saved session head and original retained effect bounds together.
	@Suppress("ComplexCondition")
	if ((session.currentServiceRunId == run.serviceRunId &&
		(run.leaseGeneration != session.lifecycleLeaseGeneration || run.bootId != session.lifecycleBootId)) ||
		session.lifecycleLeaseGeneration < revision.lifecycleLeaseGeneration ||
		(sameClockSessionEnd != revision.sessionRunEffectEndNanos &&
			revision.sessionRunEffectEndNanos != Long.MAX_VALUE) ||
		revision.windowEndElapsedRealtimeNanos > sameClockSessionEnd
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun requireCapturedActivityManifestBinding(
	revision: ActivityCapturedWindowRevisionEntity, manifest: SessionManifestVersionEntity,
	binding: SessionManifestSourceEntity?,
): SessionManifestSourceEntity {
	if (binding == null || !binding.isCapturedActivityBinding(revision, manifest.sessionMode) ||
		manifest.logicalTrackingId != revision.logicalTrackingId || manifest.serviceRunId != revision.serviceRunId
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (manifest.sourcePolicyRevision != revision.sourcePolicyRevision ||
		manifest.acquisitionPlanRevision != revision.configurationRevision ||
		manifest.effectiveBootId != revision.clockDomainId
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return binding
}

private fun requireCapturedActivityManifestWindow(
	revision: ActivityCapturedWindowRevisionEntity, manifest: SessionManifestVersionEntity, nextManifestStart: Long?,
) {
	if (manifest.effectiveElapsedRealtimeNanos > revision.windowStartElapsedRealtimeNanos ||
		nextManifestStart?.let { start -> revision.windowEndElapsedRealtimeNanos > start } == true
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (manifest.zoneId != revision.storedZoneId || !hasValidZone(revision.storedZoneId)) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun hasCompatibleCapturedActivityEnd(originalEnd: Long, retainedEnd: Long, measuredEnd: Long): Boolean =
	originalEnd == retainedEnd || (originalEnd == Long.MAX_VALUE && retainedEnd >= measuredEnd)

private fun SourceAuthorizationEntity.matchesCapturedActivityMember(
	revision: ActivityCapturedWindowRevisionEntity,
): Boolean = purpose == ACTIVITY_CAPTURED_CAPTURE_PURPOSE && persistenceEligible &&
	logicalTrackingId == revision.logicalTrackingId && serviceRunId == revision.serviceRunId &&
	manifestRevision == revision.manifestRevision && lifecycleLeaseGeneration == revision.lifecycleLeaseGeneration &&
	sourcePolicyRevision == revision.sourcePolicyRevision && consentEpoch == revision.captureConsentEpoch

private fun SourceAuthorizationSnapshot.matchesCapturedActivityAuthorization(
	revision: ActivityCapturedWindowRevisionEntity,
): Boolean = authorizationFingerprint == revision.authorizationFingerprint &&
	purposeEligibilityMask == revision.purposeEligibilityMask && effectiveBootId == revision.clockDomainId &&
	effectiveElapsedRealtimeNanos == revision.authorizationEffectStartNanos

private suspend fun AppDatabase.authenticateCapturedActivityRegistrationPlan(
	plan: ActivityCapturedRegistrationPlanEntity,
) {
	val registration = sourceBrokerDao().registration(ACTIVITY_CAPTURED_SOURCE_KIND, plan.registrationGeneration)
	val desiredPlans = activityCapturedFactDao().maintenanceDesiredPlans(
		plan.configurationRevision, MAX_SOURCES_PER_PLAN + 1,
	)
	val desired = desiredPlans.singleOrNull { candidate -> candidate.sourceKind == ACTIVITY_CAPTURED_SOURCE_KIND }
	requireCapturedActivityPlanPayload(plan, desiredPlans, desired)
	if (registration == null || registration.sourceInstanceId != plan.sourceInstanceId ||
		registration.registrationGeneration != plan.registrationGeneration ||
		registration.physicalConfigurationFingerprint != plan.physicalConfigurationFingerprint
	) {
		throw ActivityCapturedSourceDeletionBlockedException(
			ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
}

private fun requireCapturedActivityPlanPayload(
	plan: ActivityCapturedRegistrationPlanEntity, desiredPlans: List<SourceDesiredPlanEntity>,
	desired: SourceDesiredPlanEntity?,
) {
	if (plan.bindingIdentity != plan.calculatedBindingIdentity() || desiredPlans.size > MAX_SOURCES_PER_PLAN ||
		plan.desiredPlanPayloadChecksum != sha256(plan.desiredPlanPayload)
	) {
		throw ActivityCapturedSourceDeletionBlockedException(
			ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
	if (desired == null || desired.payloadVersion != plan.desiredPlanPayloadVersion ||
		!desired.payload.contentEquals(plan.desiredPlanPayload) || desired.payloadChecksum != plan.desiredPlanPayloadChecksum
	) {
		throw ActivityCapturedSourceDeletionBlockedException(
			ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
}

private const val REGISTRATION_PLAN_PAGE_SIZE = 128

private const val MAX_SOURCES_PER_PLAN = 12
