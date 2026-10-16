package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.tracker.api.ProviderStepsSessionContinuation

/** Pure correspondence shared by preparation, delivery and foreground acknowledgement. */
internal fun LogicalTrackingSessionEntity.hasPreparedRunBinding(run: SourceServiceRunEntity): Boolean =
    currentServiceRunId == run.serviceRunId && currentManifestRevision == run.preparedManifestRevision &&
        currentIntentRevision == run.preparedIntentRevision && state == SessionLifecycleState.STARTING.name

internal fun LogicalTrackingSessionEntity.hasPreparedLease(generation: Long, bootId: String): Boolean =
    lifecycleLeaseGeneration == generation && lifecycleBootId == bootId

internal fun SessionManifestVersionEntity.hasPreparedRunPlan(run: SourceServiceRunEntity): Boolean =
    acquisitionPlanRevision == run.desiredPlanRevision && serviceRunId == run.serviceRunId

internal fun SessionLifecycleIntentVersionEntity.hasPreparedRunIntent(run: SourceServiceRunEntity): Boolean =
    manifestRevision == run.preparedManifestRevision && desiredState == LifecycleDesiredState.ACTIVE.name &&
        !sourceCallerAuthorityReference.isNullOrBlank()

internal fun SessionStartRequest.hasCurrentProviderStepsContinuation(
    session: LogicalTrackingSessionEntity?, delivery: AndroidStartDeliveryMetadata,
): Boolean {
    val continuation = providerStepsContinuation ?: return false
    if (session == null) {
        return false
    }
    return session.hasProviderContinuationSession(continuation) && hasProviderStartIdentity(session) &&
        session.hasProviderContinuationAuthority(continuation, delivery) && clockDomainId == continuation.bootId
}

private fun LogicalTrackingSessionEntity.hasProviderContinuationSession(
    continuation: ProviderStepsSessionContinuation,
): Boolean = logicalTrackingId == continuation.logicalTrackingId &&
    lifecycleRevision == continuation.lifecycleRevision &&
    state == SessionLifecycleState.ACTIVE.name && isIdleProviderStepsSession()

private fun LogicalTrackingSessionEntity.isIdleProviderStepsSession(): Boolean =
    currentServiceRunId == null && currentManifestRevision == null && currentIntentRevision == null &&
        sessionMode == LogicalTrackingSessionEntity.MODE_PROVIDER_STEPS

private fun LogicalTrackingSessionEntity.hasProviderContinuationAuthority(
    continuation: ProviderStepsSessionContinuation, delivery: AndroidStartDeliveryMetadata,
): Boolean = lifecycleLeaseGeneration == continuation.commandGeneration &&
    delivery.commandGeneration == continuation.commandGeneration && clockDomainId == continuation.bootId

private fun SessionStartRequest.hasProviderStartIdentity(session: LogicalTrackingSessionEntity): Boolean =
    logicalTrackingId == session.logicalTrackingId && origin == SessionStartOrigin.MANUAL_FOREGROUND_START &&
        automaticTrigger == null && continuationAuthority == null

internal fun LifecycleDesiredActionEntity.hasPreparedActionBinding(run: SourceServiceRunEntity): Boolean =
    logicalTrackingId == run.logicalTrackingId && serviceRunId == run.serviceRunId &&
        manifestRevision == run.preparedManifestRevision

internal fun LifecycleDesiredActionEntity.hasPreparedActionLease(run: SourceServiceRunEntity): Boolean =
    leaseGeneration == run.leaseGeneration && bootId == run.bootId

internal fun List<SourceDemandEntity>.hasPreparedCallerReference(reference: String): Boolean =
    isNotEmpty() && all { it.sourceCallerAuthorityReference == reference &&
        it.status in setOf(SourceDemandEntity.STATUS_ACTIVE, SourceDemandEntity.STATUS_BLOCKED,
            SourceDemandEntity.STATUS_RETIRED) }

internal fun List<SourceCallerAcceptedAuthorityEntity>.hasPreparedDemandKeys(
    demands: List<SourceDemandEntity>,
): Boolean {
    val demandKeys = demands.mapTo(linkedSetOf()) { demand ->
        demand.sourceKind to when (demand.purpose) {
            SourceBrokerPurpose.SESSION_CAPTURE -> TrackingPurpose.SESSION_CAPTURE.stableName
            SourceBrokerPurpose.CONTROL_CONTINUATION -> TrackingPurpose.CONTROL.stableName
            else -> return false
        }
    }
    return mapTo(linkedSetOf()) { it.sourceKind to it.purpose } == demandKeys
}

internal fun hasPreparedCaptureAuthorities(
    bindings: List<SessionManifestSourceEntity>, rows: List<SourceCallerAcceptedAuthorityEntity>,
    actions: List<LifecycleDesiredActionEntity>, run: SourceServiceRunEntity, lease: LifecycleLeaseToken,
): Boolean {
    val captureBindings = bindings.filter { it.purpose == SessionManifestPurpose.SESSION_CAPTURE.name }
    val captureRows = rows.filter { it.purpose == TrackingPurpose.SESSION_CAPTURE.stableName }
    val captureSourceKinds = captureBindings.mapTo(linkedSetOf()) { it.sourceKind }
    return actions.mapNotNullTo(linkedSetOf(), LifecycleDesiredActionEntity::sourceKind) == captureSourceKinds &&
        captureRows.mapTo(linkedSetOf()) { it.sourceKind } == captureSourceKinds &&
        captureRows.all { it.matchesPreparedCaptureAuthority(run, lease) }
}

private fun SourceCallerAcceptedAuthorityEntity.matchesPreparedCaptureAuthority(
    run: SourceServiceRunEntity, lease: LifecycleLeaseToken,
): Boolean = logicalTrackingId == run.logicalTrackingId && manifestRevision == run.preparedManifestRevision &&
    executionRevision == run.leaseGeneration && ownerCasToken == lease.ownerToken
