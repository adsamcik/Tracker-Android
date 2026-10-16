package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.HandoffPreflight
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.HistoricalAmbientAuthority
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.AuthorityResolution
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.AmbientAuthority
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.Companion.SOURCE_KIND

internal suspend fun AmbientStepsFactImporter.resolveHandoffPreflight(
	command: AmbientStepsProviderHandoffCommand,
	lifecycle: CollectedDataLifecycleSnapshot,
): HandoffPreflight {
	val boundary = command.importBoundary()
	val successor = when (val resolved = resolveAuthority(boundary, lifecycle)) {
		is AuthorityResolution.Outcome -> {
			val importReason = (resolved.result as? AmbientStepsImportResult.Ineligible)?.reason
			return HandoffPreflight.Outcome(
				AmbientStepsProviderHandoffResult.Ineligible(
					if (importReason ==
						AmbientStepsImportIneligibleReason.DESTINATION_OWNER_INELIGIBLE
					) {
						AmbientStepsProviderHandoffIneligibleReason.DESTINATION_OWNER_INELIGIBLE
					} else {
						AmbientStepsProviderHandoffIneligibleReason.REPLACEMENT_NOT_ACCEPTED
					},
				),
			)
		}
		is AuthorityResolution.Ready -> resolved.authority
	}
	if (successor.registration.registrationGeneration !=
		command.successorRegistrationGeneration
	) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.REGISTRATION_CHANGED)
	}

	val predecessorRegistration = eligibleHandoffPredecessorRegistration(command, lifecycle, successor)
		?: return handoffIneligible(
			AmbientStepsProviderHandoffIneligibleReason.PREDECESSOR_NOT_DRAINABLE,
		)
	return resolvePredecessorHandoffBoundary(command, lifecycle, successor, predecessorRegistration)
}

internal suspend fun AmbientStepsFactImporter.resolveCompletedHandoffReplay(
	command: AmbientStepsProviderHandoffCommand,
	predecessor: HistoricalAmbientAuthority,
	successor: AmbientAuthority,
	predecessorCursor: AmbientStepsImportCursorEntity,
	successorCursor: AmbientStepsImportCursorEntity?,
): HandoffPreflight {
	if (!predecessorCursor.matchesHistoricalAuthority(predecessor, AmbientStepsImportCursorEntity.STATUS_RETIRED)) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.AUTHORIZATION_CHANGED)
	}
	val exactSuccessorCursor = successorCursor
		?: return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
	val invalidSuccessor = completedHandoffSuccessorFailure(command, predecessorCursor, exactSuccessorCursor, successor)
	if (invalidSuccessor != null) {
		return invalidSuccessor
	}
	return completedHandoffMarkerReplay(predecessorCursor, exactSuccessorCursor, successor)
}

private fun completedHandoffSuccessorFailure(
	command: AmbientStepsProviderHandoffCommand,
	predecessorCursor: AmbientStepsImportCursorEntity,
	successorCursor: AmbientStepsImportCursorEntity,
	successor: AmbientAuthority,
): HandoffPreflight? {
	if (!successorCursor.matchesRegistrationAuthority(successor)) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.REGISTRATION_CHANGED)
	}
	if (!successorCursor.matchesCurrentAuthorization(successor)) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.AUTHORIZATION_CHANGED)
	}
	if (predecessorCursor.lastObservedAtMs > command.observedAtMs ||
		successorCursor.lastObservedAtMs > command.observedAtMs
	) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
	}
	return null
}

