package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainWriteResult
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.Preflight
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.AuthorityResolution
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.AmbientAuthority
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.Companion.INSERT_IGNORED
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.Companion.EMPTY_EFFECT_CHECKSUM

internal suspend fun AmbientStepsFactImporter.commitRead(
	preflight: Preflight.Ready,
	boundary: AmbientStepsImportBoundary,
	lifecycle: CollectedDataLifecycleSnapshot,
	aggregate: AmbientStepsProviderAggregate?,
): AmbientStepsImportResult {
	val current = when (val resolved = validatedReadCommitAuthority(preflight, boundary, lifecycle)) {
		is AuthorityResolution.Outcome -> return resolved.result
		is AuthorityResolution.Ready -> resolved.authority
	}
	val currentCursor = database.ambientStepsImportStateDao().cursor(
		current.registration.registrationGeneration,
	)
	if (currentCursor != preflight.storedCursor) {
		return stale(AmbientStepsImportStaleReason.CURSOR_CHANGED)
	}
	if (aggregate == null) {
		return missingAggregateResult(preflight, boundary)
	}
	return applyReadAggregate(preflight, current, lifecycle, aggregate)
}

private suspend fun AmbientStepsFactImporter.validatedReadCommitAuthority(
	preflight: Preflight.Ready,
	boundary: AmbientStepsImportBoundary,
	lifecycle: CollectedDataLifecycleSnapshot,
): AuthorityResolution {
	if (!collectionWindowAllowed(preflight.window.startTimeMs)) {
		return AuthorityResolution.Outcome(ineligible(AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE))
	}
	val current = when (val resolved = resolveAuthority(boundary, lifecycle)) {
		is AuthorityResolution.Outcome -> return AuthorityResolution.Outcome(stale(resolved.result.toStaleReason()))
		is AuthorityResolution.Ready -> resolved.authority
	}
	return if (current != preflight.authority) {
		AuthorityResolution.Outcome(stale(authorityDifference(preflight.authority, current)))
	} else {
		AuthorityResolution.Ready(current)
	}
}

internal suspend fun AmbientStepsFactImporter.missingAggregateResult(
	preflight: Preflight.Ready,
	boundary: AmbientStepsImportBoundary,
): AmbientStepsImportResult {
	if (preflight.window.endTimeMs == preflight.window.day.endTimeMs) {
		advanceAcrossGap(
			cursor = preflight.storedCursor,
			reason = AmbientStepsImportGapReason.PROVIDER_NO_EVIDENCE,
			toTimeMs = preflight.window.endTimeMs,
			nextZoneId = preflight.window.day.zoneId,
			boundary = boundary,
		)
		return AmbientStepsImportResult.Gap(
			reason = AmbientStepsImportGapReason.PROVIDER_NO_EVIDENCE,
			fromTimeMs = preflight.storedCursor.importedThroughTimeMs,
			toTimeMs = preflight.window.endTimeMs,
		)
	}
	return AmbientStepsImportResult.NoEvidence(
		AmbientStepsImportNoEvidenceReason.PROVIDER_RETURNED_NO_EVIDENCE,
	)
}

internal suspend fun AmbientStepsFactImporter.applyReadAggregate(
	preflight: Preflight.Ready,
	current: AmbientAuthority,
	lifecycle: CollectedDataLifecycleSnapshot,
	aggregate: AmbientStepsProviderAggregate,
): AmbientStepsImportResult {
	val appliedAtMs = maxOf(clock.currentTimeMillis(), aggregate.observedAtMs)
	val cursor = preflight.storedCursor

	val logicalFactId = cursor.importedFactId(preflight.window)
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
		authority = current,
		cursor = cursor,
		window = preflight.window,
		aggregate = aggregate,
	)
	if (unchanged && preflight.window.endTimeMs == cursor.importedThroughTimeMs &&
		aggregate.observedAtMs == cursor.lastObservedAtMs
	) {
		recordCountDomainOrThrow(
			requireNotNull(latest),
			aggregate.counterDomainToken,
		)
		return AmbientStepsImportResult.Unchanged(
			window = preflight.window.providerWindow,
			cursorRevision = cursor.cursorRevision,
		)
	}
	val semanticRevision = if (unchanged) {
		requireNotNull(latest).semanticRevision
	} else {
		appendReadAggregate(preflight, current, lifecycle, aggregate, latest, appliedAtMs)
	}
	if (unchanged) {
		recordCountDomainOrThrow(
			requireNotNull(latest),
			aggregate.counterDomainToken,
		)
	}

	val nextCursorRevision = advanceReadCursor(cursor, preflight.window, aggregate, appliedAtMs)
	return if (unchanged) {
		AmbientStepsImportResult.Unchanged(preflight.window.providerWindow, nextCursorRevision)
	} else {
		AmbientStepsImportResult.Applied(
			window = preflight.window.providerWindow,
			logicalFactId = logicalFactId,
			semanticRevision = semanticRevision,
			cursorRevision = nextCursorRevision,
		)
	}
}

