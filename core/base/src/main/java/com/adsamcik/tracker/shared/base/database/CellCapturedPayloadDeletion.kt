package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.CellCapturedFactDao
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.RetainedCaptureEventIdentity
import com.adsamcik.tracker.shared.base.database.data.RetainedCaptureScopeIdentity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionScopeProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Deletes only the identity-free captured-Cell product store after exact capture-consent revoke.
 *
 * Durable source WAL, authorization history, registrations, and demands are audit inputs and are
 * never removed. Each retained physical run receives both the payload-free global scope fence and
 * the matching Cell-local monotonic generation before any fact or cursor is removed.
 */
suspend fun AppDatabase.deleteCapturedCellFactsAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
): CellCapturedSourceDeletionResult = deleteCapturedCellFactsAfterConsentReset(
	expectedCollectedDataEpoch,
	expectedDeletedSourceEventHighWaterOrdinal,
	expectedRevokedConsentEpoch,
	deletedAtMs,
	DEFAULT_CELL_CAPTURED_MAINTENANCE_LIMITS,
	{ currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.deleteCapturedCellFactsAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedSourceDeletionResult {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
	require(expectedRevokedConsentEpoch >= 0L)
	require(deletedAtMs >= 0L)
	return try {
		withTransaction {
			deleteCellCaptureAfterConsentResetInTransaction(
				expectedCollectedDataEpoch, expectedDeletedSourceEventHighWaterOrdinal,
				expectedRevokedConsentEpoch, deletedAtMs, limits, checkpoint,
			)
		}
	} catch (blocked: CellCapturedSourceDeletionBlockedException) {
		CellCapturedSourceDeletionResult.Blocked(blocked.reason)
	} catch (blocked: CellCapturedRetentionBlockedException) {
		CellCapturedSourceDeletionResult.Blocked(
			when (blocked.reason) {
				CellCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED ->
					CellCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED
				CellCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT ->
					CellCapturedSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT
				CellCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
					CellCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED
				else -> CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE
			},
		)
	} catch (@Suppress("SwallowedException") _: CellCapturedMaintenanceLimitExceeded) {
		CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
}

/** Reconcile every retained Cell scope before deciding whether source deletion already completed. */
private suspend fun AppDatabase.reconcileCellCaptureDeletionScopes(
	scopeAuthorities: Collection<RetainedCaptureScopeIdentity>,
	generationByScope: Map<CellCapturedRunScope, CellCaptureDeletionGenerationEntity>,
	expectedCollectedDataEpoch: Long,
): List<RetainedCaptureScopeIdentity> {
	val scopesNeedingAuthority = mutableListOf<RetainedCaptureScopeIdentity>()
	for (identity in scopeAuthorities) {
		currentCoroutineContext().ensureActive()
		val scope = CellCapturedRunScope(
			identity.logicalTrackingId,
			identity.serviceRunId,
		)
		val generation = generationByScope[scope]
		val fence = sourceDeletionFenceDao().get(
			CELL_SOURCE,
			CAPTURE_PURPOSE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			identity.logicalRunScopeDigest,
		)
		if (generation == null && fence == null) {
			scopesNeedingAuthority += identity
			continue
		}
		if (!hasExactCellCaptureDeletionFence(generation, fence, identity, expectedCollectedDataEpoch)) {
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
		}
	}
	return scopesNeedingAuthority
}

/** Install and read back both Cell replay fences before removing captured payload. */
private suspend fun AppDatabase.installCellCaptureDeletionFences(
	dao: CellCapturedFactDao,
	scopesNeedingAuthority: List<RetainedCaptureScopeIdentity>,
	expectedCollectedDataEpoch: Long,
	deletedAtMs: Long,
) {
	for (identity in scopesNeedingAuthority) {
		currentCoroutineContext().ensureActive()
		val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
			sourceKind = CELL_SOURCE,
			purpose = CAPTURE_PURPOSE,
			logicalTrackingId = identity.logicalTrackingId,
			serviceRunId = identity.serviceRunId,
			fenceGeneration = CELL_CAPTURED_FIRST_DELETION_GENERATION,
			collectedDataEpoch = expectedCollectedDataEpoch,
			deletedAtMs = deletedAtMs,
		)
		if (sourceDeletionFenceDao().insertIfAbsent(fence) == CELL_CAPTURED_INSERT_IGNORED &&
			sourceDeletionFenceDao().get(
				fence.sourceKind,
				fence.purpose,
				fence.scopeKind,
				fence.scopeIdentityDigest,
			) != fence
		) {
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
		}
		val generation = CellCaptureDeletionGenerationEntity(
			logicalTrackingId = identity.logicalTrackingId,
			serviceRunId = identity.serviceRunId,
			collectedDataEpoch = expectedCollectedDataEpoch,
			generation = CELL_CAPTURED_FIRST_DELETION_GENERATION,
			updatedAtMs = deletedAtMs,
		)
		dao.insertDeletionGeneration(generation)
		if (dao.deletionGeneration(
				identity.logicalTrackingId,
				identity.serviceRunId,
			) != generation ||
			sourceDeletionFenceDao().get(
				fence.sourceKind,
				fence.purpose,
				fence.scopeKind,
				fence.scopeIdentityDigest,
			) != fence
		) {
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
		}
	}
}

/** Remove audited Cell capture rows and exact retention receipts while retaining sibling purposes. */
private suspend fun AppDatabase.removeCellCapturePayloadAndRetentionProofs(
	dao: CellCapturedFactDao,
	lineages: List<CellCapturedLineage>,
	retainedEventProofs: List<SourceEventRetentionProofEntity>,
	retainedScopeProofs: List<SourceEventRetentionScopeProofEntity>,
) {
	removeCellCaptureFactLineages(dao, lineages)
	capturedRadioFactRetentionProofDao().deleteSource(CELL_SOURCE)
	if (capturedRadioFactRetentionProofDao().allForSource(CELL_SOURCE).isNotEmpty()) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	removeCellCaptureEventProofs(retainedEventProofs)
	removeCellCaptureScopeProofs(retainedScopeProofs)
}


/** One original callback may cover several policy/consent intervals in the same run. */
private data class CellCapturedEventAuthorityKey(
	val eventId: String,
	val logicalRunScopeDigest: String,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
)

private fun CellCapturedRunScope.retainedIdentity(
	collectedDataEpoch: Long,
): RetainedCaptureScopeIdentity = RetainedCaptureScopeIdentity(
	sourceKind = CELL_SOURCE,
	logicalTrackingId = logicalTrackingId,
	serviceRunId = serviceRunId,
	collectedDataEpoch = collectedDataEpoch,
	scopeDeletionGeneration = 0L,
	logicalRunScopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
		CELL_SOURCE,
		CAPTURE_PURPOSE,
		logicalTrackingId,
		serviceRunId,
	),
)


private class CellCapturedSourceDeletionBlockedException(
	val reason: CellCapturedSourceDeletionBlockedReason,
) : IllegalStateException(reason.name)

internal fun blockCapturedCellSourceDeletion(reason: CellCapturedSourceDeletionBlockedReason): Nothing =
	throw CellCapturedSourceDeletionBlockedException(reason)

private suspend fun AppDatabase.deleteCellCaptureAfterConsentResetInTransaction(
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedSourceDeletionResult {
	checkpoint(CellCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
	val evidenceState = readCellCaptureDeletionEvidence(
		expectedCollectedDataEpoch, expectedDeletedSourceEventHighWaterOrdinal,
	)

	val (policy, revokedConsent) = readRevokedCellCaptureConsent(expectedRevokedConsentEpoch, deletedAtMs)

	val dao = cellCapturedFactDao()
	quiesceCellCaptureForConsentDeletion(dao, expectedCollectedDataEpoch, policy, revokedConsent, limits)

	val audit = auditCapturedCellFacts(evidenceState, limits, checkpoint)
	val walAudit = readCellCaptureWalAuthorityForErase(evidenceState, audit, limits, deletedAtMs)
	val retainedProofs = loadCellCapturedRetentionProofsForSourceErase(limits)
	val retainedEventProofs = loadCellSourceEventRetentionProofsForSourceErase(limits)
	val retainedScopeProofs = loadCellSourceEventRetentionScopeProofsForSourceErase(limits)
	val scopeAuthorities = linkedMapOf<CellCapturedRunScope, RetainedCaptureScopeIdentity>()
	val eventAuthorities = mutableMapOf<CellCapturedEventAuthorityKey, RetainedCaptureEventIdentity>()
	val rawEventIdentities = mutableMapOf<String, Pair<Long, String>>()
	fun registerScope(
		identity: RetainedCaptureScopeIdentity,
		eventIdentity: RetainedCaptureEventIdentity? = null,
	) {
		scopeAuthorities.registerCellCaptureScope(identity, expectedCollectedDataEpoch, limits)
		if (eventIdentity != null) {
			eventAuthorities.registerCellCaptureEvent(
				eventIdentity, rawEventIdentities, expectedDeletedSourceEventHighWaterOrdinal,
			)
		}
	}
	registerLiveCellCaptureAuthorities(audit, walAudit, expectedCollectedDataEpoch, ::registerScope)
	evidenceState.authenticateRetainedCellCaptureProofs(
		retainedProofs, retainedEventProofs, retainedScopeProofs, deletedAtMs, ::registerScope,
	)
	val scopesNeedingAuthority = reconcileCellCaptureDeletionScopes(
		scopeAuthorities.values, audit.generationByScope, expectedCollectedDataEpoch,
	)
	if (audit.lineages.isEmpty() && scopesNeedingAuthority.isEmpty()) {
		if (retainedProofs.isEmpty() && retainedEventProofs.isEmpty() && retainedScopeProofs.isEmpty()) {
			return CellCapturedSourceDeletionResult.AlreadyDeleted
		}
	}
	installCellCaptureDeletionFences(
		dao, scopesNeedingAuthority, expectedCollectedDataEpoch, deletedAtMs,
	)
	checkpoint(CellCapturedMaintenanceCheckpoint.DELETION_FENCES_INSTALLED)

	val revisionCount = audit.lineages.sumOf { lineage -> lineage.revisions.size }
	removeCellCapturePayloadAndRetentionProofs(
		dao, audit.lineages, retainedEventProofs, retainedScopeProofs,
	)
	check(sourceEvidenceStateDao().incrementRevision(maxOf(evidenceState.updatedAtMs, deletedAtMs)) == 1) {
		"Unable to publish captured Cell source deletion"
	}
	checkpoint(CellCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
	return CellCapturedSourceDeletionResult.Deleted(
		logicalFactCount = audit.lineages.size,
		revisionCount = revisionCount,
		fencedServiceRunCount = scopesNeedingAuthority.size,
	)
}

private suspend fun AppDatabase.readCellCaptureDeletionEvidence(
	expectedCollectedDataEpoch: Long, expectedDeletedSourceEventHighWaterOrdinal: Long,
): SourceEvidenceState {
	val evidenceState = sourceEvidenceStateDao().get()
	if (evidenceState == null || evidenceState.collectedDataEpoch != expectedCollectedDataEpoch ||
		evidenceState.deletedSourceEventHighWaterOrdinal !=
			expectedDeletedSourceEventHighWaterOrdinal
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
	}
	return evidenceState
}

private suspend fun AppDatabase.readRevokedCellCaptureConsent(
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
): Pair<SourcePolicyEntity, SourceConsentEpochEntity> {
	val policyAuthority = sourcePolicyDao().authority()?.takeIf { authority ->
		authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
	} ?: blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
	val policy = sourcePolicyDao().policyAtRevision(
		policyAuthority.currentPolicyRevision,
		CELL_SOURCE,
	)
	val revokedConsent = sourcePolicyDao().latestConsentEpoch(CELL_SOURCE, CAPTURE_PURPOSE)
	if (policy == null || revokedConsent == null) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
	}
	requireRevokedCellCaptureEligibility(policy, revokedConsent, expectedRevokedConsentEpoch)
	// Exact epoch/high-water and capture clocks fence this erase; sibling WAL cannot veto it.
	if (deletedAtMs < maxOf(
		policyAuthority.updatedAtMs,
		policy.effectiveWallTimeMs,
		revokedConsent.effectiveWallTimeMs,
	)) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.STALE_REQUEST)
	}
	return policy to revokedConsent
}

private suspend fun AppDatabase.readCellCaptureWalAuthorityForErase(
	evidenceState: SourceEvidenceState,
	audit: CellCapturedFactAudit,
	limits: CellCapturedMaintenanceLimits,
	deletedAtMs: Long,
): CellCapturedWalAudit {
	val walAudit = loadCapturedCellWalScopesForDeletion(evidenceState, limits)
	if (walAudit.scopes.isNotEmpty()) {
		val owner = sourceDestinationOwnerDao().get(
			CELL_SOURCE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
		)
		if (owner == null || owner.owner !=
			SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS ||
			!owner.ownerGeneration.isKnownCapturedWriterOwnerGeneration()
		) {
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED)
		}
	}
	if (deletedAtMs < maxOf(audit.latestDurableTimeMs, walAudit.latestDurableTimeMs)) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.STALE_REQUEST)
	}
	return walAudit
}

