package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.dao.SourceRunAdmissionHighWaterRow
import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCaptureAdmissionBarrierEntity
import com.adsamcik.tracker.shared.base.database.data.activityCallbackLossRevisions
import com.adsamcik.tracker.shared.base.database.data.hasActivityCallbackLossFor
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.runtime.ProviderCoverage
import com.adsamcik.tracker.tracker.source.runtime.ProviderFlushOutcome
import com.adsamcik.tracker.tracker.source.runtime.RegistrationRemovalOutcome
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus

/** Activity fences and drains callbacks before retiring a replaced physical registration. */
internal fun hasOrderlyActivityRetirement(
	registration: ProviderRegistrationGenerationEntity,
	barrier: SourceCaptureAdmissionBarrierEntity?,
	highWater: SourceRunAdmissionHighWaterRow,
): Boolean {
	if (barrier == null) {
		return false
	}
	val exactBarrier = registration.captureCallbackBarrierAuthorizationRevision > 0L &&
		barrier.throughAuthorizationRevision == registration.captureCallbackBarrierAuthorizationRevision
	val drainedPrefix = barrier.lastSourceSequence >= highWater.lastSourceSequence &&
		barrier.lastAdmissionOrdinal >= highWater.lastAdmissionOrdinal
	return registration.wasRetiredOrderly() && barrier.matches(registration) && exactBarrier && drainedPrefix
}

/** Reads the original Activity fence and loss disposition before reconstructing its acknowledgement. */
internal suspend fun SourceBrokerDao.orderlyActivityRetirementAcknowledgement(
	provider: SourceProviderKey,
	registration: ProviderRegistrationGenerationEntity,
	highWater: SourceRunAdmissionHighWaterRow,
	cutoff: SessionCutoff,
	serviceRunId: String,
): SourceStopAck? {
	if (registration.sourceKind != SourceKind.ACTIVITY.stableCode) {
		return null
	}
	val barrier = captureAdmissionBarrier(registration.sourceKind, registration.registrationGeneration)
	if (!hasOrderlyActivityRetirement(registration, barrier, highWater)) {
		return null
	}
	val failedCaptureDelivery = hasFailedActivityCaptureDelivery(registration, cutoff.logicalTrackingId, serviceRunId)
	return orderlyActivityRetirementAcknowledgement(provider, highWater, cutoff, serviceRunId, failedCaptureDelivery)
}

private fun ProviderRegistrationGenerationEntity.wasRetiredOrderly(): Boolean =
	sourceKind == SourceKind.ACTIVITY.stableCode && status == ProviderRegistrationGenerationEntity.STATUS_RETIRED &&
		hasOrderlyActivityReason() && retiredElapsedRealtimeNanos != null

private fun ProviderRegistrationGenerationEntity.hasOrderlyActivityReason(): Boolean =
	failureCode == "DEMAND_REMOVED" ||
		(failureCode?.startsWith("DEMAND_REMOVED|") == true &&
			activityCallbackLossRevisions().isNotEmpty() &&
			failureCode == "DEMAND_REMOVED|" + activityCallbackLossRevisions().joinToString("|") {
				"${ProviderRegistrationGenerationEntity.FAILURE_ACTIVITY_CALLBACK_LOSS_PREFIX}$it"
			})

private fun SourceCaptureAdmissionBarrierEntity.matches(registration: ProviderRegistrationGenerationEntity): Boolean =
	sourceKind == registration.sourceKind && sourceInstanceId == registration.sourceInstanceId &&
		registrationGeneration == registration.registrationGeneration

/** Source off/on may share a provider; attribute a retained loss to its original saved session grant. */
internal suspend fun SourceBrokerDao.hasFailedActivityCaptureDelivery(
	registration: ProviderRegistrationGenerationEntity,
	logicalTrackingId: String,
	serviceRunId: String,
): Boolean {
	val revisions = registration.activityCallbackLossRevisions()
	if (revisions.isEmpty()) {
		return false
	}
	return registration.hasActivityCallbackLossFor(logicalTrackingId, serviceRunId,
		revisions.flatMap { authorizationRevision(SourceKind.ACTIVITY.stableCode, registration.registrationGeneration, it) })
}