internal suspend fun AmbientStepsFactImporter.appendReadAggregate(
	preflight: Preflight.Ready,
	current: AmbientAuthority,
	lifecycle: CollectedDataLifecycleSnapshot,
	aggregate: AmbientStepsProviderAggregate,
	latest: AmbientStepsFactRevisionEntity?,
	appliedAtMs: Long,
): Long {
	val cursor = preflight.storedCursor
	val nextRevision = Math.addExact(latest?.semanticRevision ?: 0L, 1L)
	val fact = ambientFact(
		authority = current,
		cursor = cursor,
		window = preflight.window,
		aggregate = aggregate,
		semanticRevision = nextRevision,
		appliedAtMs = appliedAtMs,
	)
	persistImportedFact(fact, lifecycle, aggregate.counterDomainToken)
	return nextRevision
}

/** Appends one provider fact and its count-domain evidence in the caller’s Room transaction. */
internal suspend fun AmbientStepsFactImporter.persistImportedFact(
	fact: AmbientStepsFactRevisionEntity,
	lifecycle: CollectedDataLifecycleSnapshot,
	counterDomainToken: StepsCounterDomainToken?,
) {
	val lifecycleChanged = synchronizeImportedFactLifecycle(fact, lifecycle)
	val evidenceDao = database.sourceEvidenceStateDao()
	if (database.ambientStepsFactRevisionDao().insert(fact) == INSERT_IGNORED) {
		throw ConcurrentAmbientStepsImportException()
	}
	recordCountDomainOrThrow(fact, counterDomainToken)
	if (!lifecycleChanged && evidenceDao.incrementRevision(fact.appliedAtMs) != 1) {
		throw ConcurrentAmbientStepsImportException()
	}
}

private suspend fun AmbientStepsFactImporter.synchronizeImportedFactLifecycle(
	fact: AmbientStepsFactRevisionEntity,
	lifecycle: CollectedDataLifecycleSnapshot,
): Boolean {
	val evidenceDao = database.sourceEvidenceStateDao()
	val lifecycleChanged = evidenceDao.synchronizeLifecycle(
		epoch = lifecycle.epoch,
		retainedFromMs = lifecycle.retainedFromMs,
		updatedAtMs = fact.appliedAtMs,
	)
	val roomLifecycle = requireNotNull(evidenceDao.get())
	if (roomLifecycle.collectedDataEpoch != lifecycle.epoch ||
		roomLifecycle.retainedFromMs != lifecycle.retainedFromMs
	) {
		throw ConcurrentAmbientStepsImportException()
	}
	return lifecycleChanged
}

internal suspend fun AmbientStepsFactImporter.advanceReadCursor(
	cursor: AmbientStepsImportCursorEntity,
	window: AmbientStepsStructuralWindow,
	aggregate: AmbientStepsProviderAggregate,
	appliedAtMs: Long,
): Long {
	val nextCursorRevision = Math.addExact(cursor.cursorRevision, 1L)
	if (database.ambientStepsImportStateDao().advanceExact(
			registrationGeneration = cursor.registrationGeneration,
			expectedContinuitySegmentGeneration = cursor.continuitySegmentGeneration,
			expectedLastGapSequence = cursor.lastGapSequence,
			expectedCursorRevision = cursor.cursorRevision,
			expectedImportedThroughTimeMs = cursor.importedThroughTimeMs,
			expectedObservedAtMs = cursor.lastObservedAtMs,
			expectedUpdatedAtMs = cursor.updatedAtMs,
			expectedBootId = cursor.lastObservedBootId,
			expectedZoneId = cursor.lastObservedZoneId,
			newImportedThroughTimeMs = window.endTimeMs,
			newObservedAtMs = aggregate.observedAtMs,
			newCursorRevision = nextCursorRevision,
			updatedAtMs = appliedAtMs,
		) != 1
	) {
		throw ConcurrentAmbientStepsImportException()
	}
	return nextCursorRevision
}