private fun MutableMap<CellCapturedRunScope, RetainedCaptureScopeIdentity>.registerCellCaptureScope(
	identity: RetainedCaptureScopeIdentity,
	expectedCollectedDataEpoch: Long,
	limits: CellCapturedMaintenanceLimits,
) {
	if (!identity.isCanonicalFor(CELL_SOURCE, expectedCollectedDataEpoch)) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val scope = CellCapturedRunScope(
		identity.logicalTrackingId,
		identity.serviceRunId,
	)
	val retainedScope = putIfAbsent(scope, identity)
	if (retainedScope != null && retainedScope != identity) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
	}
	if (retainedScope == null &&
		size > limits.maximumDeletionGenerations
	) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
}

private fun MutableMap<CellCapturedEventAuthorityKey, RetainedCaptureEventIdentity>.registerCellCaptureEvent(
	eventIdentity: RetainedCaptureEventIdentity,
	rawEventIdentities: MutableMap<String, Pair<Long, String>>,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
) {
	if (eventIdentity.sourceAdmissionOrdinal <=
		expectedDeletedSourceEventHighWaterOrdinal
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val rawIdentity = eventIdentity.sourceAdmissionOrdinal to eventIdentity.walIntegrityIdentity
	val original = rawEventIdentities.putIfAbsent(eventIdentity.eventId, rawIdentity)
	if (original != null && original != rawIdentity) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
	}
	val retained = putIfAbsent(
		CellCapturedEventAuthorityKey(
			eventIdentity.eventId,
			eventIdentity.scope.logicalRunScopeDigest,
			eventIdentity.sourcePolicyRevision,
			eventIdentity.captureConsentEpoch,
		),
		eventIdentity,
	)
	if (retained != null && retained != eventIdentity) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
	}
}