internal fun orderlyActivityRetirementAcknowledgement(
	provider: SourceProviderKey,
	highWater: SourceRunAdmissionHighWaterRow,
	cutoff: SessionCutoff,
	serviceRunId: String,
	failedCaptureDelivery: Boolean,
): SourceStopAck = SourceStopAck(
	source = SourceKind.ACTIVITY,
	sourceInstanceId = provider.sourceInstanceId,
	registrationGeneration = provider.registrationGeneration,
	appliedRevision = null,
	callbackEntryBarrierSequence = highWater.lastSourceSequence,
	lastDurablyAdmittedSequence = highWater.lastSourceSequence.takeIf { highWater.lastAdmissionOrdinal > 0L },
	lastAdmissionOrdinal = highWater.lastAdmissionOrdinal.takeIf { it > 0L },
	failedAdmissionCount = if (failedCaptureDelivery) {
		1L
	} else {
		0L
	},
	unresolvedSequenceStart = null,
	unresolvedSequenceEndInclusive = null,
	registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED,
	providerFlushOutcome = ProviderFlushOutcome.NOT_SUPPORTED,
	providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
	appDrainComplete = true,
	status = SourceStopStatus.COMPLETE,
	logicalTrackingId = cutoff.logicalTrackingId,
	serviceRunId = serviceRunId,
)

/** Shared Activity may retire a captured generation before a pending runtime action adopts it. */
internal suspend fun AppDatabase.hasOrderlyAdditionalActivityMemberships(
	request: SourceProductDrainRequest, memberships: List<SourceDrainMembership>,
): Boolean {
	if (request.source != SourceKind.ACTIVITY) { return false }
	val claimed = request.retirementClaims.filterNot(SourceDrainRetirementClaim::cleanupOnly)
		.map { it.sourceInstanceId to it.registrationGeneration }.toSet()
	val recorded = memberships.map { it.sourceInstanceId to it.registrationGeneration }.toSet()
	return recorded.containsAll(claimed) && memberships.filter {
		(it.sourceInstanceId to it.registrationGeneration) !in claimed
	}.all { hasOrderlyAdditionalActivityMembership(request, it) }
}

private suspend fun AppDatabase.hasOrderlyAdditionalActivityMembership(
	request: SourceProductDrainRequest, membership: SourceDrainMembership,
): Boolean {
	val broker = sourceBrokerDao()
	val registration = broker.registration(SourceKind.ACTIVITY.stableCode, membership.registrationGeneration)
		?.takeIf { matchesActivityMembershipOwner(request, membership, it) } ?: return false
	val highWater = broker.runCaptureAdmissionHighWater(SourceKind.ACTIVITY.stableCode, membership.sourceInstanceId,
		membership.registrationGeneration, request.logicalTrackingId, request.serviceRunId,
		SourceBrokerPurpose.MASK_SESSION_CAPTURE)
	val cutoff = SessionCutoff(request.logicalTrackingId, request.cutoffElapsedRealtimeNanos,
		request.cutoffWallTimeMs, request.cutoffElapsedRealtimeNanos)
	val ack = broker.orderlyActivityRetirementAcknowledgement(
		SourceProviderKey(SourceInstanceId(membership.sourceInstanceId), membership.registrationGeneration),
		registration, highWater, cutoff, request.serviceRunId) ?: return false
	return ack.matchesActivityMembership(membership) && hasCanonicalActivityCaptureGrant(request, registration)
}

private suspend fun AppDatabase.matchesActivityMembershipOwner(
	request: SourceProductDrainRequest, membership: SourceDrainMembership,
	registration: ProviderRegistrationGenerationEntity,
): Boolean {
	val run = sourceSessionDao().serviceRun(request.serviceRunId) ?: return false
	return run.logicalTrackingId == request.logicalTrackingId && registration.clockDomainId == run.bootId &&
		registration.sourceInstanceId == membership.sourceInstanceId &&
		registration.collectedDataEpoch == sourceEvidenceStateDao().get()?.collectedDataEpoch
}

private fun SourceStopAck.matchesActivityMembership(membership: SourceDrainMembership): Boolean =
	SourceDrainMembership(sourceInstanceId.value, registrationGeneration, lastAdmissionOrdinal,
		lastDurablyAdmittedSequence, appDrainComplete, providerCoverage.name, status.name,
		unresolvedSequenceStart, unresolvedSequenceEndInclusive) == membership