internal fun AmbientStepsFactImporter.ambientFact(
	authority: AmbientAuthority,
	cursor: AmbientStepsImportCursorEntity,
	window: AmbientStepsStructuralWindow,
	aggregate: AmbientStepsProviderAggregate,
	semanticRevision: Long,
	appliedAtMs: Long,
): AmbientStepsFactRevisionEntity {
	val logicalFactId = cursor.importedFactId(window)
	val unsealed = AmbientStepsFactRevisionEntity(
		logicalFactId = logicalFactId,
		semanticRevision = semanticRevision,
		mutationId = AmbientStepsFactIntegrity.mutationId(
			logicalFactId,
			semanticRevision,
			AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
		),
		writerId = AmbientStepsFactRevisionEntity.WRITER_ID,
		writerVersion = AmbientStepsFactRevisionEntity.WRITER_VERSION,
		writerOwnerGeneration = authority.ownerGeneration,
		operation = AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
		originKind = AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE,
		provider = cursor.provider,
		registrationGeneration = cursor.registrationGeneration,
		continuitySegmentGeneration = cursor.continuitySegmentGeneration,
		sourceInstanceId = cursor.sourceInstanceId,
		authorizationRevision = cursor.authorizationRevision,
		authorizationFingerprint = cursor.authorizationFingerprint,
		windowStartTimeMs = window.startTimeMs,
		windowEndTimeMs = window.endTimeMs,
		observedAtMs = aggregate.observedAtMs,
		structuralEpochDay = window.day.epochDay,
		storedZoneId = window.day.zoneId,
		structuralDayStartTimeMs = window.day.startTimeMs,
		structuralDayEndTimeMs = window.day.endTimeMs,
		stepCount = aggregate.stepCount,
		purpose = AmbientStepsFactRevisionEntity.PURPOSE_AMBIENT_PRODUCT,
		sourcePolicyRevision = cursor.sourcePolicyRevision,
		ambientConsentEpoch = cursor.ambientConsentEpoch,
		collectedDataEpoch = cursor.collectedDataEpoch,
		scopeDeletionGeneration = 0L,
		effectChecksum = EMPTY_EFFECT_CHECKSUM,
		appliedAtMs = appliedAtMs,
		retentionScope = authority.retention.scope,
		retentionPolicyId = authority.retention.opaquePolicyId,
		retentionApprovalRevision = authority.retention.approvalRevision,
	)
	return unsealed.copy(effectChecksum = AmbientStepsFactIntegrity.effectChecksum(unsealed))
}

internal suspend fun AmbientStepsFactImporter.recordCountDomainOrThrow(
	fact: AmbientStepsFactRevisionEntity,
	counterDomainToken: StepsCounterDomainToken?,
) {
	when (StepsCountDomainStore(database).recordAmbientFact(fact, counterDomainToken)) {
		StepsCountDomainWriteResult.INSERTED,
		StepsCountDomainWriteResult.EXACT_REPLAY,
		StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE,
		StepsCountDomainWriteResult.UNPROVEN,
		-> Unit
		StepsCountDomainWriteResult.NOT_APPLICABLE,
		StepsCountDomainWriteResult.AUTHORITY_PENDING,
		StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE,
		StepsCountDomainWriteResult.IDENTITY_CONFLICT,
		StepsCountDomainWriteResult.REVISION_GAP,
		StepsCountDomainWriteResult.TERMINAL_OWNER,
		-> throw ConcurrentAmbientStepsImportException()
	}
}

internal fun AmbientStepsFactImporter.appliedAtMs(boundary: AmbientStepsImportBoundary, effectiveTimeMs: Long): Long =
	maxOf(clock.currentTimeMillis(), boundary.observedAtMs, effectiveTimeMs)

internal fun AmbientStepsImportCursorEntity.importedFactId(
	window: AmbientStepsStructuralWindow,
): String = AmbientStepsFactIntegrity.logicalFactId(
	provider = provider,
	registrationGeneration = registrationGeneration,
	continuitySegmentGeneration = continuitySegmentGeneration,
	sourceInstanceId = sourceInstanceId,
	windowStartTimeMs = window.startTimeMs,
	structuralEpochDay = window.day.epochDay,
	storedZoneId = window.day.zoneId,
	collectedDataEpoch = collectedDataEpoch,
)

