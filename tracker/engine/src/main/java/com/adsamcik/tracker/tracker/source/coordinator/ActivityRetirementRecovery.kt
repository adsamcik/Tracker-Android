package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.dao.SourceRunAdmissionHighWaterRow
import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCaptureAdmissionBarrierEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.activityCallbackLossRevision
import com.adsamcik.tracker.tracker.source.model.SourceKind
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
		(failureCode?.startsWith("DEMAND_REMOVED|") == true && activityCallbackLossRevision() != null)

private fun SourceCaptureAdmissionBarrierEntity.matches(registration: ProviderRegistrationGenerationEntity): Boolean =
	sourceKind == registration.sourceKind && sourceInstanceId == registration.sourceInstanceId &&
		registrationGeneration == registration.registrationGeneration

/** Source off/on may share a provider; attribute a retained loss to its original saved session grant. */
internal suspend fun SourceBrokerDao.hasFailedActivityCaptureDelivery(
	registration: ProviderRegistrationGenerationEntity,
	logicalTrackingId: String,
	serviceRunId: String,
): Boolean {
	val revision = registration.activityCallbackLossRevision() ?: return false
	return authorizationRevision(SourceKind.ACTIVITY.stableCode, registration.registrationGeneration, revision)
		.any { member ->
			member.sourceKind == SourceKind.ACTIVITY.stableCode &&
				member.registrationGeneration == registration.registrationGeneration &&
				member.authorizationRevision == revision && member.effectiveBootId == registration.clockDomainId &&
				member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
				member.logicalTrackingId == logicalTrackingId && member.serviceRunId == serviceRunId
		}
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