private suspend fun AppDatabase.hasCanonicalActivityCaptureGrant(
	request: SourceProductDrainRequest, registration: ProviderRegistrationGenerationEntity,
): Boolean {
	val broker = sourceBrokerDao()
	val rows = broker.authorizationRevisionBounded(SourceKind.ACTIVITY.stableCode, registration.registrationGeneration,
		registration.captureCallbackBarrierAuthorizationRevision, MAX_RETIREMENT_AUTHORIZATION_MEMBERS + 1)
	if (rows.isEmpty() || rows.size > MAX_RETIREMENT_AUTHORIZATION_MEMBERS ||
		!rows.first().matchesRetiredActivityInterval(registration)) { return false }
	val first = rows.first()
	val ids = rows.mapNotNull(SourceAuthorizationEntity::demandId).distinct()
	if (ids.size != rows.size) { return false }
	val demands = broker.demandsByIds(ids)
	val expected = runCatching { SourceBrokerAuthorization.rows(SourceKind.ACTIVITY.stableCode,
		registration.registrationGeneration, first.authorizationRevision, demands, first.effectiveBootId,
		first.effectiveElapsedRealtimeNanos, first.effectiveWallTimeMs) }.getOrNull() ?: return false
	return expected.sortedBy(SourceAuthorizationEntity::memberId) == rows.sortedBy(SourceAuthorizationEntity::memberId) &&
		rows.any { hasVerifiedActivityCaptureScope(request, it) }
}

private suspend fun AppDatabase.hasVerifiedActivityCaptureScope(
	request: SourceProductDrainRequest, grant: SourceAuthorizationEntity,
): Boolean {
	if (!grant.matchesActivityCaptureScope(request)) { return false }
	val dao = sourceSessionDao()
	val manifest = grant.manifestRevision?.let { dao.manifest(request.logicalTrackingId, it) } ?: return false
	val bindings = dao.manifestSources(request.logicalTrackingId, manifest.manifestRevision)
	val manifestMatches = manifest.serviceRunId == request.serviceRunId &&
		manifest.sourcePolicyRevision == grant.sourcePolicyRevision
	return manifestMatches && SessionManifestIntegrity.verify(manifest, bindings) &&
		bindings.hasActivityCaptureBinding(grant) && hasRetainedActivityGrantLease(request, grant)
}

private fun SourceAuthorizationEntity.matchesActivityCaptureScope(request: SourceProductDrainRequest): Boolean =
	purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible &&
		logicalTrackingId == request.logicalTrackingId && serviceRunId == request.serviceRunId

private fun List<SessionManifestSourceEntity>.hasActivityCaptureBinding(grant: SourceAuthorizationEntity): Boolean =
	singleOrNull { it.sourceKind == SourceKind.ACTIVITY.stableCode && it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }
		?.let { it.persistenceEligible && it.consentEpoch == grant.consentEpoch } == true

private suspend fun AppDatabase.hasRetainedActivityGrantLease(
	request: SourceProductDrainRequest, grant: SourceAuthorizationEntity,
): Boolean {
	val lease = grant.lifecycleLeaseGeneration ?: return false
	val run = sourceSessionDao().serviceRun(request.serviceRunId) ?: return false
	return SessionManifestIntegrity.hasRetainedLeaseGeneration(run, lease)
}

/** A settled provider can still have known original capture loss without an admitted observation. */
internal suspend fun AppDatabase.hasKnownActivityCaptureLoss(request: SourceProductDrainRequest): Boolean =
	request.memberships.any { membership ->
		val broker = sourceBrokerDao()
		val registration = broker.registration(SourceKind.ACTIVITY.stableCode, membership.registrationGeneration)
		registration != null && broker.hasFailedActivityCaptureDelivery(
			registration, request.logicalTrackingId, request.serviceRunId)
	}

private fun SourceAuthorizationEntity.matchesRetiredActivityInterval(
	registration: ProviderRegistrationGenerationEntity,
): Boolean {
	val accepted = registration.acceptedElapsedRealtimeNanos ?: return false
	val retired = registration.retiredElapsedRealtimeNanos ?: return false
	return effectiveBootId == registration.clockDomainId && effectiveElapsedRealtimeNanos in accepted..retired
}
