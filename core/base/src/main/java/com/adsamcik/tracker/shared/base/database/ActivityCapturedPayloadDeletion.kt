package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.ActivityCapturedFactDao
import com.adsamcik.tracker.shared.base.database.dao.SourceDeletionFenceDao
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Authenticates and removes only captured Activity payload owned by one exact logical run set.
 *
 * The caller must already hold its presentation-day locks and a Room transaction. Session, run,
 * manifest, authorization, CONTROL WAL, registration-plan, and completeness authority is retained
 * so the installed per-run fences remain independently auditable and prevent resurrection.
 */
suspend fun AppDatabase.deleteSelectedCapturedActivityFactsInTransaction(
	logicalTrackingId: String,
	runScopes: List<ActivityCapturedSelectedRunScope>,
	expectedCollectedDataEpoch: Long,
	deletedAtMs: Long,
): ActivityCapturedSelectedDeletionResult = deleteSelectedCapturedActivityFactsInTransaction(
	logicalTrackingId = logicalTrackingId,
	runScopes = runScopes,
	expectedCollectedDataEpoch = expectedCollectedDataEpoch,
	deletedAtMs = deletedAtMs,
	limits = DEFAULT_ACTIVITY_CAPTURED_MAINTENANCE_LIMITS,
	checkpoint = { currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.deleteSelectedCapturedActivityFactsInTransaction(
	logicalTrackingId: String,
	runScopes: List<ActivityCapturedSelectedRunScope>,
	expectedCollectedDataEpoch: Long,
	deletedAtMs: Long,
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
): ActivityCapturedSelectedDeletionResult {
	require(logicalTrackingId.isNotBlank())
	require(runScopes.isNotEmpty())
	require(expectedCollectedDataEpoch >= 0L)
	require(deletedAtMs >= 0L)
	val runIds = runScopes.map(ActivityCapturedSelectedRunScope::serviceRunId)
	val segmentIds = runScopes.map(ActivityCapturedSelectedRunScope::sessionSegmentId)
	if (runIds.distinct().size != runIds.size ||
		segmentIds.distinct().size != runScopes.size
	) {
		return ActivityCapturedSelectedDeletionResult.Blocked(
			ActivityCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
	var mutationStarted = false
	return try {
		val evidenceState = sourceEvidenceStateDao().get()
		if (evidenceState?.collectedDataEpoch != expectedCollectedDataEpoch) {
			return ActivityCapturedSelectedDeletionResult.Blocked(
				ActivityCapturedSelectedDeletionBlockedReason.STALE_COLLECTED_DATA_EPOCH,
			)
		}
		val audit = auditSelectedActivityDeletionScope(logicalTrackingId, runScopes, limits, checkpoint)
		selectedActivityAuditRejection(audit, expectedCollectedDataEpoch, deletedAtMs)?.let { reason ->
			return ActivityCapturedSelectedDeletionResult.Blocked(reason)
		}

		val fenceDao = sourceDeletionFenceDao()
		val missingFences = fenceDao.loadSelectedActivityDeletionFences(
			logicalTrackingId, runScopes, expectedCollectedDataEpoch, deletedAtMs,
		)
		if (missingFences == null) {
			ActivityCapturedSelectedDeletionResult.Blocked(
				ActivityCapturedSelectedDeletionBlockedReason.DELETION_FENCE_CONFLICT,
			)
		} else {
			mutationStarted = true
			installSelectedActivityDeletionFences(fenceDao, missingFences, deletedAtMs, checkpoint)

			removeSelectedActivityPayload(audit, logicalTrackingId, runIds, segmentIds, checkpoint)
		}
	} catch (blocked: ActivityCapturedRetentionBlockedException) {
		selectedActivityDeletionFailure(
			mutationStarted,
			ActivityCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	} catch (@Suppress("SwallowedException") _: ActivityCapturedMaintenanceLimitExceeded) {
		selectedActivityDeletionFailure(
			mutationStarted,
			ActivityCapturedSelectedDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		selectedActivityDeletionFailure(
			mutationStarted,
			ActivityCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
}

private suspend fun AppDatabase.auditSelectedActivityDeletionScope(
	logicalTrackingId: String,
	runScopes: List<ActivityCapturedSelectedRunScope>,
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
): ActivityCapturedFactAudit {
	val scope = ActivityCapturedAuditScope(
		logicalTrackingId = logicalTrackingId,
		runs = runScopes.associateBy(ActivityCapturedSelectedRunScope::serviceRunId),
	)
	return auditCapturedActivityFacts(
		limits = limits,
		checkpoint = checkpoint,
		requireOwnerWhenEmpty = false,
		selectedScope = scope,
	)
}

private fun selectedActivityAuditRejection(
	audit: ActivityCapturedFactAudit,
	expectedCollectedDataEpoch: Long,
	deletedAtMs: Long,
): ActivityCapturedSelectedDeletionBlockedReason? {
	if (deletedAtMs < audit.latestDurableTimeMs) {
		return ActivityCapturedSelectedDeletionBlockedReason.STALE_REQUEST
	}
	if (audit.lineages.any { lineage ->
			lineage.revisions.any { persisted ->
				persisted.revision.collectedDataEpoch != expectedCollectedDataEpoch
			}
		}
	) {
		return ActivityCapturedSelectedDeletionBlockedReason.STALE_COLLECTED_DATA_EPOCH
	}
	return null
}

private suspend fun SourceDeletionFenceDao.loadSelectedActivityDeletionFences(
	logicalTrackingId: String,
	runScopes: List<ActivityCapturedSelectedRunScope>,
	expectedCollectedDataEpoch: Long,
	deletedAtMs: Long,
): List<SourceDeletionFenceEntity>? {
	val missingFences = mutableListOf<SourceDeletionFenceEntity>()
	for (runScope in runScopes) {
		val expected = SourceDeletionFenceEntity.createLogicalServiceRun(
			sourceKind = ACTIVITY_CAPTURED_SOURCE_KIND,
			purpose = ACTIVITY_CAPTURED_CAPTURE_PURPOSE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = runScope.serviceRunId,
			fenceGeneration = FIRST_DELETION_GENERATION,
			collectedDataEpoch = expectedCollectedDataEpoch,
			deletedAtMs = deletedAtMs,
		)
		val retained = try {
			get(
				expected.sourceKind,
				expected.purpose,
				expected.scopeKind,
				expected.scopeIdentityDigest,
			)
		} catch (_: IllegalArgumentException) {
			return null
		}
		if (retained == null) {
			missingFences += expected
		} else {
			if (retained.sourceKind != expected.sourceKind || retained.purpose != expected.purpose ||
				retained.scopeKind != expected.scopeKind ||
				retained.scopeIdentityDigest != expected.scopeIdentityDigest
			) {
				return null
			}
			if (retained.fenceGeneration != FIRST_DELETION_GENERATION ||
				retained.collectedDataEpoch != expectedCollectedDataEpoch
			) {
				return null
			}
		}
	}
	return missingFences
}

private suspend fun AppDatabase.installSelectedActivityDeletionFences(
	fenceDao: SourceDeletionFenceDao,
	missingFences: List<SourceDeletionFenceEntity>,
	deletedAtMs: Long,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
) {
	for (fence in missingFences) {
		if (fenceDao.insertIfAbsent(fence) == INSERT_IGNORED) {
			failSelectedActivityDeletionMutation()
		}
	}
	checkpoint(ActivityCapturedMaintenanceCheckpoint.DELETION_FENCES_INSTALLED)
	if (sourceEvidenceStateDao().incrementRevision(deletedAtMs) != 1) {
		failSelectedActivityDeletionMutation()
	}
}

private suspend fun AppDatabase.removeSelectedActivityPayload(
	audit: ActivityCapturedFactAudit,
	logicalTrackingId: String,
	runIds: List<String>,
	segmentIds: List<Long>,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
): ActivityCapturedSelectedDeletionResult.Deleted {
	val dao = activityCapturedFactDao()
	var removedRevisions = 0
	for (batch in audit.lineages.chunked(ACTIVITY_CAPTURED_DELETE_BATCH_SIZE)) {
		val ids = batch.map(ActivityCapturedLineage::logicalWindowId)
		if (dao.deleteExactCursors(ACTIVITY_CAPTURED_WRITER_ID, ACTIVITY_CAPTURED_WRITER_VERSION, ids) != batch.size) {
			failSelectedActivityDeletionMutation()
		}
		val expectedRevisions = batch.sumOf { lineage -> lineage.revisions.size }
		val deleted = dao.deleteExactRevisionLineages(ACTIVITY_CAPTURED_WRITER_ID, ACTIVITY_CAPTURED_WRITER_VERSION, ids)
		if (deleted != expectedRevisions) {
			failSelectedActivityDeletionMutation()
		}
		removedRevisions = Math.addExact(removedRevisions, deleted)
		checkpoint(ActivityCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
	}
	if (dao.selectedRevisionCount(logicalTrackingId, runIds, segmentIds) != 0L ||
		dao.selectedFragmentCount(logicalTrackingId, runIds, segmentIds) != 0L ||
		dao.selectedEvidenceCount(logicalTrackingId, runIds, segmentIds) != 0L ||
		dao.selectedCursorCount(logicalTrackingId, runIds, segmentIds) != 0L
	) {
		failSelectedActivityDeletionMutation()
	}
	return ActivityCapturedSelectedDeletionResult.Deleted(
		logicalWindowCount = audit.lineages.size,
		revisionCount = removedRevisions,
		fencedServiceRunCount = runIds.size,
	)
}

private fun selectedActivityDeletionFailure(
	mutationStarted: Boolean,
	reason: ActivityCapturedSelectedDeletionBlockedReason,
): ActivityCapturedSelectedDeletionResult.Blocked {
	if (mutationStarted) {
		failSelectedActivityDeletionMutation()
	}
	return ActivityCapturedSelectedDeletionResult.Blocked(reason)
}

private fun failSelectedActivityDeletionMutation(): Nothing =
	throw ActivityCapturedSelectedDeletionConcurrentMutationException()

/**
 * Deletes captured Activity product state after capture consent has been explicitly revoked.
 *
 * CONTROL demands, authorization history, and WAL rows are never changed here. Every retained fact
 * scope receives a permanent run fence before fact payload is removed, so old captured WAL cannot
 * recreate it. Unknown payload shapes block the operation rather than being silently skipped.
 */
suspend fun AppDatabase.deleteCapturedActivityFactsAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
): ActivityCapturedSourceDeletionResult = deleteCapturedActivityFactsAfterConsentReset(
	expectedCollectedDataEpoch,
	expectedRevokedConsentEpoch,
	deletedAtMs,
	DEFAULT_ACTIVITY_CAPTURED_MAINTENANCE_LIMITS,
	{ currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.deleteCapturedActivityFactsAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
): ActivityCapturedSourceDeletionResult {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedRevokedConsentEpoch >= 0L)
	require(deletedAtMs >= 0L)
	return try {
		withTransaction {
			deleteActivityCaptureAfterConsentResetInTransaction(
				expectedCollectedDataEpoch, expectedRevokedConsentEpoch, deletedAtMs, limits, checkpoint,
			)
		}
	} catch (blocked: ActivityCapturedSourceDeletionBlockedException) {
		ActivityCapturedSourceDeletionResult.Blocked(blocked.reason)
	} catch (blocked: ActivityCapturedRetentionBlockedException) {
		ActivityCapturedSourceDeletionResult.Blocked(
			when (blocked.reason) {
				ActivityCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED ->
					ActivityCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED
				ActivityCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT ->
					ActivityCapturedSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT
				ActivityCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
					ActivityCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED
				else -> ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE
			},
		)
	} catch (@Suppress("SwallowedException") _: ActivityCapturedMaintenanceLimitExceeded) {
		ActivityCapturedSourceDeletionResult.Blocked(
			ActivityCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		ActivityCapturedSourceDeletionResult.Blocked(
			ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
}

private suspend fun AppDatabase.deleteActivityCaptureAfterConsentResetInTransaction(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
): ActivityCapturedSourceDeletionResult {
	checkpoint(ActivityCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
	val evidenceState = sourceEvidenceStateDao().get()
	if (evidenceState == null || evidenceState.collectedDataEpoch != expectedCollectedDataEpoch) {
		block(ActivityCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
	}
	requireRevokedActivityCaptureConsent(expectedRevokedConsentEpoch, deletedAtMs)
	val dao = activityCapturedFactDao()
	dao.requireActivityCaptureQuiesced(limits)
	val audit = auditCapturedActivityFacts(limits, checkpoint, requireOwnerWhenEmpty = false)
	val plans = auditCapturedActivityRegistrationPlans(limits)
	if (deletedAtMs < audit.latestDurableTimeMs) {
		block(ActivityCapturedSourceDeletionBlockedReason.STALE_REQUEST)
	}
	val nativeObservationCount = activitySnapshotDao().nativeCount()
	fenceAmbientSensorConsentErasureInTransaction(ACTIVITY_CAPTURED_SOURCE_KIND,
		expectedCollectedDataEpoch, deletedAtMs)
	if (audit.lineages.isEmpty() && plans.isEmpty() && nativeObservationCount == 0) {
		return ActivityCapturedSourceDeletionResult.AlreadyDeleted
	}
	val scopes = audit.lineages.map { lineage ->
		ActivityCapturedRunScope(
			lineage.revisions.first().revision.logicalTrackingId,
			lineage.revisions.first().revision.serviceRunId,
		)
	}.distinct()
	installActivityConsentDeletionFences(scopes, expectedCollectedDataEpoch, deletedAtMs)
	checkpoint(ActivityCapturedMaintenanceCheckpoint.DELETION_FENCES_INSTALLED)

	val revisionCount = audit.lineages.sumOf { lineage -> lineage.revisions.size }
	dao.removeAllActivityCapturePayload()
	check(activitySnapshotDao().deleteAllNative() == nativeObservationCount)
	check(sourceEvidenceStateDao().incrementRevision(maxOf(evidenceState.updatedAtMs, deletedAtMs)) == 1) {
		"Unable to publish captured Activity source deletion"
	}
	checkpoint(ActivityCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
	return ActivityCapturedSourceDeletionResult.Deleted(
		logicalWindowCount = audit.lineages.size,
		revisionCount = revisionCount,
		registrationPlanCount = plans.size,
		fencedServiceRunCount = scopes.size,
		nativeObservationCount = nativeObservationCount,
	)
}

private suspend fun AppDatabase.requireRevokedActivityCaptureConsent(
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
) {
	val policyAuthority = sourcePolicyDao().authority()
	val policy = policyAuthority?.takeIf { authority ->
		authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
	}?.let { authority ->
		sourcePolicyDao().policyAtRevision(authority.currentPolicyRevision, ACTIVITY_CAPTURED_SOURCE_KIND)
	}
	val revokedConsent = sourcePolicyDao().latestConsentEpoch(
		ACTIVITY_CAPTURED_SOURCE_KIND,
		ACTIVITY_CAPTURED_CAPTURE_PURPOSE,
	)
	if (policy == null || revokedConsent == null ||
		revokedConsent.epoch != expectedRevokedConsentEpoch
	) {
		block(ActivityCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
	}
	requireActivityCaptureConsentRevoked(policy, revokedConsent)
	if (deletedAtMs < maxOf(
		policyAuthority.updatedAtMs,
		policy.effectiveWallTimeMs,
		revokedConsent.effectiveWallTimeMs,
	)) {
		block(ActivityCapturedSourceDeletionBlockedReason.STALE_REQUEST)
	}
}

private fun requireActivityCaptureConsentRevoked(
	policy: SourcePolicyEntity,
	revokedConsent: SourceConsentEpochEntity,
) {
	if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null ||
		revokedConsent.eligible || revokedConsent.persistenceEligible
	) {
		block(ActivityCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
	}
	if (revokedConsent.policyRevision > policy.policyRevision) {
		block(ActivityCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
	}
}

private suspend fun ActivityCapturedFactDao.requireActivityCaptureQuiesced(
	limits: ActivityCapturedMaintenanceLimits,
) {
	val demands = capturedActivityDemandsForDeletion(
		ACTIVITY_CAPTURED_SOURCE_KIND,
		ACTIVITY_CAPTURED_CAPTURE_PURPOSE,
		limits.maximumCaptureDemands + 1,
	)
	if (demands.size > limits.maximumCaptureDemands) {
		throw ActivityCapturedMaintenanceLimitExceeded()
	}
	if (demands.isNotEmpty()) {
		block(ActivityCapturedSourceDeletionBlockedReason.CAPTURE_DEMAND_NOT_QUIESCED)
	}
	val registrations = activityRegistrationsForDeletion(
		ACTIVITY_CAPTURED_SOURCE_KIND,
		limits.maximumNonterminalRegistrations + 1,
	)
	if (registrations.size > limits.maximumNonterminalRegistrations) {
		throw ActivityCapturedMaintenanceLimitExceeded()
	}
	// Activity still has one shared physical callback registration. Until a released owner
	// scope proves otherwise, every nonterminal generation is capture-compatible.
	if (registrations.isNotEmpty()) {
		block(ActivityCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
	}
}

private suspend fun AppDatabase.installActivityConsentDeletionFences(
	scopes: List<ActivityCapturedRunScope>,
	expectedCollectedDataEpoch: Long,
	deletedAtMs: Long,
) {
	for (scope in scopes) {
		val expectedFence = SourceDeletionFenceEntity.createLogicalServiceRun(
			sourceKind = ACTIVITY_CAPTURED_SOURCE_KIND,
			purpose = ACTIVITY_CAPTURED_CAPTURE_PURPOSE,
			logicalTrackingId = scope.logicalTrackingId,
			serviceRunId = scope.serviceRunId,
			fenceGeneration = FIRST_DELETION_GENERATION,
			collectedDataEpoch = expectedCollectedDataEpoch,
			deletedAtMs = deletedAtMs,
		)
		if (sourceDeletionFenceDao().insertIfAbsent(expectedFence) == INSERT_IGNORED) {
			val retained = sourceDeletionFenceDao().get(
				expectedFence.sourceKind,
				expectedFence.purpose,
				expectedFence.scopeKind,
				expectedFence.scopeIdentityDigest,
			)
			if (retained != expectedFence) {
				block(ActivityCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
			}
		}
	}
}

private suspend fun ActivityCapturedFactDao.removeAllActivityCapturePayload() {
	deleteAllEvidence()
	deleteAllFragments()
	deleteAllCursors()
	deleteAllRevisions()
	deleteAllRegistrationPlanBindings()
	if (revisionCount() != 0L || fragmentCount() != 0L || evidenceCount() != 0L) {
		block(ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (cursorCount() != 0L || registrationPlanBindingCount() != 0L) {
		block(ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private data class ActivityCapturedRunScope(
	val logicalTrackingId: String,
	val serviceRunId: String,
)

internal class ActivityCapturedSourceDeletionBlockedException(
	val reason: ActivityCapturedSourceDeletionBlockedReason,
) : IllegalStateException(reason.name)

private fun block(reason: ActivityCapturedSourceDeletionBlockedReason): Nothing =
	throw ActivityCapturedSourceDeletionBlockedException(reason)

private const val FIRST_DELETION_GENERATION = 1L
private const val INSERT_IGNORED = -1L
