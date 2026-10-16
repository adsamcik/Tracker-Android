package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapIntegrity
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.HistoricalAmbientAuthority
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.CursorResolution
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.AmbientAuthority
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsFactImporter.Companion.INSERT_IGNORED

internal suspend fun AmbientStepsFactImporter.prepareCursor(
	authority: AmbientAuthority,
	cursor: AmbientStepsImportCursorEntity?,
	boundary: AmbientStepsImportBoundary,
): CursorResolution {
	if (cursor == null) {
		return initializeCursor(authority, boundary)
	}
	if (!cursor.matchesRegistrationAuthority(authority)) {
		return CursorResolution.Outcome(
			stale(AmbientStepsImportStaleReason.REGISTRATION_CHANGED),
		)
	}
	if (boundary.observedAtMs < cursor.lastObservedAtMs) {
		return CursorResolution.Outcome(
			stale(AmbientStepsImportStaleReason.CURSOR_CHANGED),
		)
	}
	return if (cursor.matchesCurrentAuthorization(authority)) {
		CursorResolution.Ready(cursor)
	} else {
		advanceCursorAuthority(authority, cursor, boundary)
	}
}

internal suspend fun AmbientStepsFactImporter.initializeCursor(
	authority: AmbientAuthority,
	boundary: AmbientStepsImportBoundary,
): CursorResolution {
	val privacyFloor = authority.privacyFloorTimeMs
	if (privacyFloor > boundary.throughTimeMs) {
		return CursorResolution.Outcome(
			AmbientStepsImportResult.NoEvidence(
				AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
			),
		)
	}
	val initialCursor = authority.initialCursor(
		zoneId = boundary.zoneId.id.takeIf { privacyFloor == boundary.throughTimeMs },
	)
	if (database.ambientStepsImportStateDao().insertCursor(initialCursor) == INSERT_IGNORED) {
		throw ConcurrentAmbientStepsImportException()
	}
	if (privacyFloor < boundary.throughTimeMs) {
		advanceAcrossGap(
			cursor = initialCursor,
			reason = AmbientStepsImportGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED,
			toTimeMs = boundary.throughTimeMs,
			nextZoneId = boundary.zoneId.id,
			boundary = boundary,
		)
		return CursorResolution.Outcome(
			AmbientStepsImportResult.Gap(
				reason = AmbientStepsImportGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED,
				fromTimeMs = privacyFloor,
				toTimeMs = boundary.throughTimeMs,
			),
		)
	}
	return CursorResolution.Outcome(
		AmbientStepsImportResult.NoEvidence(
			AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
		),
	)
}

internal suspend fun AmbientStepsFactImporter.advanceCursorAuthority(
	authority: AmbientAuthority,
	cursor: AmbientStepsImportCursorEntity,
	boundary: AmbientStepsImportBoundary,
): CursorResolution {
	if (authority.authorization.authorizationRevision <= cursor.authorizationRevision) {
		return CursorResolution.Outcome(
			stale(AmbientStepsImportStaleReason.AUTHORIZATION_CHANGED),
		)
	}
	val boundaryTimeMs = authority.privacyFloorTimeMs
	if (cursor.importedThroughTimeMs < boundaryTimeMs) {
		val advanced = advanceAcrossGap(
			cursor = cursor,
			reason = AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED,
			toTimeMs = boundaryTimeMs,
			nextZoneId = cursor.lastObservedZoneId,
			boundary = boundary,
		)
		val transition = authority.transitionFrom(advanced, boundary.observedAtMs)
		rotateAuthority(
			cursor = advanced,
			authority = authority,
			transition = transition,
			observedAtMs = boundary.observedAtMs,
			appliedAtMs = appliedAtMs(boundary, boundaryTimeMs),
		)
		return CursorResolution.Outcome(
			AmbientStepsImportResult.Gap(
				reason = AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED,
				fromTimeMs = cursor.importedThroughTimeMs,
				toTimeMs = boundaryTimeMs,
			),
		)
	}
	if (cursor.importedThroughTimeMs > boundaryTimeMs) {
		return CursorResolution.Outcome(
			stale(AmbientStepsImportStaleReason.AUTHORIZATION_CHANGED),
		)
	}
	val transition = authority.transitionFrom(cursor, boundary.observedAtMs)
	return CursorResolution.Ready(
		rotateAuthority(
			cursor = cursor,
			authority = authority,
			transition = transition,
			observedAtMs = boundary.observedAtMs,
			appliedAtMs = appliedAtMs(boundary, boundaryTimeMs),
		),
	)
}