private suspend fun AmbientStepsFactImporter.completedHandoffMarkerReplay(
	predecessorCursor: AmbientStepsImportCursorEntity,
	successorCursor: AmbientStepsImportCursorEntity,
	successor: AmbientAuthority,
): HandoffPreflight {
	val stateDao = database.ambientStepsImportStateDao()
	if (!hasExactCursorSiblings(predecessorCursor) || !hasExactCursorSiblings(successorCursor)) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
	}
	val providerMarker = stateDao.gaps(successorCursor.registrationGeneration).singleOrNull { gap ->
		gap.reason == AmbientStepsImportGapEntity.REASON_PROVIDER_CHANGED &&
			gap.predecessorRegistrationGeneration == predecessorCursor.registrationGeneration &&
			gap.predecessorProvider == predecessorCursor.provider
	} ?: return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
	if (!providerMarker.matchesHandoffSuccessorIdentity(successorCursor) ||
		!providerMarker.matchesHandoffWindow(predecessorCursor, successorCursor, successor) ||
		!providerMarker.matchesHandoffReceipt(predecessorCursor, successorCursor)
	) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
	}
	return HandoffPreflight.Outcome(AmbientStepsProviderHandoffResult.Completed(
		predecessorRegistrationGeneration = predecessorCursor.registrationGeneration,
		successorRegistrationGeneration = successorCursor.registrationGeneration,
		successorStartTimeMs = providerMarker.gapStartTimeMs,
		drainDisposition = AmbientStepsProviderDrainDisposition.ALREADY_COVERED,
		drainedWindow = null,
		logicalFactId = null,
		replayed = true,
	))
}

private fun AmbientStepsImportGapEntity.matchesHandoffSuccessorIdentity(
	cursor: AmbientStepsImportCursorEntity,
): Boolean = registrationGeneration == cursor.registrationGeneration && provider == cursor.provider &&
	sourceInstanceId == cursor.sourceInstanceId && collectedDataEpoch == cursor.collectedDataEpoch &&
	gapSequence <= cursor.lastGapSequence

private fun AmbientStepsImportGapEntity.matchesHandoffWindow(
	predecessorCursor: AmbientStepsImportCursorEntity,
	successorCursor: AmbientStepsImportCursorEntity,
	successor: AmbientAuthority,
): Boolean = gapStartTimeMs == gapEndTimeMs && gapStartTimeMs == predecessorCursor.importedThroughTimeMs &&
	gapStartTimeMs >= requireNotNull(successor.registration.acceptedAtMs) &&
	gapStartTimeMs <= successorCursor.importedThroughTimeMs

private fun AmbientStepsImportGapEntity.matchesHandoffReceipt(
	predecessorCursor: AmbientStepsImportCursorEntity,
	successorCursor: AmbientStepsImportCursorEntity,
): Boolean = previousClockDomainId == successorCursor.registrationClockDomainId &&
	nextClockDomainId == successorCursor.registrationClockDomainId && previousZoneId == nextZoneId &&
	recordedAtMs == predecessorCursor.updatedAtMs && recordedAtMs <= successorCursor.updatedAtMs

private fun handoffIneligible(
	reason: AmbientStepsProviderHandoffIneligibleReason,
): AmbientStepsFactImporter.HandoffPreflight = AmbientStepsFactImporter.HandoffPreflight.Outcome(
	AmbientStepsProviderHandoffResult.Ineligible(reason),
)

private fun handoffStale(
	reason: AmbientStepsProviderHandoffStaleReason,
): AmbientStepsFactImporter.HandoffPreflight = AmbientStepsFactImporter.HandoffPreflight.Outcome(
	AmbientStepsProviderHandoffResult.Stale(reason),
)

private suspend fun AmbientStepsFactImporter.eligibleHandoffPredecessorRegistration(
	command: AmbientStepsProviderHandoffCommand,
	lifecycle: CollectedDataLifecycleSnapshot,
	successor: AmbientAuthority,
): ProviderRegistrationGenerationEntity? {
	val predecessorRegistration = database.sourceBrokerDao().registration(
		SOURCE_KIND,
		command.predecessorRegistrationGeneration,
	) ?: return null
	val predecessorProvider = predecessorRegistration.ambientStepsProviderOrNull() ?: return null
	return predecessorRegistration.takeIf {
		predecessorProvider != successor.provider &&
			predecessorRegistration.ownerScope == successor.registration.ownerScope &&
			predecessorRegistration.sourceInstanceId == successor.registration.sourceInstanceId &&
			predecessorRegistration.collectedDataEpoch == lifecycle.epoch &&
			predecessorRegistration.clockDomainId == command.observedBootId &&
			predecessorRegistration.providerResidency ==
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE &&
			predecessorRegistration.status in setOf(
				ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			)
	}
}

