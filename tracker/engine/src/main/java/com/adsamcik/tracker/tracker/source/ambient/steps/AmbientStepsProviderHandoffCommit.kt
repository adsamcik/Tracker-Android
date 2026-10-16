package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.HandoffPreflight
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.HandoffFactApplication
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.AmbientAuthority
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.Companion.INSERT_IGNORED

internal suspend fun AmbientStepsFactImporter.commitHandoff(
	preflight: HandoffPreflight.Ready,
	command: AmbientStepsProviderHandoffCommand,
	lifecycle: CollectedDataLifecycleSnapshot,
	aggregate: AmbientStepsProviderAggregate?,
): AmbientStepsProviderHandoffResult {
	val current = resolveHandoffPreflight(command, lifecycle)
	if (current !is HandoffPreflight.Ready || current != preflight) {
		return AmbientStepsProviderHandoffResult.Stale(
			AmbientStepsProviderHandoffStaleReason.CURRENT_STATE_CHANGED,
		)
	}
	if (preflight.predecessor.registration.status !=
		ProviderRegistrationGenerationEntity.STATUS_RETIRING
	) {
		return AmbientStepsProviderHandoffResult.Stale(
			AmbientStepsProviderHandoffStaleReason.REGISTRATION_CHANGED,
		)
	}

	val stateDao = database.ambientStepsImportStateDao()
	if (preflight.storedPredecessorCursor == null &&
		stateDao.insertCursor(preflight.predecessorCursor) == INSERT_IGNORED
	) {
		throw ConcurrentAmbientStepsImportException()
	}
	var predecessorCursor = requireNotNull(
		stateDao.cursor(preflight.predecessor.registration.registrationGeneration),
	)
	var logicalFactId: String? = null
	var changed = false
	if (preflight.drainWindow != null && aggregate != null) {
		val application = applyHandoffAggregate(
			authority = preflight.successor,
			cursor = predecessorCursor,
			window = preflight.drainWindow,
			aggregate = aggregate,
			lifecycle = lifecycle,
		)
		predecessorCursor = application.cursor
		logicalFactId = application.logicalFactId
		changed = application.changed
	}

	retireHandoffPredecessor(preflight, command, predecessorCursor)
	initializeHandoffSuccessor(preflight, command, lifecycle)
	return completedHandoffResult(preflight, aggregate, logicalFactId, changed)
}

private suspend fun AmbientStepsFactImporter.retireHandoffPredecessor(
	preflight: HandoffPreflight.Ready,
	command: AmbientStepsProviderHandoffCommand,
	cursor: AmbientStepsImportCursorEntity,
) {
	val stateDao = database.ambientStepsImportStateDao()
	var predecessorCursor = cursor
	val missingStartTimeMs = predecessorCursor.importedThroughTimeMs
	if (missingStartTimeMs < preflight.successorStartTimeMs) {
		predecessorCursor = advanceAcrossGap(
			cursor = predecessorCursor,
			reason = preflight.noReadGapReason
				?: AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED,
			toTimeMs = preflight.successorStartTimeMs,
			nextZoneId = command.zoneId.id,
			boundary = command.importBoundary(preflight.successorStartTimeMs),
		)
	}
	val appliedAtMs = appliedAtMs(
		command.importBoundary(preflight.successorStartTimeMs),
		preflight.successorStartTimeMs,
	)
	if (stateDao.retireExact(
			registrationGeneration = predecessorCursor.registrationGeneration,
			expectedCursorRevision = predecessorCursor.cursorRevision,
			expectedImportedThroughTimeMs = predecessorCursor.importedThroughTimeMs,
			expectedUpdatedAtMs = predecessorCursor.updatedAtMs,
			newCursorRevision = Math.addExact(predecessorCursor.cursorRevision, 1L),
			updatedAtMs = appliedAtMs,
		) != 1
	) {
		throw ConcurrentAmbientStepsImportException()
	}
}