internal suspend fun AmbientStepsFactImporter.advanceAcrossGap(
	cursor: AmbientStepsImportCursorEntity,
	reason: AmbientStepsImportGapReason,
	toTimeMs: Long,
	nextZoneId: String,
	boundary: AmbientStepsImportBoundary,
): AmbientStepsImportCursorEntity {
	require(toTimeMs >= cursor.importedThroughTimeMs)
	val storedReason = reason.toStoredGapReason()
	val nextGapSequence = Math.addExact(cursor.lastGapSequence, 1L)
	val nextContinuityGeneration = Math.addExact(cursor.continuitySegmentGeneration, 1L)
	val recordedAtMs = appliedAtMs(boundary, toTimeMs)
	val previousZoneId = if (
		reason == AmbientStepsImportGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED
	) {
		AmbientStepsImportGapEntity.ZONE_AUTHORITY_UNOBSERVED
	} else {
		cursor.lastObservedZoneId
	}
	val gapId = AmbientStepsImportGapIntegrity.gapId(
		registrationGeneration = cursor.registrationGeneration,
		gapSequence = nextGapSequence,
		provider = cursor.provider,
		sourceInstanceId = cursor.sourceInstanceId,
		reason = storedReason,
		gapStartTimeMs = cursor.importedThroughTimeMs,
		gapEndTimeMs = toTimeMs,
		predecessorRegistrationGeneration = null,
		predecessorProvider = null,
		previousClockDomainId = cursor.lastObservedBootId,
		nextClockDomainId = boundary.observedBootId,
		previousZoneId = previousZoneId,
		nextZoneId = nextZoneId,
		collectedDataEpoch = cursor.collectedDataEpoch,
	)
	val gap = AmbientStepsImportGapEntity(
		gapId = gapId,
		registrationGeneration = cursor.registrationGeneration,
		gapSequence = nextGapSequence,
		provider = cursor.provider,
		sourceInstanceId = cursor.sourceInstanceId,
		reason = storedReason,
		gapStartTimeMs = cursor.importedThroughTimeMs,
		gapEndTimeMs = toTimeMs,
		predecessorRegistrationGeneration = null,
		predecessorProvider = null,
		previousClockDomainId = cursor.lastObservedBootId,
		nextClockDomainId = boundary.observedBootId,
		previousZoneId = previousZoneId,
		nextZoneId = nextZoneId,
		collectedDataEpoch = cursor.collectedDataEpoch,
		recordedAtMs = recordedAtMs,
	)
	return commitGapAndBeginSegment(cursor, gap, nextContinuityGeneration, boundary.observedAtMs)
}

internal suspend fun AmbientStepsFactImporter.beginProviderChangedSegment(
	cursor: AmbientStepsImportCursorEntity,
	predecessor: HistoricalAmbientAuthority,
	boundary: AmbientStepsImportBoundary,
): AmbientStepsImportCursorEntity {
	val nextGapSequence = Math.addExact(cursor.lastGapSequence, 1L)
	val nextContinuityGeneration = Math.addExact(cursor.continuitySegmentGeneration, 1L)
	val transitionTimeMs = cursor.importedThroughTimeMs
	val recordedAtMs = appliedAtMs(boundary, transitionTimeMs)
	val gapId = AmbientStepsImportGapIntegrity.gapId(
		registrationGeneration = cursor.registrationGeneration,
		gapSequence = nextGapSequence,
		provider = cursor.provider,
		sourceInstanceId = cursor.sourceInstanceId,
		reason = AmbientStepsImportGapEntity.REASON_PROVIDER_CHANGED,
		gapStartTimeMs = transitionTimeMs,
		gapEndTimeMs = transitionTimeMs,
		predecessorRegistrationGeneration = predecessor.registration.registrationGeneration,
		predecessorProvider = predecessor.provider.name,
		previousClockDomainId = cursor.lastObservedBootId,
		nextClockDomainId = cursor.lastObservedBootId,
		previousZoneId = cursor.lastObservedZoneId,
		nextZoneId = cursor.lastObservedZoneId,
		collectedDataEpoch = cursor.collectedDataEpoch,
	)
	val gap = AmbientStepsImportGapEntity(
		gapId = gapId,
		registrationGeneration = cursor.registrationGeneration,
		gapSequence = nextGapSequence,
		provider = cursor.provider,
		sourceInstanceId = cursor.sourceInstanceId,
		reason = AmbientStepsImportGapEntity.REASON_PROVIDER_CHANGED,
		gapStartTimeMs = transitionTimeMs,
		gapEndTimeMs = transitionTimeMs,
		predecessorRegistrationGeneration = predecessor.registration.registrationGeneration,
		predecessorProvider = predecessor.provider.name,
		previousClockDomainId = cursor.lastObservedBootId,
		nextClockDomainId = cursor.lastObservedBootId,
		previousZoneId = cursor.lastObservedZoneId,
		nextZoneId = cursor.lastObservedZoneId,
		collectedDataEpoch = cursor.collectedDataEpoch,
		recordedAtMs = recordedAtMs,
	)
	return commitGapAndBeginSegment(cursor, gap, nextContinuityGeneration, boundary.observedAtMs)
}

