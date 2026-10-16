package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreFailureKind
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession.ActiveSession
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthorityRetirementOutcome
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerAuthorityRetirementRetryReason

/** Persist the replacement and its predecessor debt before retiring any authority. */
internal suspend fun TrackerServiceSourceSession.persistSourceCallerAuthority(
	session: ActiveSession,
	replacementReference: SourceCallerReplayReference,
): ActiveTrackingSessionDescriptor? {
	val supersededReference = session.sourceCallerAuthorityReference
	val stored = when (val result = activeTrackingSessionStore.read()) {
		is ActiveTrackingSessionStoreResult.Failure -> return null
		is ActiveTrackingSessionStoreResult.Success -> result.descriptor
	}
	if (stored == null ||
		!stored.matchesCallerSession(session)
	) {
		return null
	}
	if (stored.sourceCallerAuthorityReference != supersededReference &&
		stored.sourceCallerAuthorityReference != replacementReference
	) {
		return null
	}
	return replaceSourceCallerAuthority(session, stored, supersededReference, replacementReference)
}

private suspend fun TrackerServiceSourceSession.replaceSourceCallerAuthority(
	session: ActiveSession,
	stored: ActiveTrackingSessionDescriptor,
	supersededReference: SourceCallerReplayReference?,
	replacementReference: SourceCallerReplayReference,
): ActiveTrackingSessionDescriptor? {
	val predecessor = stored.pendingRetirementSourceCallerAuthorityReference
		?: supersededReference?.takeIf { it != replacementReference }
	val replacement = stored.copy(
		sourceCallerAuthorityReference = replacementReference,
		pendingRetirementSourceCallerAuthorityReference = predecessor,
	)
	val persisted = if (stored == replacement) {
		stored
	} else {
		when (val result = activeTrackingSessionStore.replaceExact(stored, replacement)) {
			is ActiveTrackingSessionStoreResult.Failure -> return null
			is ActiveTrackingSessionStoreResult.Success -> result.descriptor
		}
	}
	if (persisted != replacement) {
		return null
	}
	session.sourceCallerAuthorityReference = replacementReference
	session.pendingRetirementSourceCallerAuthorityReference = predecessor
	return replacement
}

// Retire the predecessor before settling its exact saved debt.
internal suspend fun TrackerServiceSourceSession.retireRecordedSupersededAuthority(
	session: ActiveSession,
	currentReference: SourceCallerReplayReference,
	supersededReference: SourceCallerReplayReference,
	recordedDescriptor: ActiveTrackingSessionDescriptor? = null,
): SourceCallerAuthorityRetirementOutcome {
	val retirement = coordinator.retireSupersededSourceCallerAuthority(
		session.logicalTrackingId,
		currentReference,
		supersededReference,
		Time.nowMillis,
	)
	if (retirement !in setOf(SourceCallerAuthorityRetirementOutcome.Completed,
		SourceCallerAuthorityRetirementOutcome.RetainedForSteps)) {
		return retirement
	}
	return settleRecordedSourceCallerRetirement(
		session, currentReference, supersededReference, recordedDescriptor, retirement,
	)
}

private suspend fun TrackerServiceSourceSession.settleRecordedSourceCallerRetirement(
	session: ActiveSession,
	currentReference: SourceCallerReplayReference,
	supersededReference: SourceCallerReplayReference,
	recordedDescriptor: ActiveTrackingSessionDescriptor?,
	retirement: SourceCallerAuthorityRetirementOutcome,
): SourceCallerAuthorityRetirementOutcome {
	val stored = recordedDescriptor ?: when (val result = activeTrackingSessionStore.read()) {
		is ActiveTrackingSessionStoreResult.Failure -> return result.kind.toRetirementOutcome()
		is ActiveTrackingSessionStoreResult.Success -> result.descriptor
	}
	return when {
		stored == null -> SourceCallerAuthorityRetirementOutcome.TerminalMissing
		!stored.matchesCallerSession(session) ||
			stored.sourceCallerAuthorityReference != currentReference ->
			SourceCallerAuthorityRetirementOutcome.Retryable(
				SourceCallerAuthorityRetirementRetryReason.CURRENT_AUTHORITY_CHANGED,
			)
		stored.pendingRetirementSourceCallerAuthorityReference == null -> {
			session.pendingRetirementSourceCallerAuthorityReference = null
			retirement
		}
		stored.pendingRetirementSourceCallerAuthorityReference != supersededReference ->
			SourceCallerAuthorityRetirementOutcome.TerminalAmbiguous
		else -> clearRecordedSourceCallerRetirement(session, stored, retirement)
	}
}

private suspend fun TrackerServiceSourceSession.clearRecordedSourceCallerRetirement(
	session: ActiveSession,
	stored: ActiveTrackingSessionDescriptor,
	retirement: SourceCallerAuthorityRetirementOutcome,
): SourceCallerAuthorityRetirementOutcome {
	val cleared = stored.copy(
		pendingRetirementSourceCallerAuthorityReference = null,
	)
	val persisted = when (val result = activeTrackingSessionStore.replaceExact(stored, cleared)) {
		is ActiveTrackingSessionStoreResult.Failure -> return result.kind.toRetirementOutcome()
		is ActiveTrackingSessionStoreResult.Success -> result.descriptor
	}
	if (persisted != cleared) {
		return SourceCallerAuthorityRetirementOutcome.Retryable(
			SourceCallerAuthorityRetirementRetryReason.CURRENT_AUTHORITY_CHANGED,
		)
	}
	session.pendingRetirementSourceCallerAuthorityReference = null
	return retirement
}

private fun ActiveTrackingSessionStoreFailureKind.toRetirementOutcome(): SourceCallerAuthorityRetirementOutcome =
	when (this) {
	ActiveTrackingSessionStoreFailureKind.UNAVAILABLE ->
		SourceCallerAuthorityRetirementOutcome.Retryable(
			SourceCallerAuthorityRetirementRetryReason.STORAGE_UNAVAILABLE,
		)
	ActiveTrackingSessionStoreFailureKind.CORRUPT ->
		SourceCallerAuthorityRetirementOutcome.TerminalCorrupt
}

private fun ActiveTrackingSessionDescriptor.matchesCallerSession(session: ActiveSession): Boolean =
	logicalTrackingId == session.logicalTrackingId && serviceRunId == session.serviceRunId
