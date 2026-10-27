package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession.ActiveSession
import com.adsamcik.tracker.tracker.source.model.SourceKind

internal const val SOURCE_STOP_PROJECTION_LEASE_BUSY = "PROJECTION_LEASE_BUSY"

/** A busy projector is a normal wait only after every physical source has settled. */
internal fun CoordinatorDrainResult.projectionWaitReason(retirementIncomplete: Boolean): String? =
	if (!retirementIncomplete && (this == CoordinatorDrainResult.LeaseUnavailable ||
		this is CoordinatorDrainResult.LeaseLost)) {
		SOURCE_STOP_PROJECTION_LEASE_BUSY
	} else {
		null
	}

internal fun TrackerServiceSourceSession.startupRejectedReconfigure(): SourceSessionReconfigureOutcome {
	settingsStatusProvider.publishFailure(SOURCE_SESSION_STARTUP_RECOVERY_NOT_READY)
	return SourceSessionReconfigureOutcome.Rejected(
		SessionReconfigureResult.InvalidState(SOURCE_SESSION_STARTUP_RECOVERY_NOT_READY),
	)
}

internal fun TrackerServiceSourceSession.finishInactiveStop(): SourceSessionStopOutcome {
	preStartInputs = null
	settingsStatusProvider.publishInactive()
	return SourceSessionStopOutcome.NotActive
}

internal suspend fun TrackerServiceSourceSession.finishUnstartedStop(): SourceSessionStopOutcome {
	active?.let { session ->
		if (!clearCatalogReconfigurationDebt(session)) {
			return SourceSessionStopOutcome.Retryable(
				SourceSessionStopRetryCode.STORAGE_UNAVAILABLE,
			)
		}
	}
	active = null
	settingsStatusProvider.publishInactive()
	return SourceSessionStopOutcome.Stopped
}

internal suspend fun TrackerServiceSourceSession.suspendForRestart(
	session: ActiveSession,
	reason: String,
): SourceSessionStopOutcome {
	session.pendingSourceCallerAuthorityReference?.let { pending ->
		if (!propagateSourceCallerAuthority(
				session,
				pending,
				markCleanupOnFailure = false,
			)
		) {
			return SourceSessionStopOutcome.Retryable(
				SourceSessionStopRetryCode.STORAGE_UNAVAILABLE,
			)
		}
		session.pendingSourceCallerAuthorityReference = null
		if (session.coordinatorSuspended) {
			active = null
			return SourceSessionStopOutcome.Stopped
		}
	}
	val cutoff = currentStopCutoff(session.desiredInputs.clockDomainId)
	val result = coordinator.suspendForRestart(
		SessionSuspendRequest(
			ownerToken = session.ownerToken,
			reason = reason,
			wallTimeMs = cutoff.wallTimeMs,
			elapsedRealtimeNanos = cutoff.elapsedRealtimeNanos,
			clockDomainId = cutoff.clockDomainId,
		),
	)
	val authorityReference = result.sourceCallerAuthorityReferenceOrNull()
	if (authorityReference != null) {
		session.pendingSourceCallerAuthorityReference = authorityReference
		session.coordinatorSuspended = result is SessionSuspendResult.Suspended
		if (!propagateSourceCallerAuthority(
				session,
				authorityReference,
				markCleanupOnFailure = false,
			)
		) {
			return SourceSessionStopOutcome.Retryable(
				SourceSessionStopRetryCode.STORAGE_UNAVAILABLE,
			)
		}
		session.pendingSourceCallerAuthorityReference = null
	}
	val outcome = result.toSourceSessionStopOutcome()
	if (outcome == SourceSessionStopOutcome.Stopped) {
		active = null
	}
	return outcome
}

