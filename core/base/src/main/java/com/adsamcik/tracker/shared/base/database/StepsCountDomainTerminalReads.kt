package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore.Companion.MAX_COMPLETENESS_TIMELINE
import android.database.sqlite.SQLiteException
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsSessionCompletenessIntegrity

internal suspend fun StepsCountDomainStore.authenticatedStepsCompletenessTimeline(
	logicalTrackingId: String,
	serviceRunId: String,
): List<SourceSessionCompletenessEntity>? {
	val raw = database.sourceSessionDao().rawSourceCompletenessForServiceRun(
		serviceRunId = serviceRunId,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		limit = MAX_COMPLETENESS_TIMELINE + 1,
	)
	if (raw.size > MAX_COMPLETENESS_TIMELINE) {
		return null
	}
	val rows = ArrayList<SourceSessionCompletenessEntity>(raw.size)
	for (candidate in raw) {
		val row = candidate.validatedOrNull() ?: return null
		if (
			row.logicalTrackingId != logicalTrackingId ||
			row.serviceRunId != serviceRunId ||
			row.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS
		) {
			return null
		}
		rows += row
	}
	return rows
}

private fun StepsCountDomainStore.terminalCompletenessAbsence(
	ownerIdentity: String,
	scopeIdentity: String,
): StepsTerminalCompletenessAuthentication {
	val sqlite = database.openHelper.readableDatabase
	return try {
		when (StepsCountDomainSchema.inspect(sqlite)) {
			StepsCountDomainSchemaState.Absent,
			StepsCountDomainSchemaState.FreshRoomScaffold,
			-> StepsTerminalCompletenessAuthentication.Absent
			StepsCountDomainSchemaState.Incompatible ->
				StepsTerminalCompletenessAuthentication.Unverifiable
			StepsCountDomainSchemaState.ValidV2 -> {
				if (sqlite.queryLatestOwner(
						StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
						scopeIdentity,
						ownerIdentity,
					) == null
				) {
					StepsTerminalCompletenessAuthentication.Absent
				} else {
					StepsTerminalCompletenessAuthentication.Unverifiable
				}
			}
		}
	} catch (_: SQLiteException) {
		StepsTerminalCompletenessAuthentication.Unverifiable
	} catch (_: IllegalArgumentException) {
		StepsTerminalCompletenessAuthentication.Unverifiable
	} catch (_: IllegalStateException) {
		StepsTerminalCompletenessAuthentication.Unverifiable
	}
}

private suspend fun StepsCountDomainStore.authenticateTerminalCompletenessRow(
	completeness: SourceSessionCompletenessEntity,
	timeline: List<SourceSessionCompletenessEntity>,
	ownerIdentity: String,
): StepsTerminalCompletenessAuthentication {
	val orderedTimeline =
		timeline.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
	val completenessIndex = orderedTimeline.indexOf(completeness)
	if (completenessIndex < 0 || timeline != orderedTimeline) {
		return StepsTerminalCompletenessAuthentication.Unverifiable
	}
	val timelinePrefix = orderedTimeline.take(completenessIndex + 1)
	val ownerRevision = StepsCountDomainReceiptIntegrity.completenessOwnerRevision(completeness)
	val key = StepsCountDomainOwnerLookupKey(
		StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
		ownerIdentity,
		ownerRevision,
	)
	val stored = selectedTerminalCompletenessOwner(completeness, key, timelinePrefix)
		?: return StepsTerminalCompletenessAuthentication.Unverifiable
	val marker = requireNotNull(stored.completenessMarker)
	val retirementEvidence = StepsCountDomainRetirementEvidence(
		providerFlushOutcome = marker.providerFlushOutcome,
		registrationRemovalOutcome = marker.registrationRemovalOutcome,
	)
	if (!retirementEvidence.hasFinalRegistrationRemoval()) {
		return StepsTerminalCompletenessAuthentication.Unverifiable
	}
	val receipt = stored.receipt
	val authentic = when (stored.owner.operation) {
		StepsCountDomainOwnerRevisionEntity.OPERATION_BIND ->
			stored.authenticatesCompleteRegistration(completeness, retirementEvidence)
		StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN -> stored.hasUnprovenCompleteness()
		else -> false
	}
	return if (authentic) {
		StepsTerminalCompletenessAuthentication.Authenticated(
			completeness,
			retirementEvidence,
			receipt?.collectedDataEpoch,
		)
	} else {
		StepsTerminalCompletenessAuthentication.Unverifiable
	}
}