internal suspend fun AmbientStepsFactImporter.commitGapAndBeginSegment(
	cursor: AmbientStepsImportCursorEntity,
	gap: AmbientStepsImportGapEntity,
	nextContinuityGeneration: Long,
	observedAtMs: Long,
): AmbientStepsImportCursorEntity {
	val dao = database.ambientStepsImportStateDao()
	if (dao.insertGap(gap) == INSERT_IGNORED &&
		dao.gap(cursor.registrationGeneration, gap.gapSequence) != gap
	) {
		throw ConcurrentAmbientStepsImportException()
	}
	val nextCursorRevision = Math.addExact(cursor.cursorRevision, 1L)
	if (dao.beginNextSegmentExact(
			registrationGeneration = cursor.registrationGeneration,
			expectedContinuitySegmentGeneration = cursor.continuitySegmentGeneration,
			expectedLastGapSequence = cursor.lastGapSequence,
			expectedCursorRevision = cursor.cursorRevision,
			expectedImportedThroughTimeMs = cursor.importedThroughTimeMs,
			expectedObservedAtMs = cursor.lastObservedAtMs,
			expectedUpdatedAtMs = cursor.updatedAtMs,
			newLastGapSequence = gap.gapSequence,
			newContinuitySegmentGeneration = nextContinuityGeneration,
			newSegmentStartTimeMs = gap.gapEndTimeMs,
			newObservedAtMs = observedAtMs,
			newBootId = gap.nextClockDomainId,
			newZoneId = gap.nextZoneId,
			newCursorRevision = nextCursorRevision,
			updatedAtMs = gap.recordedAtMs,
		) != 1
	) {
		throw ConcurrentAmbientStepsImportException()
	}
	return requireNotNull(dao.cursor(cursor.registrationGeneration))
}

internal suspend fun AmbientStepsFactImporter.rotateAuthority(
	cursor: AmbientStepsImportCursorEntity,
	authority: AmbientAuthority,
	transition: AmbientStepsImportAuthorityTransitionEntity,
	observedAtMs: Long,
	appliedAtMs: Long,
): AmbientStepsImportCursorEntity {
	val dao = database.ambientStepsImportStateDao()
	val insert = dao.insertAuthorityTransition(transition)
	if (insert == INSERT_IGNORED) {
		val stored = dao.authorityTransitions(cursor.registrationGeneration)
			.singleOrNull { it.transitionSequence == transition.transitionSequence }
		if (stored != transition) {
			throw ConcurrentAmbientStepsImportException()
		}
	}
	val nextRevision = Math.addExact(cursor.cursorRevision, 1L)
	if (dao.rotateAuthorityExact(
			registrationGeneration = cursor.registrationGeneration,
			expectedContinuitySegmentGeneration = cursor.continuitySegmentGeneration,
			expectedLastGapSequence = cursor.lastGapSequence,
			expectedAuthorityTransitionSequence = cursor.authorityTransitionSequence,
			expectedCursorRevision = cursor.cursorRevision,
			expectedObservedAtMs = cursor.lastObservedAtMs,
			expectedUpdatedAtMs = cursor.updatedAtMs,
			newAuthorityTransitionSequence = transition.transitionSequence,
			newContinuitySegmentGeneration = transition.toContinuitySegmentGeneration,
			newAuthorizationRevision = transition.toAuthorizationRevision,
			newAuthorizationFingerprint = transition.toAuthorizationFingerprint,
			newAuthorizationEffectiveBootId = transition.toAuthorizationEffectiveBootId,
			newAuthorizationEffectiveElapsedRealtimeNanos =
				transition.toAuthorizationEffectiveElapsedRealtimeNanos,
			newAuthorizationEffectiveWallTimeMs = transition.toAuthorizationEffectiveWallTimeMs,
			newSourcePolicyRevision = transition.toSourcePolicyRevision,
			newAmbientConsentEpoch = transition.toAmbientConsentEpoch,
			newRetentionScope = authority.retention.scope,
			newRetentionPolicyId = authority.retention.opaquePolicyId,
			newRetentionApprovalRevision = authority.retention.approvalRevision,
			effectiveBoundaryTimeMs = transition.effectiveBoundaryTimeMs,
			newObservedAtMs = observedAtMs,
			newCursorRevision = nextRevision,
			updatedAtMs = appliedAtMs,
		) != 1
	) {
		throw ConcurrentAmbientStepsImportException()
	}
	return requireNotNull(dao.cursor(cursor.registrationGeneration))
}

private fun AmbientStepsImportGapReason.toStoredGapReason(): String = when (this) {
	AmbientStepsImportGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED ->
		AmbientStepsImportGapEntity.REASON_INITIAL_ZONE_AUTHORITY_UNOBSERVED
	AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED ->
		AmbientStepsImportGapEntity.REASON_AUTHORITY_BOUNDARY_NOT_DRAINED
	AmbientStepsImportGapReason.PROVIDER_NO_EVIDENCE ->
		AmbientStepsImportGapEntity.REASON_PROVIDER_NO_EVIDENCE
	AmbientStepsImportGapReason.RETENTION_ADVANCED ->
		AmbientStepsImportGapEntity.REASON_PROVIDER_RETENTION_LOSS
	AmbientStepsImportGapReason.ZONE_CHANGED -> AmbientStepsImportGapEntity.REASON_ZONE_CHANGED
}
