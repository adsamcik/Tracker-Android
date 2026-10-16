package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference

internal fun currentRecoverySourceCallerAuthorityCandidate(
	logicalTrackingId: String,
	serviceRunId: String,
	session: LogicalTrackingSessionEntity?,
	run: SourceServiceRunEntity?,
	manifest: SessionManifestVersionEntity?,
	bindings: List<SessionManifestSourceEntity>,
	intent: SessionLifecycleIntentVersionEntity?,
): CurrentRecoverySourceCallerAuthority? {
	if (session == null || run == null || manifest == null || intent == null) {
		return null
	}
	return verifiedRecoverySourceCallerAuthorityCandidate(
		logicalTrackingId, serviceRunId, session, run, manifest, bindings, intent,
	)
}

private fun verifiedRecoverySourceCallerAuthorityCandidate(
	logicalTrackingId: String,
	serviceRunId: String,
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	bindings: List<SessionManifestSourceEntity>,
	intent: SessionLifecycleIntentVersionEntity,
): CurrentRecoverySourceCallerAuthority? {
	val activeOrReconfiguringRun = session.hasActiveRecoveryRun(run, serviceRunId)
	val completedSuspension = session.hasCompletedRecoverySuspension(run)
	val hasCurrentAuthority = session.matchesRecoveryAuthority(logicalTrackingId, manifest, intent) &&
		(activeOrReconfiguringRun || completedSuspension) &&
		run.matchesRecoveryAuthority(logicalTrackingId, serviceRunId, session) &&
		manifest.matchesRecoveryAuthority(logicalTrackingId, serviceRunId, session, bindings) &&
		intent.matchesRecoveryAuthority(logicalTrackingId, session, manifest) &&
		intent.hasRecoveryBoundaryAndChecksum(run, manifest, activeOrReconfiguringRun, completedSuspension)
	if (!hasCurrentAuthority) {
		return null
	}
	return CurrentRecoverySourceCallerAuthority(
		manifestIdentity = SourceCallerManifestIdentity(logicalTrackingId, manifest.manifestRevision),
		reference = SourceCallerReplayReference(
			intent.sourceCallerAuthorityReference ?: return null,
		),
	)
}

private fun LogicalTrackingSessionEntity.hasActiveRecoveryRun(
	run: SourceServiceRunEntity,
	serviceRunId: String,
): Boolean = currentServiceRunId == serviceRunId && state in setOf(
	SessionLifecycleState.ACTIVE.name,
	SessionLifecycleState.RECONFIGURING.name,
) && run.state in setOf(
	SessionLifecycleState.ACTIVE.name,
	SessionLifecycleState.RECONFIGURING.name,
) && run.completedAtMs == null && run.runtimeAcknowledgement == LifecycleActionStatus.START_ACCEPTED.name

private fun LogicalTrackingSessionEntity.hasCompletedRecoverySuspension(run: SourceServiceRunEntity): Boolean =
	currentServiceRunId == null && state == SessionLifecycleState.ACTIVE.name &&
		cutoffAtMs == null && cutoffElapsedNanos == null && finalAdmissionOrdinal == null &&
		run.state == SessionLifecycleState.FINALIZED.name && run.completedAtMs != null &&
		run.runtimeAcknowledgement == LifecycleActionStatus.STOP_ACCEPTED.name

private fun LogicalTrackingSessionEntity.matchesRecoveryAuthority(
	logicalTrackingId: String,
	manifest: SessionManifestVersionEntity,
	intent: SessionLifecycleIntentVersionEntity,
): Boolean = this.logicalTrackingId == logicalTrackingId && sessionMode == SessionMode.MANUAL.name &&
	state in setOf(SessionLifecycleState.ACTIVE.name, SessionLifecycleState.RECONFIGURING.name) &&
	completedAtMs == null && currentManifestRevision == manifest.manifestRevision &&
	currentIntentRevision == intent.intentRevision && lifecycleLeaseGeneration > 0L &&
	!lifecycleBootId.isNullOrBlank()

private fun SourceServiceRunEntity.matchesRecoveryAuthority(
	logicalTrackingId: String,
	serviceRunId: String,
	session: LogicalTrackingSessionEntity,
): Boolean = this.logicalTrackingId == logicalTrackingId && this.serviceRunId == serviceRunId &&
	startIsUserInitiated && bootId == session.lifecycleBootId && leaseGeneration == session.lifecycleLeaseGeneration &&
	rolloutRevision == session.rolloutRevision && desiredPlanRevision == session.desiredPlanRevision &&
	androidDeliveryState == AndroidStartDeliveryState.FOREGROUND_ACCEPTED.name