internal suspend fun TrackerServiceSourceSession.stopCompletely(
	session: ActiveSession,
	reason: String,
	factualCutoff: SourceSessionStopCutoff?,
): SourceSessionStopOutcome {
	val cutoff = selectStopCutoff(session.desiredInputs.clockDomainId, factualCutoff)
	val outcome = coordinator.stop(
		SessionStopRequest(
			ownerToken = session.ownerToken,
			reason = reason,
			wallTimeMs = cutoff.wallTimeMs,
			elapsedRealtimeNanos = cutoff.elapsedRealtimeNanos,
			clockDomainId = cutoff.clockDomainId,
		),
	).toSourceSessionStopOutcome()
	if (outcome == SourceSessionStopOutcome.Stopped) {
		if (!clearCatalogReconfigurationDebt(session)) {
			return SourceSessionStopOutcome.Retryable(
				SourceSessionStopRetryCode.STORAGE_UNAVAILABLE,
			)
		}
		active = null
	}
	return outcome
}

private fun currentStopCutoff(clockDomainId: String) = SourceSessionStopCutoff(
	wallTimeMs = Time.nowMillis,
	elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
	clockDomainId = clockDomainId,
)

private fun selectStopCutoff(
	clockDomainId: String,
	factualCutoff: SourceSessionStopCutoff?,
): SourceSessionStopCutoff = factualCutoff
	?.takeIf { it.clockDomainId == clockDomainId }
	?: currentStopCutoff(clockDomainId)

private fun SessionSuspendResult.toSourceSessionStopOutcome(): SourceSessionStopOutcome = when (this) {
	is SessionSuspendResult.Suspended,
	SessionSuspendResult.NoActiveSession,
	-> SourceSessionStopOutcome.Stopped
	is SessionSuspendResult.DrainPending -> SourceSessionStopOutcome.Retryable(
		projectionDrainRetryCode(reason, source, sourceResults),
	)
	is SessionSuspendResult.CleanupPending -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.CLEANUP_PENDING,
	)
	is SessionSuspendResult.Retryable -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.STORAGE_UNAVAILABLE,
	)
	is SessionSuspendResult.InvalidIntent -> error(
		"Event-source suspension intent rejected: ${code}",
	)
	SessionSuspendResult.Busy -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.COORDINATOR_BUSY,
	)
}

private fun SessionStopResult.toSourceSessionStopOutcome(): SourceSessionStopOutcome = when (this) {
	is SessionStopResult.Stopped,
	SessionStopResult.NoActiveSession,
	-> SourceSessionStopOutcome.Stopped
	is SessionStopResult.DrainPending -> SourceSessionStopOutcome.Retryable(
		projectionDrainRetryCode(reason, source, sourceResults),
	)
	is SessionStopResult.CleanupPending -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.CLEANUP_PENDING,
	)
	is SessionStopResult.InvalidIntent -> error(
		"Event-source shutdown intent rejected: ${code}",
	)
	SessionStopResult.Busy -> SourceSessionStopOutcome.Retryable(
		SourceSessionStopRetryCode.COORDINATOR_BUSY,
	)
}

private fun projectionDrainRetryCode(
	reason: String?, source: SourceKind?, sourceResults: List<SourceProductDrainResult>,
): SourceSessionStopRetryCode =
	if (reason == SOURCE_STOP_PROJECTION_LEASE_BUSY && source == null && sourceResults.isEmpty()) {
		SourceSessionStopRetryCode.PROJECTION_BUSY
	} else {
		SourceSessionStopRetryCode.DRAIN_PENDING
	}

private fun SessionSuspendResult.sourceCallerAuthorityReferenceOrNull():
	SourceCallerReplayReference? = when (this) {
	is SessionSuspendResult.Suspended -> sourceCallerAuthorityReference
	is SessionSuspendResult.CleanupPending -> sourceCallerAuthorityReference
	is SessionSuspendResult.DrainPending -> sourceCallerAuthorityReference
	is SessionSuspendResult.InvalidIntent,
	is SessionSuspendResult.Retryable,
	SessionSuspendResult.NoActiveSession,
	SessionSuspendResult.Busy,
	-> null
}