private fun SourceEvidenceState.authenticateRetainedCellCaptureProofs(
	retainedProofs: List<CapturedRadioFactRetentionProofEntity>,
	retainedEventProofs: List<SourceEventRetentionProofEntity>,
	retainedScopeProofs: List<SourceEventRetentionScopeProofEntity>,
	deletedAtMs: Long,
	registerScope: (RetainedCaptureScopeIdentity, RetainedCaptureEventIdentity?) -> Unit,
) {
	for (proof in retainedProofs) {
		val eventIdentity = proof.authenticatedCaptureEvent(
			expectedSourceKind = CELL_SOURCE,
			expectedCollectedDataEpoch = collectedDataEpoch,
			currentRetainedFromMs = retainedFromMs,
			deletedAtMs = deletedAtMs,
		) ?: blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		registerScope(eventIdentity.scope, eventIdentity)
	}
	val scopeProofsByDigest = authenticateCellCaptureScopeProofs(retainedScopeProofs, registerScope)
	for (proof in retainedEventProofs) {
		val eventIdentity = proof.authenticatedCaptureEvent(
			expectedSourceKind = CELL_SOURCE,
			expectedCollectedDataEpoch = collectedDataEpoch,
		) ?: blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val scopeProof = proof.logicalRunScopeDigest?.let(scopeProofsByDigest::get)
		if (scopeProof?.authenticatesRetainedEvent(proof) != true) {
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		registerScope(eventIdentity.scope, eventIdentity)
	}
}

private fun SourceEvidenceState.authenticateCellCaptureScopeProofs(
	retainedScopeProofs: List<SourceEventRetentionScopeProofEntity>,
	registerScope: (RetainedCaptureScopeIdentity, RetainedCaptureEventIdentity?) -> Unit,
): Map<String, SourceEventRetentionScopeProofEntity> {
	val scopeProofsByDigest =
		retainedScopeProofs.associateBy(
			SourceEventRetentionScopeProofEntity::logicalRunScopeDigest,
		)
	if (scopeProofsByDigest.size != retainedScopeProofs.size) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
	}
	for (proof in retainedScopeProofs) {
		val identity = proof.authenticatedCaptureScope(
			expectedSourceKind = CELL_SOURCE,
			expectedCollectedDataEpoch = collectedDataEpoch,
			expectedDeletedSourceEventHighWaterOrdinal =
				deletedSourceEventHighWaterOrdinal,
		) ?: blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		registerScope(identity, null)
	}
	return scopeProofsByDigest
}