private suspend fun AmbientStepsFactImporter.resolvePredecessorHandoffBoundary(
	command: AmbientStepsProviderHandoffCommand,
	lifecycle: CollectedDataLifecycleSnapshot,
	successor: AmbientAuthority,
	predecessorRegistration: ProviderRegistrationGenerationEntity,
): HandoffPreflight {
	if (predecessorRegistration.retiredAtMs == null ||
		predecessorRegistration.retiredElapsedRealtimeNanos == null
	) {
		return handoffIneligible(
			AmbientStepsProviderHandoffIneligibleReason.PREDECESSOR_NOT_DRAINABLE,
		)
	}
	val cutoverWallTimeMs = requireNotNull(predecessorRegistration.retiredAtMs)
	val cutoverElapsedRealtimeNanos = requireNotNull(predecessorRegistration.retiredElapsedRealtimeNanos)
	if (successor.registration.acceptedAtMs != cutoverWallTimeMs ||
		successor.registration.acceptedElapsedRealtimeNanos != cutoverElapsedRealtimeNanos ||
		cutoverWallTimeMs > command.observedAtMs ||
		cutoverElapsedRealtimeNanos > command.observedElapsedRealtimeNanos
	) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.REGISTRATION_CHANGED)
	}

	val stateDao = database.ambientStepsImportStateDao()
	val storedPredecessorCursor = stateDao.cursor(command.predecessorRegistrationGeneration)
	val successorStartTimeMs = roundForwardToSecond(
		listOfNotNull(
			cutoverWallTimeMs,
			successor.registration.acceptedAtMs,
			successor.authorizationEffectiveWallTimeMs,
			successor.policy.effectiveWallTimeMs,
			successor.consent.effectiveWallTimeMs,
			lifecycle.retainedFromMs,
		).max(),
	)
	if (successorStartTimeMs > command.observedAtMs) {
		return handoffIneligible(
			AmbientStepsProviderHandoffIneligibleReason.REPLACEMENT_NOT_ACCEPTED,
		)
	}
	val successorCursor = stateDao.cursor(command.successorRegistrationGeneration)

	return resolveAuthenticatedPredecessorHandoff(
		command, successor, predecessorRegistration,
		storedPredecessorCursor, successorCursor, successorStartTimeMs,
	)
}

private suspend fun AmbientStepsFactImporter.resolveAuthenticatedPredecessorHandoff(
	command: AmbientStepsProviderHandoffCommand,
	successor: AmbientAuthority,
	predecessorRegistration: ProviderRegistrationGenerationEntity,
	storedPredecessorCursor: AmbientStepsImportCursorEntity?,
	successorCursor: AmbientStepsImportCursorEntity?,
	successorStartTimeMs: Long,
): HandoffPreflight {
	val lifecycle = successor.lifecycle
	val predecessor = historicalAmbientHandoffAuthority(command, lifecycle, successor, predecessorRegistration)
		?: return handoffIneligible(
			AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
		)
	if (storedPredecessorCursor?.status == AmbientStepsImportCursorEntity.STATUS_RETIRED) {
		return resolveCompletedHandoffReplay(
			command = command,
			predecessor = predecessor,
			successor = successor,
			predecessorCursor = storedPredecessorCursor,
			successorCursor = successorCursor,
		)
	}
	if (predecessorRegistration.status !=
		ProviderRegistrationGenerationEntity.STATUS_RETIRING
	) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.REGISTRATION_CHANGED)
	}
	return resolveActiveHandoffWindow(
		command, predecessor, successor,
		storedPredecessorCursor, successorCursor, successorStartTimeMs,
	)
}