private fun terminalRowProductDisposition(
	row: SourceSessionCompletenessEntity,
	key: StepsCountDomainOwnerLookupKey,
	ready: StepsCountDomainOwnerRead.Ready,
	timelinePrefix: List<SourceSessionCompletenessEntity>,
): StepsTerminalProductAuthentication {
	val stored = ready.owners[key] ?: return StepsTerminalProductAuthentication.Unverifiable
	val marker = stored.completenessMarker ?: return StepsTerminalProductAuthentication.Unverifiable
	val retirementEvidence = StepsCountDomainRetirementEvidence(
		marker.providerFlushOutcome, marker.registrationRemovalOutcome,
	)
	if (ready.latestRevisions[StepsCountDomainOwnerLineageKey(key.ownerKind, key.ownerIdentity)] != key.ownerRevision ||
		!stored.matchesTerminalCompleteness(row, timelinePrefix) || !retirementEvidence.hasFinalRegistrationRemoval()
	) {
		return StepsTerminalProductAuthentication.Unverifiable
	}
	return when (stored.owner.operation) {
		StepsCountDomainOwnerRevisionEntity.OPERATION_BIND -> completeRowProductDisposition(stored, row, retirementEvidence)
		StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN ->
			unprovenRowProductDisposition(stored, row, retirementEvidence)
		else -> StepsTerminalProductAuthentication.Unverifiable
	}
}
private fun hasValidTerminalSessionIdentity(
	logicalId: String, runId: String, instanceId: String, generation: Long,
): Boolean =
	logicalId.isNotBlank() && runId.isNotBlank() && instanceId.isNotBlank() && generation > 0L

private suspend fun StepsCountDomainStore.registeredCompletenessTimeline(
	logicalId: String, runId: String,
): List<SourceSessionCompletenessEntity>? {
	val timeline = authenticatedStepsCompletenessTimeline(logicalId, runId) ?: return null
	return timeline.takeIf { StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(it, logicalId, runId) }
}

private fun hasValidTerminalProductTimeline(
	logicalId: String, runId: String, timeline: List<SourceSessionCompletenessEntity>,
): Boolean =
	logicalId.isNotBlank() && runId.isNotBlank() && timeline.isNotEmpty() && timeline.size <= MAX_COMPLETENESS_TIMELINE &&
		StepsSessionCompletenessIntegrity.hasValidTimeline(timeline, logicalId, runId)

private fun StepsCountDomainStoredOwner.matchesTerminalCompleteness(
	row: SourceSessionCompletenessEntity,
	timelinePrefix: List<SourceSessionCompletenessEntity>,
): Boolean {
	val marker = completenessMarker ?: return false
	return marker.matches(owner) && owner.matchesCompletenessIdentity(row) &&
		marker.matchesCompletenessTimeline(row, timelinePrefix)
}

private fun StepsCountDomainOwnerRevisionEntity.matchesCompletenessIdentity(
	row: SourceSessionCompletenessEntity,
): Boolean =
	scopeIdentity == StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(row.logicalTrackingId, row.serviceRunId) &&
		ownerEffectChecksum == StepsCountDomainReceiptIntegrity.completenessEffectChecksum(row) &&
		linkedAtMs == row.updatedAtMs

private fun StepsCountDomainCompletenessMarkerEntity.matchesCompletenessTimeline(
	row: SourceSessionCompletenessEntity,
	timelinePrefix: List<SourceSessionCompletenessEntity>,
): Boolean = lastAdmissionOrdinal == row.lastAdmissionOrdinal && lastSourceSequence == row.lastSourceSequence &&
	registrationTimelineChecksum == StepsCountDomainReceiptIntegrity.registrationTimelineChecksum(timelinePrefix)

private fun StepsCountDomainStoredOwner.hasUnprovenCompleteness(): Boolean =
	receipt == null && owner.receiptIdentity == null &&
		completenessMarker?.terminalState == StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN

private fun StepsCountDomainStoredOwner.authenticatesCompleteRegistration(
	row: SourceSessionCompletenessEntity,
	evidence: StepsCountDomainRetirementEvidence,
): Boolean = isAuthentic() && receipt?.let {
	it.registrationGeneration == row.registrationGeneration &&
		it.coverageKind == StepsCountDomainReceiptEntity.COVERAGE_COMPLETE_RUN
} == true && row.hasExactCompleteRetirement(evidence)

private suspend fun StepsCountDomainStore.selectedTerminalCompletenessOwner(
	row: SourceSessionCompletenessEntity,
	key: StepsCountDomainOwnerLookupKey,
	timelinePrefix: List<SourceSessionCompletenessEntity>,
): StepsCountDomainStoredOwner? {
	val ready = readOwners(listOf(key), limit = 1) as? StepsCountDomainOwnerRead.Ready ?: return null
	val stored = ready.owners[key] ?: return null
	val latest = ready.latestRevisions[StepsCountDomainOwnerLineageKey(key.ownerKind, key.ownerIdentity)]
	return stored.takeIf { latest == key.ownerRevision && it.matchesTerminalCompleteness(row, timelinePrefix) }
}