// Compares one saved fact with the supplied aggregate/window tuple and deterministic digest.
@Suppress("CyclomaticComplexMethod")
internal fun AmbientStepsFactRevisionEntity.matchesAggregate(
	authority: AmbientStepsFactImporter.AmbientAuthority,
	cursor: AmbientStepsImportCursorEntity,
	window: AmbientStepsStructuralWindow,
	aggregate: AmbientStepsProviderAggregate,
): Boolean = AmbientStepsFactIntegrity.hasValidEffectChecksum(this) &&
	operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT &&
	writerOwnerGeneration == authority.ownerGeneration &&
	provider == cursor.provider && registrationGeneration == cursor.registrationGeneration &&
	continuitySegmentGeneration == cursor.continuitySegmentGeneration &&
	sourceInstanceId == cursor.sourceInstanceId &&
	authorizationRevision == cursor.authorizationRevision &&
	authorizationFingerprint == cursor.authorizationFingerprint &&
	windowStartTimeMs == window.startTimeMs && windowEndTimeMs == window.endTimeMs &&
	structuralEpochDay == window.day.epochDay && storedZoneId == window.day.zoneId &&
	structuralDayStartTimeMs == window.day.startTimeMs &&
	structuralDayEndTimeMs == window.day.endTimeMs &&
	stepCount == aggregate.stepCount &&
	sourcePolicyRevision == cursor.sourcePolicyRevision &&
	ambientConsentEpoch == cursor.ambientConsentEpoch &&
	retentionScope == cursor.retentionScope &&
	retentionPolicyId == cursor.retentionPolicyId &&
	retentionApprovalRevision == cursor.retentionApprovalRevision &&
	collectedDataEpoch == cursor.collectedDataEpoch && scopeDeletionGeneration == 0L

// Classifies the first differing field group in two supplied immutable authority snapshots.
@Suppress("CyclomaticComplexMethod")
private fun authorityDifference(
	before: AmbientStepsFactImporter.AmbientAuthority,
	after: AmbientStepsFactImporter.AmbientAuthority,
): AmbientStepsImportStaleReason = when {
	before.registration != after.registration || before.state != after.state ->
		AmbientStepsImportStaleReason.REGISTRATION_CHANGED
	before.authorization != after.authorization || before.policy != after.policy ||
		before.consent != after.consent || before.retention != after.retention ||
		before.demands != after.demands ->
		AmbientStepsImportStaleReason.AUTHORIZATION_CHANGED
	before.ownerGeneration != after.ownerGeneration ->
		AmbientStepsImportStaleReason.DESTINATION_OWNER_CHANGED
	before.lifecycle != after.lifecycle -> AmbientStepsImportStaleReason.LIFECYCLE_CHANGED
	else -> AmbientStepsImportStaleReason.CURRENT_STATE_CHANGED
}

private fun AmbientStepsImportResult.toStaleReason(): AmbientStepsImportStaleReason = when (this) {
	is AmbientStepsImportResult.Ineligible -> when (reason) {
		AmbientStepsImportIneligibleReason.NO_ACTIVE_REGISTRATION,
		AmbientStepsImportIneligibleReason.REGISTRATION_NOT_ACCEPTED,
		-> AmbientStepsImportStaleReason.REGISTRATION_CHANGED
		AmbientStepsImportIneligibleReason.DESTINATION_OWNER_INELIGIBLE ->
			AmbientStepsImportStaleReason.DESTINATION_OWNER_CHANGED
		AmbientStepsImportIneligibleReason.POLICY_INACTIVE,
		AmbientStepsImportIneligibleReason.AMBIENT_POLICY_INELIGIBLE,
		AmbientStepsImportIneligibleReason.AMBIENT_CONSENT_INELIGIBLE,
		AmbientStepsImportIneligibleReason.RETENTION_AUTHORITY_UNAVAILABLE,
		AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE,
		AmbientStepsImportIneligibleReason.PROVIDER_READER_UNAVAILABLE,
		-> AmbientStepsImportStaleReason.AUTHORIZATION_CHANGED
	}
	is AmbientStepsImportResult.Stale -> reason
	else -> AmbientStepsImportStaleReason.CURRENT_STATE_CHANGED
}