private suspend fun AmbientStepsFactImporter.initializeHandoffSuccessor(
	preflight: HandoffPreflight.Ready,
	command: AmbientStepsProviderHandoffCommand,
	lifecycle: CollectedDataLifecycleSnapshot,
) {
	val stateDao = database.ambientStepsImportStateDao()
	var successorCursor = preflight.successor.initialCursor(command.zoneId.id)
	if (stateDao.insertCursor(successorCursor) == INSERT_IGNORED) {
		throw ConcurrentAmbientStepsImportException()
	}
	if (successorCursor.importedThroughTimeMs < preflight.successorStartTimeMs) {
		val retainedFloor = lifecycle.retainedFromMs?.let(::roundForwardToSecond)
		successorCursor = advanceAcrossGap(
			cursor = successorCursor,
			reason = if (retainedFloor != null &&
				retainedFloor == preflight.successorStartTimeMs
			) {
				AmbientStepsImportGapReason.RETENTION_ADVANCED
			} else {
				AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED
			},
			toTimeMs = preflight.successorStartTimeMs,
			nextZoneId = command.zoneId.id,
			boundary = command.importBoundary(preflight.successorStartTimeMs),
		)
	}
	beginProviderChangedSegment(
		cursor = successorCursor,
		predecessor = preflight.predecessor,
		boundary = command.importBoundary(preflight.successorStartTimeMs),
	)
}

private fun completedHandoffResult(
	preflight: HandoffPreflight.Ready,
	aggregate: AmbientStepsProviderAggregate?,
	logicalFactId: String?,
	changed: Boolean,
): AmbientStepsProviderHandoffResult.Completed {
	val disposition = when {
		preflight.drainWindow == null &&
			preflight.predecessorCursor.importedThroughTimeMs ==
			preflight.successorStartTimeMs ->
			AmbientStepsProviderDrainDisposition.ALREADY_COVERED
		preflight.drainWindow == null || aggregate == null ->
			AmbientStepsProviderDrainDisposition.UNAVAILABLE
		preflight.drainWindow?.endTimeMs != preflight.successorStartTimeMs ->
			AmbientStepsProviderDrainDisposition.PARTIAL
		!changed -> AmbientStepsProviderDrainDisposition.UNCHANGED
		else -> AmbientStepsProviderDrainDisposition.APPLIED
	}
	return AmbientStepsProviderHandoffResult.Completed(
		predecessorRegistrationGeneration =
			preflight.predecessor.registration.registrationGeneration,
		successorRegistrationGeneration =
			preflight.successor.registration.registrationGeneration,
		successorStartTimeMs = preflight.successorStartTimeMs,
		drainDisposition = disposition,
		drainedWindow = preflight.drainWindow?.providerWindow?.takeIf { aggregate != null },
		logicalFactId = logicalFactId,
		replayed = false,
	)
}

internal suspend fun AmbientStepsFactImporter.applyHandoffAggregate(
	authority: AmbientAuthority,
	cursor: AmbientStepsImportCursorEntity,
	window: AmbientStepsStructuralWindow,
	aggregate: AmbientStepsProviderAggregate,
	lifecycle: CollectedDataLifecycleSnapshot,
): HandoffFactApplication {
	val logicalFactId = cursor.importedFactId(window)
	val factDao = database.ambientStepsFactRevisionDao()
	val latest = factDao.latest(
		AmbientStepsFactRevisionEntity.WRITER_ID,
		AmbientStepsFactRevisionEntity.WRITER_VERSION,
		logicalFactId,
	)
	if (latest?.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT) {
		throw RetractedAmbientStepsFactException()
	}
	val unchanged = latest != null && latest.matchesAggregate(
		authority = authority,
		cursor = cursor,
		window = window,
		aggregate = aggregate,
	)
	if (!unchanged) {
		val nextRevision = Math.addExact(latest?.semanticRevision ?: 0L, 1L)
		val fact = ambientFact(
			authority = authority,
			cursor = cursor,
			window = window,
			aggregate = aggregate,
			semanticRevision = nextRevision,
			appliedAtMs = maxOf(clock.currentTimeMillis(), aggregate.observedAtMs),
		)
		persistImportedFact(fact, lifecycle, aggregate.counterDomainToken)
	} else {
		recordCountDomainOrThrow(
			requireNotNull(latest),
			aggregate.counterDomainToken,
		)
	}
	val appliedAtMs = maxOf(clock.currentTimeMillis(), aggregate.observedAtMs)
	advanceReadCursor(cursor, window, aggregate, appliedAtMs)
	return HandoffFactApplication(
		cursor = requireNotNull(
			database.ambientStepsImportStateDao().cursor(cursor.registrationGeneration),
		),
		logicalFactId = logicalFactId,
		changed = !unchanged,
	)
}