private fun AmbientStepsFactImporter.resolveActiveHandoffWindow(
	command: AmbientStepsProviderHandoffCommand,
	predecessor: HistoricalAmbientAuthority,
	successor: AmbientAuthority,
	storedPredecessorCursor: AmbientStepsImportCursorEntity?,
	successorCursor: AmbientStepsImportCursorEntity?,
	successorStartTimeMs: Long,
): HandoffPreflight {
	val lifecycle = successor.lifecycle
	val cutoverWallTimeMs = requireNotNull(predecessor.registration.retiredAtMs)
	if (successorCursor != null) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
	}
	val predecessorCursor = storedPredecessorCursor ?: predecessor.initialCursor()
	if (!predecessorCursor.matchesHistoricalAuthority(
			predecessor,
			AmbientStepsImportCursorEntity.STATUS_ACTIVE,
		)
	) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.AUTHORIZATION_CHANGED)
	}
	val predecessorCutoffTimeMs = roundBackwardToSecond(cutoverWallTimeMs)
	if (predecessorCursor.importedThroughTimeMs > successorStartTimeMs ||
		predecessorCutoffTimeMs > successorStartTimeMs ||
		predecessorCursor.lastObservedAtMs > command.observedAtMs
	) {
		return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
	}
	return buildHandoffDrainWindow(
		command, lifecycle, predecessor, successor, storedPredecessorCursor,
		predecessorCursor, successorStartTimeMs, predecessorCutoffTimeMs,
	)
}

// Keep the complete validated predecessor/successor window together without a partial handoff model.
@Suppress("LongParameterList")
private fun AmbientStepsFactImporter.buildHandoffDrainWindow(
	command: AmbientStepsProviderHandoffCommand,
	lifecycle: CollectedDataLifecycleSnapshot,
	predecessor: HistoricalAmbientAuthority,
	successor: AmbientAuthority,
	storedPredecessorCursor: AmbientStepsImportCursorEntity?,
	predecessorCursor: AmbientStepsImportCursorEntity,
	successorStartTimeMs: Long,
	predecessorCutoffTimeMs: Long,
): HandoffPreflight {
	val cutoverWallTimeMs = requireNotNull(predecessor.registration.retiredAtMs)
	val cutoverElapsedRealtimeNanos = requireNotNull(predecessor.registration.retiredElapsedRealtimeNanos)
	val retainedFloor = lifecycle.retainedFromMs?.let(::roundForwardToSecond)
	val noReadGapReason = when {
		predecessorCursor.lastObservedZoneId ==
			AmbientStepsImportGapEntity.ZONE_AUTHORITY_UNOBSERVED ->
			AmbientStepsImportGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED
		predecessorCursor.lastObservedZoneId != command.zoneId.id ->
			AmbientStepsImportGapReason.ZONE_CHANGED
		retainedFloor != null && retainedFloor > predecessorCursor.importedThroughTimeMs ->
			AmbientStepsImportGapReason.RETENTION_ADVANCED
		else -> null
	}
	val drainWindow = if (noReadGapReason == null &&
		predecessorCutoffTimeMs > predecessorCursor.importedThroughTimeMs
	) {
		planner.planProgressive(
			segmentStartTimeMs = predecessorCursor.segmentStartTimeMs,
			importedThroughTimeMs = predecessorCursor.importedThroughTimeMs,
			throughTimeMs = predecessorCutoffTimeMs,
			zoneId = command.zoneId,
		).windows.singleOrNull()
	} else {
		null
	}
	return HandoffPreflight.Ready(
		predecessor = predecessor,
		successor = successor,
		storedPredecessorCursor = storedPredecessorCursor,
		predecessorCursor = predecessorCursor,
		drainWindow = drainWindow,
		noReadGapReason = noReadGapReason,
		predecessorCutoffTimeMs = predecessorCutoffTimeMs,
		successorStartTimeMs = successorStartTimeMs,
		cutoverWallTimeMs = cutoverWallTimeMs,
		cutoverElapsedRealtimeNanos = cutoverElapsedRealtimeNanos,
	)
}