private fun SessionManifestVersionEntity.matchesRecoveryAuthority(
	logicalTrackingId: String,
	serviceRunId: String,
	session: LogicalTrackingSessionEntity,
	bindings: List<SessionManifestSourceEntity>,
): Boolean = this.logicalTrackingId == logicalTrackingId && this.serviceRunId == serviceRunId &&
	manifestRevision == session.currentManifestRevision && sessionMode == SessionMode.MANUAL.name &&
	acquisitionPlanRevision == session.desiredPlanRevision && rolloutRevision == session.rolloutRevision &&
	effectiveBootId == session.lifecycleBootId && SessionManifestIntegrity.verify(this, bindings)

private fun SessionLifecycleIntentVersionEntity.matchesRecoveryAuthority(
	logicalTrackingId: String,
	session: LogicalTrackingSessionEntity,
	manifest: SessionManifestVersionEntity,
): Boolean = this.logicalTrackingId == logicalTrackingId && intentRevision == session.currentIntentRevision &&
	manifestRevision == manifest.manifestRevision && desiredState == LifecycleDesiredState.ACTIVE.name &&
	requestBootId == session.lifecycleBootId && requestedElapsedRealtimeNanos >= 0L && requestedWallTimeMs >= 0L

private fun SessionLifecycleIntentVersionEntity.hasRecoveryBoundaryAndChecksum(
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	activeOrReconfiguringRun: Boolean,
	completedSuspension: Boolean,
): Boolean = (stopReason != null || startOrigin == manifest.startOrigin) &&
	(!completedSuspension || !stopReason.isNullOrBlank()) &&
	(!completedSuspension || run.completionReason == stopReason) &&
	(!activeOrReconfiguringRun || stopReason == null) && hasAuthenticRecoveryCallerIntent()

private fun SessionLifecycleIntentVersionEntity.hasAuthenticRecoveryCallerIntent(): Boolean {
	val reference = sourceCallerAuthorityReference?.takeIf(String::isNotBlank) ?: return false
	if (!hasNoAutomaticRecoveryTrigger()) {
		return false
	}
	val hasNoStopDeadline = stopDeadlineBootId == null && stopDeadlineElapsedRealtimeNanos == null
	val ordinaryActiveChecksum = hasOrdinaryRecoveryChecksum(reference, hasNoStopDeadline)
	val suspendedRecoveryChecksum = hasSuspendedRecoveryChecksum(reference, hasNoStopDeadline)
	return ordinaryActiveChecksum || suspendedRecoveryChecksum
}

private fun SessionLifecycleIntentVersionEntity.hasNoAutomaticRecoveryTrigger(): Boolean =
	automationEpoch == null && triggerId == null && triggerKind == null && triggerBootId == null &&
		triggerObservedElapsedRealtimeNanos == null && triggerReceivedElapsedRealtimeNanos == null &&
		triggerExpiresElapsedRealtimeNanos == null && triggerCollectedDataEpoch == null

private fun SessionLifecycleIntentVersionEntity.hasOrdinaryRecoveryChecksum(
	reference: String,
	hasNoStopDeadline: Boolean,
): Boolean = startOrigin in setOf(
	SessionStartOrigin.MANUAL_FOREGROUND_START.name,
	SessionStartOrigin.RECOVERY.name,
	SessionStartOrigin.POLICY_RECONCILIATION.name,
) && stopReason == null && hasNoStopDeadline && intentChecksum == stableLifecycleChecksum(
	logicalTrackingId,
	intentRevision,
	manifestRevision,
	desiredState,
	startOrigin,
	requestBootId,
	requestedElapsedRealtimeNanos,
	requestedWallTimeMs,
	triggerId,
	automationEpoch,
	triggerCollectedDataEpoch,
	reference,
)

private fun SessionLifecycleIntentVersionEntity.hasSuspendedRecoveryChecksum(
	reference: String,
	hasNoStopDeadline: Boolean,
): Boolean = startOrigin == SessionStartOrigin.RECOVERY.name && !stopReason.isNullOrBlank() &&
	hasNoStopDeadline && triggerId == null && triggerKind == null && triggerBootId == null &&
	triggerObservedElapsedRealtimeNanos == null && triggerReceivedElapsedRealtimeNanos == null &&
	triggerExpiresElapsedRealtimeNanos == null && intentChecksum == stableLifecycleChecksum(
		logicalTrackingId,
		intentRevision,
		manifestRevision,
		desiredState,
		stopReason,
		requestBootId,
		requestedElapsedRealtimeNanos,
		reference,
	)