private suspend fun AppDatabase.removeCellCaptureFactLineages(
	dao: CellCapturedFactDao,
	lineages: List<CellCapturedLineage>,
) {
	lineages.chunked(CELL_CAPTURED_DELETE_BATCH_SIZE).forEach { batch ->
		currentCoroutineContext().ensureActive()
		val ids = batch.map(CellCapturedLineage::logicalFactId)
		ids.forEach { radioObservationChildDao().deleteCellSessionFact(WRITER_ID, WRITER_VERSION, it) }
		if (dao.deleteExactCursors(WRITER_ID, WRITER_VERSION, ids) != batch.size ||
			dao.deleteExactRevisionLineages(WRITER_ID, WRITER_VERSION, ids) !=
				batch.sumOf { lineage -> lineage.revisions.size }
		) {
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
	if (dao.revisionCount() != 0L || dao.cursorCount() != 0L) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.removeCellCaptureEventProofs(
	retainedEventProofs: List<SourceEventRetentionProofEntity>,
) {
	if (retainedEventProofs.isNotEmpty()) {
		val eventIds = retainedEventProofs.map(SourceEventRetentionProofEntity::eventId)
		val deletedProofs = eventIds.chunked(CELL_CAPTURED_DELETE_BATCH_SIZE).sumOf { batch ->
			sourceEventRetentionProofDao().deleteExactSourceEvents(CELL_SOURCE, batch)
		}
		if (deletedProofs != eventIds.size) {
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
	if (sourceEventRetentionProofDao().captureProofCount(
			CELL_SOURCE,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		) != 0L
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.removeCellCaptureScopeProofs(
	retainedScopeProofs: List<SourceEventRetentionScopeProofEntity>,
) {
	if (retainedScopeProofs.isNotEmpty()) {
		val scopeDigests =
			retainedScopeProofs.map(SourceEventRetentionScopeProofEntity::logicalRunScopeDigest)
		val deletedScopes = scopeDigests.chunked(CELL_CAPTURED_DELETE_BATCH_SIZE).sumOf { batch ->
			sourceEventRetentionScopeProofDao().deleteExactSourceScopes(
				CELL_SOURCE,
				batch,
			)
		}
		if (deletedScopes != scopeDigests.size) {
			blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
	if (sourceEventRetentionScopeProofDao().countForSource(CELL_SOURCE) != 0L) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun hasExactCellCaptureDeletionFence(
	generation: CellCaptureDeletionGenerationEntity?,
	fence: SourceDeletionFenceEntity?,
	identity: RetainedCaptureScopeIdentity,
	expectedCollectedDataEpoch: Long,
): Boolean {
	if (generation == null || fence == null) {
		return false
	}
	if (generation.logicalTrackingId != identity.logicalTrackingId ||
		generation.serviceRunId != identity.serviceRunId ||
		generation.collectedDataEpoch != expectedCollectedDataEpoch ||
		generation.generation != CELL_CAPTURED_FIRST_DELETION_GENERATION
	) {
		return false
	}
	return fence == SourceDeletionFenceEntity.createLogicalServiceRun(
		CELL_SOURCE,
		CAPTURE_PURPOSE,
		identity.logicalTrackingId,
		identity.serviceRunId,
		CELL_CAPTURED_FIRST_DELETION_GENERATION,
		expectedCollectedDataEpoch,
		generation.updatedAtMs,
	)
}

private fun requireRevokedCellCaptureEligibility(
	policy: SourcePolicyEntity,
	revokedConsent: SourceConsentEpochEntity,
	expectedRevokedConsentEpoch: Long,
) {
	if (revokedConsent.epoch != expectedRevokedConsentEpoch) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
	}
	if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
	}
	if (revokedConsent.eligible || revokedConsent.persistenceEligible ||
		revokedConsent.policyRevision > policy.policyRevision
	) {
		blockCapturedCellSourceDeletion(CellCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
	}
}

private fun registerLiveCellCaptureAuthorities(
	audit: CellCapturedFactAudit,
	walAudit: CellCapturedWalAudit,
	expectedCollectedDataEpoch: Long,
	registerScope: (RetainedCaptureScopeIdentity, RetainedCaptureEventIdentity?) -> Unit,
) {
	for (scope in audit.lineages.map(CellCapturedLineage::scope) + walAudit.scopes) {
		registerScope(scope.retainedIdentity(expectedCollectedDataEpoch), null)
	}
	for (carrier in walAudit.carriers) {
		val scope = carrier.scope.retainedIdentity(expectedCollectedDataEpoch)
		registerScope(
			scope,
			RetainedCaptureEventIdentity(
				eventId = carrier.eventId,
				sourceAdmissionOrdinal = carrier.admissionOrdinal,
				sourcePolicyRevision = carrier.sourcePolicyRevision,
				captureConsentEpoch = carrier.captureConsentEpoch,
				walIntegrityIdentity = carrier.walIntegrityIdentity,
				scope = scope,
			),
		)
	}
}