private fun terminalTimelineProductDisposition(
	orderedTimeline: List<SourceSessionCompletenessEntity>,
	keys: List<StepsCountDomainOwnerLookupKey>,
	ready: StepsCountDomainOwnerRead.Ready,
): StepsTerminalProductAuthentication {
	var unavailable = false
	for ((index, pair) in orderedTimeline.zip(keys).withIndex()) {
		val (row, key) = pair
		when (terminalRowProductDisposition(row, key, ready, orderedTimeline.take(index + 1))) {
			StepsTerminalProductAuthentication.Materializable -> Unit
			StepsTerminalProductAuthentication.TerminalUnavailable -> unavailable = true
			else -> return StepsTerminalProductAuthentication.Unverifiable
		}
	}
	return if (unavailable) {
		StepsTerminalProductAuthentication.TerminalUnavailable
	} else {
		StepsTerminalProductAuthentication.Materializable
	}
}

private fun completeRowProductDisposition(
	stored: StepsCountDomainStoredOwner,
	row: SourceSessionCompletenessEntity,
	evidence: StepsCountDomainRetirementEvidence,
): StepsTerminalProductAuthentication = if (stored.isAuthentic() && row.hasExactCompleteRetirement(evidence)) {
	StepsTerminalProductAuthentication.Materializable
} else {
	StepsTerminalProductAuthentication.Unverifiable
}

private fun unprovenRowProductDisposition(
	stored: StepsCountDomainStoredOwner,
	row: SourceSessionCompletenessEntity,
	evidence: StepsCountDomainRetirementEvidence,
): StepsTerminalProductAuthentication = if (stored.hasUnprovenCompleteness() && row.hasTerminalRetirement(evidence)) {
	StepsTerminalProductAuthentication.TerminalUnavailable
} else {
	StepsTerminalProductAuthentication.Unverifiable
}

@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
internal suspend fun StepsCountDomainStore.authenticateTerminalSessionCompletenessInCountDomain(
	logicalTrackingId: String,
	serviceRunId: String,
	sourceInstanceId: String,
	registrationGeneration: Long,
): StepsTerminalCompletenessAuthentication {
	if (!hasValidTerminalSessionIdentity(logicalTrackingId, serviceRunId, sourceInstanceId, registrationGeneration)) {
		return StepsTerminalCompletenessAuthentication.Unverifiable
	}
	val timeline = registeredCompletenessTimeline(logicalTrackingId, serviceRunId)
		?: return StepsTerminalCompletenessAuthentication.Unverifiable
	val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
		logicalTrackingId,
		serviceRunId,
		sourceInstanceId,
		registrationGeneration,
	)
	val completeness = timeline.singleOrNull { row ->
		row.sourceInstanceId == sourceInstanceId &&
			row.registrationGeneration == registrationGeneration
	} ?: return terminalCompletenessAbsence(
		ownerIdentity,
		StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
			logicalTrackingId,
			serviceRunId,
		),
	)
	return authenticateTerminalCompletenessRow(completeness, timeline, ownerIdentity)
}

@Suppress("ComplexCondition", "LongMethod", "ReturnCount")
internal suspend fun StepsCountDomainStore.authenticateTerminalProductDispositionInCountDomain(
	logicalTrackingId: String,
	serviceRunId: String,
	timeline: List<SourceSessionCompletenessEntity>,
): StepsTerminalProductAuthentication {
	if (!hasValidTerminalProductTimeline(logicalTrackingId, serviceRunId, timeline)) {
		return StepsTerminalProductAuthentication.Unverifiable
	}
	val orderedTimeline =
		timeline.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
	if (timeline != orderedTimeline) {
		return StepsTerminalProductAuthentication.Unverifiable
	}
	val keys = orderedTimeline.map { row ->
		StepsCountDomainOwnerLookupKey(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
			StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
				row.logicalTrackingId,
				row.serviceRunId,
				row.sourceInstanceId,
				row.registrationGeneration,
			),
			StepsCountDomainReceiptIntegrity.completenessOwnerRevision(row),
		)
	}
	val ready = when (val read = readOwners(keys, MAX_COMPLETENESS_TIMELINE)) {
		is StepsCountDomainOwnerRead.Ready -> read
		StepsCountDomainOwnerRead.SchemaUnavailable ->
			return StepsTerminalProductAuthentication.SchemaUnavailable
		StepsCountDomainOwnerRead.Overflow,
		StepsCountDomainOwnerRead.Unverifiable,
		-> return StepsTerminalProductAuthentication.Unverifiable
	}
	return terminalTimelineProductDisposition(orderedTimeline, keys, ready)
}
