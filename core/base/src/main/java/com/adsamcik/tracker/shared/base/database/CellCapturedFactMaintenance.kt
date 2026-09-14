package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellProviderDeliveryIdentityFact
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.canonicalCellProviderDeliveryIdentity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class CellCapturedMaintenanceCheckpoint {
	TRANSACTION_STARTED,
	REVISION_PAGE_LOADED,
	LINEAGE_AUTHENTICATED,
	DELETION_FENCES_INSTALLED,
	PAYLOAD_REMOVED,
}

internal data class CellCapturedMaintenanceLimits(
	val revisionPageSize: Int = 256,
	val maximumRevisions: Int = 65_536,
	val maximumLogicalFacts: Int = 16_384,
	val maximumCursors: Int = 16_384,
	val maximumDeletionGenerations: Int = 16_384,
	val maximumManifestsPerRun: Int = 256,
	val maximumSourcesPerRun: Int = 4_096,
	val maximumSourcesPerManifest: Int = 32,
	val maximumSourcesPerPlan: Int = 12,
	val maximumPlanPayloadBytes: Int = 64 * 1_024,
	val maximumWalPayloadBytes: Int = 1_024 * 1_024,
	val maximumWalEvents: Int = 65_536,
	val maximumAuthorizationMembers: Int = 64,
	val maximumDirectDemands: Int = 256,
	val maximumNonterminalRegistrations: Int = 64,
) {
	init {
		require(revisionPageSize in 1..1_024)
		listOf(
			maximumRevisions,
			maximumLogicalFacts,
			maximumCursors,
			maximumDeletionGenerations,
			maximumManifestsPerRun,
			maximumSourcesPerRun,
			maximumSourcesPerManifest,
			maximumSourcesPerPlan,
			maximumPlanPayloadBytes,
			maximumWalPayloadBytes,
			maximumWalEvents,
			maximumAuthorizationMembers,
			maximumDirectDemands,
			maximumNonterminalRegistrations,
		).forEach { limit -> require(limit in 1 until Int.MAX_VALUE) }
		require(maximumLogicalFacts <= maximumRevisions)
	}
}

enum class CellCapturedRetentionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	STALE_REQUEST,
}

sealed interface CellCapturedRetentionResult {
	data class Pruned(
		val logicalFactCount: Int,
		val revisionCount: Int,
	) : CellCapturedRetentionResult

	data object NoChange : CellCapturedRetentionResult

	data class Blocked(
		val reason: CellCapturedRetentionBlockedReason,
	) : CellCapturedRetentionResult
}

enum class CellCapturedSourceDeletionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	POLICY_AUTHORITY_UNAVAILABLE,
	CAPTURE_CONSENT_STILL_ELIGIBLE,
	DIRECT_DEMAND_NOT_QUIESCED,
	CAPTURE_PROVIDER_NOT_QUIESCED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	DELETION_FENCE_CONFLICT,
	STALE_REQUEST,
}

sealed interface CellCapturedSourceDeletionResult {
	data class Deleted(
		val logicalFactCount: Int,
		val revisionCount: Int,
		val fencedServiceRunCount: Int,
	) : CellCapturedSourceDeletionResult

	data object AlreadyDeleted : CellCapturedSourceDeletionResult

	data class Blocked(
		val reason: CellCapturedSourceDeletionBlockedReason,
	) : CellCapturedSourceDeletionResult
}

/**
 * Removes complete authenticated Cell correction/dependency closures affected by the global floor.
 *
 * A Cell fact covers an elapsed-time interval anchored by an uncertain wall time. If the oldest
 * possible covered wall time crosses [beforeMs], the complete correction and aggregate dependency
 * component is selected. Retention therefore never splits an owner from any coverage-only
 * dependent. The global retained floor remains the durable replay fence; WAL and provider/control
 * state are untouched.
 */
suspend fun AppDatabase.pruneCapturedCellFactsAffectedByRetentionFloor(
	beforeMs: Long,
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	markedAtMs: Long,
): CellCapturedRetentionResult = pruneCapturedCellFactsAffectedByRetentionFloor(
	beforeMs,
	expectedCollectedDataEpoch,
	expectedDeletedSourceEventHighWaterOrdinal,
	markedAtMs,
	DEFAULT_CELL_CAPTURED_MAINTENANCE_LIMITS,
	{ currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.pruneCapturedCellFactsAffectedByRetentionFloor(
	beforeMs: Long,
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	markedAtMs: Long,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedRetentionResult {
	require(beforeMs >= 0L)
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
	require(markedAtMs >= 0L)
	return try {
		withTransaction {
			checkpoint(CellCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
			val state = sourceEvidenceStateDao().get()
			if (state == null || state.collectedDataEpoch != expectedCollectedDataEpoch ||
				state.deletedSourceEventHighWaterOrdinal != expectedDeletedSourceEventHighWaterOrdinal ||
				state.retainedFromMs != beforeMs
			) block(CellCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
			val audit = auditCapturedCellFacts(state, limits, checkpoint)
			if (markedAtMs < audit.latestDurableTimeMs) {
				block(CellCapturedRetentionBlockedReason.STALE_REQUEST)
			}
			val selectedIds = affectedDependencyClosure(audit.lineages, beforeMs)
			if (selectedIds.isEmpty()) return@withTransaction CellCapturedRetentionResult.NoChange
			val selected = selectedIds.map { logicalFactId ->
				audit.lineagesById.getValue(logicalFactId)
			}
			val retained = audit.lineages.filterNot { lineage -> lineage.logicalFactId in selectedIds }
			if (retained.any { lineage ->
				lineage.aggregateOwnerLogicalFactId in selectedIds
			}) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

			val dao = cellCapturedFactDao()
			var removedRevisions = 0
			selected.chunked(DELETE_BATCH_SIZE).forEach { batch ->
				currentCoroutineContext().ensureActive()
				val ids = batch.map(CellCapturedLineage::logicalFactId)
				if (dao.deleteExactCursors(WRITER_ID, WRITER_VERSION, ids) != batch.size) {
					block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				val expectedRevisions = batch.sumOf { lineage -> lineage.revisions.size }
				val deletedRevisions = dao.deleteExactRevisionLineages(WRITER_ID, WRITER_VERSION, ids)
				if (deletedRevisions != expectedRevisions) {
					block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				removedRevisions = Math.addExact(removedRevisions, deletedRevisions)
				checkpoint(CellCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
			}
			check(sourceEvidenceStateDao().incrementRevision(markedAtMs) == 1) {
				"Unable to publish captured Cell retention"
			}
			CellCapturedRetentionResult.Pruned(selected.size, removedRevisions)
		}
	} catch (blocked: CellCapturedRetentionBlockedException) {
		CellCapturedRetentionResult.Blocked(blocked.reason)
	} catch (@Suppress("SwallowedException") _: CellCapturedMaintenanceLimitExceeded) {
		CellCapturedRetentionResult.Blocked(CellCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		CellCapturedRetentionResult.Blocked(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

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
			checkpoint(CellCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
			val evidenceState = sourceEvidenceStateDao().get()
			if (evidenceState == null || evidenceState.collectedDataEpoch != expectedCollectedDataEpoch ||
				evidenceState.deletedSourceEventHighWaterOrdinal !=
					expectedDeletedSourceEventHighWaterOrdinal
			) block(CellCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)

			val policyAuthority = sourcePolicyDao().authority()
			val policy = policyAuthority?.takeIf { authority ->
				authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
			}?.let { authority ->
				sourcePolicyDao().policyAtRevision(authority.currentPolicyRevision, CELL_SOURCE)
			}
			val revokedConsent = sourcePolicyDao().latestConsentEpoch(CELL_SOURCE, CAPTURE_PURPOSE)
			if (policy == null || revokedConsent == null ||
				revokedConsent.epoch != expectedRevokedConsentEpoch
			) block(CellCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
			if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null ||
				revokedConsent.eligible || revokedConsent.persistenceEligible ||
				revokedConsent.policyRevision != policy.policyRevision ||
				revokedConsent.effectiveBootId != policy.effectiveBootId ||
				revokedConsent.effectiveElapsedRealtimeNanos != policy.effectiveElapsedRealtimeNanos ||
				revokedConsent.effectiveWallTimeMs != policy.effectiveWallTimeMs
			) block(CellCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
			if (deletedAtMs < maxOf(
				policyAuthority.updatedAtMs,
				policy.effectiveWallTimeMs,
				revokedConsent.effectiveWallTimeMs,
				evidenceState.updatedAtMs,
			)) block(CellCapturedSourceDeletionBlockedReason.STALE_REQUEST)

			val dao = cellCapturedFactDao()
			val demands = dao.directCellDemandsForDeletion(
				CELL_SOURCE,
				limits.maximumDirectDemands + 1,
			)
			if (demands.size > limits.maximumDirectDemands) throw CellCapturedMaintenanceLimitExceeded()
			if (demands.isNotEmpty()) {
				block(CellCapturedSourceDeletionBlockedReason.DIRECT_DEMAND_NOT_QUIESCED)
			}
			val registrations = dao.cellRegistrationsForDeletion(
				CELL_SOURCE,
				limits.maximumNonterminalRegistrations + 1,
			)
			if (registrations.size > limits.maximumNonterminalRegistrations) {
				throw CellCapturedMaintenanceLimitExceeded()
			}
			if (registrations.isNotEmpty()) {
				block(CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
			}

			val audit = auditCapturedCellFacts(evidenceState, limits, checkpoint)
			val walAudit = loadCapturedCellWalScopesForDeletion(evidenceState, limits)
			if (walAudit.scopes.isNotEmpty()) {
				val owner = sourceDestinationOwnerDao().get(
					CELL_SOURCE,
					SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
				)
				if (owner == null || owner.owner !=
					SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS ||
					owner.ownerGeneration != SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
				) block(CellCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED)
			}
			if (deletedAtMs < maxOf(audit.latestDurableTimeMs, walAudit.latestDurableTimeMs)) {
				block(CellCapturedSourceDeletionBlockedReason.STALE_REQUEST)
			}
			val scopes = (audit.lineages.map(CellCapturedLineage::scope) + walAudit.scopes).distinct()
			val scopesNeedingFence = scopes.filter { scope -> audit.generationByScope[scope] == null }
			if (audit.lineages.isEmpty() && scopesNeedingFence.isEmpty()) {
				return@withTransaction CellCapturedSourceDeletionResult.AlreadyDeleted
			}
			for (scope in scopesNeedingFence) {
				currentCoroutineContext().ensureActive()
				val nextGeneration = 1L
				val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
					sourceKind = CELL_SOURCE,
					purpose = CAPTURE_PURPOSE,
					logicalTrackingId = scope.logicalTrackingId,
					serviceRunId = scope.serviceRunId,
					fenceGeneration = nextGeneration,
					collectedDataEpoch = expectedCollectedDataEpoch,
					deletedAtMs = deletedAtMs,
				)
				if (sourceDeletionFenceDao().insertIfAbsent(fence) == INSERT_IGNORED) {
					val retained = sourceDeletionFenceDao().get(
						fence.sourceKind,
						fence.purpose,
						fence.scopeKind,
						fence.scopeIdentityDigest,
					)
					if (retained != fence) {
						block(CellCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
					}
				}
				dao.insertDeletionGeneration(
					CellCaptureDeletionGenerationEntity(
						logicalTrackingId = scope.logicalTrackingId,
						serviceRunId = scope.serviceRunId,
						collectedDataEpoch = expectedCollectedDataEpoch,
						generation = nextGeneration,
						updatedAtMs = deletedAtMs,
					),
				)
			}
			checkpoint(CellCapturedMaintenanceCheckpoint.DELETION_FENCES_INSTALLED)

			val revisionCount = audit.lineages.sumOf { lineage -> lineage.revisions.size }
			audit.lineages.chunked(DELETE_BATCH_SIZE).forEach { batch ->
				currentCoroutineContext().ensureActive()
				val ids = batch.map(CellCapturedLineage::logicalFactId)
				if (dao.deleteExactCursors(WRITER_ID, WRITER_VERSION, ids) != batch.size ||
					dao.deleteExactRevisionLineages(WRITER_ID, WRITER_VERSION, ids) !=
						batch.sumOf { lineage -> lineage.revisions.size }
				) block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			if (dao.revisionCount() != 0L || dao.cursorCount() != 0L) {
				block(CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			check(sourceEvidenceStateDao().incrementRevision(deletedAtMs) == 1) {
				"Unable to publish captured Cell source deletion"
			}
			checkpoint(CellCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
			CellCapturedSourceDeletionResult.Deleted(
				logicalFactCount = audit.lineages.size,
				revisionCount = revisionCount,
				fencedServiceRunCount = scopesNeedingFence.size,
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

private suspend fun AppDatabase.auditCapturedCellFacts(
	evidenceState: SourceEvidenceState,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedFactAudit {
	val dao = cellCapturedFactDao()
	if (dao.unsupportedRevisionCount(WRITER_ID, WRITER_VERSION) != 0L ||
		dao.unsupportedCursorCount(WRITER_ID, WRITER_VERSION) != 0L
	) block(CellCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)

	val lineages = mutableListOf<CellCapturedLineage>()
	var afterLogicalFactId: String? = null
	var afterSemanticRevision: Long? = null
	var revisionCount = 0
	var current = mutableListOf<CellCapturedFactRevisionEntity>()
	while (true) {
		currentCoroutineContext().ensureActive()
		val page = dao.maintenanceRevisionPage(
			WRITER_ID,
			WRITER_VERSION,
			afterLogicalFactId,
			afterSemanticRevision,
			limits.revisionPageSize,
		)
		if (page.isEmpty()) break
		revisionCount = Math.addExact(revisionCount, page.size)
		if (revisionCount > limits.maximumRevisions) throw CellCapturedMaintenanceLimitExceeded()
		checkpoint(CellCapturedMaintenanceCheckpoint.REVISION_PAGE_LOADED)
		for (revision in page) {
			if (current.isNotEmpty() && current.last().logicalFactId != revision.logicalFactId) {
				lineages += authenticateCellCapturedLineage(current)
				current = mutableListOf()
				if (lineages.size > limits.maximumLogicalFacts) {
					throw CellCapturedMaintenanceLimitExceeded()
				}
			}
			current += revision
		}
		afterLogicalFactId = page.last().logicalFactId
		afterSemanticRevision = page.last().semanticRevision
		if (page.size < limits.revisionPageSize) break
	}
	if (current.isNotEmpty()) lineages += authenticateCellCapturedLineage(current)
	if (lineages.size > limits.maximumLogicalFacts) throw CellCapturedMaintenanceLimitExceeded()

	val owner = sourceDestinationOwnerDao().get(
		CELL_SOURCE,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
	)
	if (lineages.isNotEmpty() && (owner == null ||
		owner.owner != SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS ||
		owner.ownerGeneration != SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION)
	) block(CellCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED)

	val cursors = loadCellCapturedCursors(limits)
	val generations = loadCellDeletionGenerations(limits)
	if (dao.revisionCount() != revisionCount.toLong() ||
		dao.cursorCount() != cursors.size.toLong() ||
		dao.deletionGenerationCount() != generations.size.toLong()
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (cursors.size != lineages.size || cursors.keys != lineages.mapTo(mutableSetOf()) {
			lineage -> lineage.logicalFactId
		}
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val byId = lineages.associateBy(CellCapturedLineage::logicalFactId)
	if (byId.size != lineages.size) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val reuseAuthorityById = mutableMapOf<String, CellAggregateReuseAuthority>()
	val aggregateById = mutableMapOf<String, CellHistoricalIdentityFreeAggregate>()
	var latestWalTimeMs = 0L
	for (lineage in lineages) {
		currentCoroutineContext().ensureActive()
		val latest = lineage.revisions.last()
		val cursor = cursors[lineage.logicalFactId]
		if (cursor == null || !cursor.matchesCurrent(latest)) {
			block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		if (latest.collectedDataEpoch != evidenceState.collectedDataEpoch ||
			latest.sourceAdmissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
		) block(CellCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		val authenticated = authenticateCellCapturedAuthority(latest, limits)
		reuseAuthorityById[lineage.logicalFactId] = authenticated.reuseAuthority
		aggregateById[lineage.logicalFactId] = authenticated.aggregate
		latestWalTimeMs = maxOf(latestWalTimeMs, authenticated.walCreatedAtMs)
		checkpoint(CellCapturedMaintenanceCheckpoint.LINEAGE_AUTHENTICATED)
	}
	authenticateAggregateDependencies(lineages, byId, reuseAuthorityById, aggregateById)
	authenticateCellDeletionGenerations(lineages, generations, evidenceState.collectedDataEpoch)

	return CellCapturedFactAudit(
		lineages = lineages,
		lineagesById = byId,
		generationByScope = generations.associateBy { generation ->
			CellCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
		},
		latestDurableTimeMs = maxOf(
			evidenceState.updatedAtMs,
			owner?.updatedAtMs ?: 0L,
			latestWalTimeMs,
			lineages.maxOfOrNull { lineage -> lineage.revisions.maxOf { it.appliedAtMs } } ?: 0L,
			cursors.values.maxOfOrNull(CellCapturedFactCursorEntity::updatedAtMs) ?: 0L,
			generations.maxOfOrNull(CellCaptureDeletionGenerationEntity::updatedAtMs) ?: 0L,
		),
	)
}

private fun authenticateCellCapturedLineage(
	revisions: List<CellCapturedFactRevisionEntity>,
): CellCapturedLineage {
	if (revisions.isEmpty()) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val first = revisions.first()
	for ((index, revision) in revisions.withIndex()) {
		val semanticRevision = index + 1L
		val previous = revisions.getOrNull(index - 1)
		if (revision.semanticRevision != semanticRevision ||
			revision.supersedesSemanticRevision != semanticRevision.takeIf { it > 1L }?.minus(1L) ||
			revision.logicalFactId != CellCapturedFactRevisionIntegrity.logicalFactId(
				revision.sourceDeliveryIdentity,
				revision.logicalTrackingId,
				revision.serviceRunId,
				revision.sessionSegmentId,
				revision.manifestRevision,
				revision.collectedDataEpoch,
				revision.scopeDeletionGeneration,
			) || revision.mutationId != CellCapturedFactRevisionIntegrity.mutationId(
				revision.logicalFactId,
				revision.semanticRevision,
			) || !CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(revision) ||
			!revision.hasSameCorrectionEffect(first) ||
			(previous != null && !revision.isExactSettlementSuccessorOf(previous))
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return CellCapturedLineage(
		logicalFactId = first.logicalFactId,
		revisions = revisions,
		earliestPossibleWallTimeMs = earliestCoveredWallTime(
			first.observedWallTimeMs,
			first.wallTimeUncertaintyMs,
			first.observedElapsedNanos,
			first.coverageIntervalStartNanos,
		),
		aggregateOwnerLogicalFactId = first.aggregateOwnerLogicalFactId,
		aggregateOwnerSemanticRevision = first.aggregateOwnerSemanticRevision,
	)
}

private suspend fun AppDatabase.authenticateCellCapturedAuthority(
	revision: CellCapturedFactRevisionEntity,
	limits: CellCapturedMaintenanceLimits,
): AuthenticatedCellFactAuthority {
	val walPayloadBytes = cellCapturedFactDao().maintenanceWalPayloadByteCount(revision.sourceEventId)
	if (walPayloadBytes == null || walPayloadBytes > limits.maximumWalPayloadBytes) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val wal = sourceEventWalDao().getByEventId(revision.sourceEventId)
	if (wal == null || !wal.hasQualifiedIntegrity() || wal.sourceKind != CELL_SOURCE ||
		wal.admissionOrdinal != revision.sourceAdmissionOrdinal ||
		wal.deliveryIdentity != revision.sourceDeliveryIdentity ||
		wal.deliveryUnitIndex != revision.deliveryUnitIndex ||
		wal.deliveryUnitCount != revision.deliveryUnitCount ||
		wal.logicalTrackingId != revision.logicalTrackingId ||
		wal.serviceRunId != revision.serviceRunId || wal.sourceInstanceId != revision.sourceInstanceId ||
		wal.registrationGeneration != revision.registrationGeneration ||
		wal.physicalConfigurationFingerprint != revision.physicalConfigurationFingerprint ||
		wal.authorizationRevision != revision.authorizationRevision ||
		wal.authorizationPurposeEligibilityMask != revision.purposeEligibilityMask ||
		wal.authorizationFingerprint != revision.authorizationFingerprint ||
		wal.sourceSequence != revision.sourceSequence ||
		wal.configRevision != revision.configurationRevision ||
		wal.planAttribution != CAPTURED_REGISTRATION_PLAN_ATTRIBUTION ||
		wal.clockDomainId != revision.clockDomainId ||
		wal.observedElapsedNanos != revision.observedElapsedNanos ||
		wal.observedIntervalStartNanos != revision.observedIntervalStartNanos ||
		wal.receivedElapsedNanos != revision.receivedElapsedNanos ||
		wal.wallTimeMs != revision.observedWallTimeMs ||
		wal.wallTimeUncertaintyMs != revision.wallTimeUncertaintyMs ||
		wal.capturedCollectedDataEpoch != revision.collectedDataEpoch ||
		wal.sourcePolicyRevision != revision.sourcePolicyRevision ||
		wal.captureConsentEpoch != revision.captureConsentEpoch ||
		wal.sessionManifestRevision != revision.manifestRevision ||
		wal.lifecycleLeaseGeneration != revision.lifecycleLeaseGeneration ||
		wal.acquiredAtMs != revision.acquiredAtMs || wal.qualityFlags != revision.qualityFlags ||
		wal.qualityConfidence != revision.qualityConfidence || wal.payloadVersion != revision.payloadVersion ||
		wal.payloadChecksum != revision.payloadChecksum ||
		wal.integrityIdentity != revision.walIntegrityIdentity || wal.createdAtMs != revision.createdAtMs
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val decodedPayload = wal.decodeCanonicalCellPayload()
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val canonicalDeliveryIdentity = runCatching {
		canonicalCellProviderDeliveryIdentity(
			wal.clockDomainId,
			decodedPayload.map(CellHistoricalObservation::toProviderDeliveryIdentityFact),
		)
	}.getOrNull() ?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (canonicalDeliveryIdentity != revision.sourceDeliveryIdentity) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val payloadEffect = wal.canonicalCellPayloadEffect(revision, decodedPayload)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (!revision.matchesCellPayloadEffect(payloadEffect)) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val sessionDao = sourceSessionDao()
	val run = sessionDao.serviceRun(revision.serviceRunId)
	val session = sessionDao.session(revision.logicalTrackingId)
	val segment = sessionSegmentDao().getById(revision.sessionSegmentId)
	if (run == null || session == null || segment == null ||
		!session.hasValidHistoricalCellShape(revision.clockDomainId) ||
		!run.hasValidHistoricalCellShape(session) ||
		run.logicalTrackingId != revision.logicalTrackingId ||
		run.sessionSegmentId != revision.sessionSegmentId ||
		run.bootId != revision.clockDomainId || run.leaseGeneration != revision.lifecycleLeaseGeneration ||
		run.startedElapsedNanos != revision.deletionEffectStartNanos ||
		segment.logicalTrackingId != revision.logicalTrackingId ||
		segment.serviceRunId != revision.serviceRunId ||
		(session.finalAdmissionOrdinal != null &&
			revision.sourceAdmissionOrdinal > session.finalAdmissionOrdinal) ||
		session.clockDomainId != revision.clockDomainId ||
		session.lifecycleLeaseGeneration < revision.lifecycleLeaseGeneration ||
		(session.cutoffAtMs == null) != (session.cutoffElapsedNanos == null) ||
		(session.completedAtMs != null && session.cutoffElapsedNanos == null)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val sessionEnd = session.cutoffElapsedNanos ?: Long.MAX_VALUE
	if (!sessionEnd.isExactSettlementOf(revision.deletionEffectEndNanos)) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val manifests = sessionDao.manifestsForServiceRun(
		revision.serviceRunId,
		limits.maximumManifestsPerRun + 1,
	)
	val sources = trackingHistoryReadDao().manifestSources(
		listOf(revision.serviceRunId),
		limits.maximumSourcesPerRun + 1,
	)
	if (manifests.size > limits.maximumManifestsPerRun ||
		sources.size > limits.maximumSourcesPerRun ||
		!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests) ||
		manifests.any { manifest ->
			!SessionManifestIntegrity.verify(
				manifest,
				sources.filter { source ->
					source.logicalTrackingId == manifest.logicalTrackingId &&
						source.manifestRevision == manifest.manifestRevision
				},
			)
		}
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifestIndex = manifests.indexOfFirst { manifest ->
		manifest.logicalTrackingId == revision.logicalTrackingId &&
			manifest.manifestRevision == revision.manifestRevision
	}
	if (manifestIndex < 0) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifest = manifests[manifestIndex]
	val membership = sources.filter { source ->
		source.logicalTrackingId == revision.logicalTrackingId &&
			source.manifestRevision == revision.manifestRevision
	}
	if (membership.size > limits.maximumSourcesPerManifest) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val binding = membership.singleOrNull { source ->
		source.sourceKind == CELL_SOURCE && source.purpose == CAPTURE_PURPOSE
	}
	val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
		?: minOf(
			sessionEnd,
			revision.providerAcceptanceEndNanos,
			revision.authorizationEffectEndNanos,
		)
	if (binding == null || !binding.isExactCapturedCellBinding(revision) ||
		manifest.serviceRunId != revision.serviceRunId ||
		manifest.sourcePolicyRevision != revision.sourcePolicyRevision ||
		manifest.acquisitionPlanRevision != revision.configurationRevision ||
		manifest.effectiveBootId != revision.clockDomainId ||
		manifest.effectiveElapsedRealtimeNanos != revision.sessionRunEffectStartNanos ||
		!manifestEnd.isExactSettlementOf(revision.sessionRunEffectEndNanos) ||
		manifest.zoneId != revision.storedZoneId || !hasValidZone(revision.storedZoneId)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val plan = authenticateHistoricalCellPlan(
		revision.configurationRevision,
		revision.sourcePolicyRevision,
		revision.physicalConfigurationFingerprint,
		limits,
	)
	if (revision.maximumObservationAgeNanos != plan.maximumObservationAgeNanos()) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val policy = sourcePolicyDao().policyAtRevision(revision.sourcePolicyRevision, CELL_SOURCE)
	val consent = sourcePolicyDao().consentEpoch(
		CELL_SOURCE,
		CAPTURE_PURPOSE,
		revision.captureConsentEpoch,
	)
	if (policy == null || consent == null || !policy.enabled || !policy.capturePersistenceEligible ||
		policy.captureConsentEpoch != revision.captureConsentEpoch || policy.qosCode != binding.qosCode ||
		policy.effectiveBootId != revision.clockDomainId ||
		policy.effectiveElapsedRealtimeNanos > revision.coverageIntervalStartNanos ||
		!consent.eligible || !consent.persistenceEligible ||
		consent.policyRevision > revision.sourcePolicyRevision ||
		consent.effectiveBootId != revision.clockDomainId ||
		consent.effectiveElapsedRealtimeNanos != revision.consentEffectStartNanos
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val brokerDao = sourceBrokerDao()
	val registration = brokerDao.registration(CELL_SOURCE, revision.registrationGeneration)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val registrationInterval = authenticateCellRegistration(
		registration = registration,
		expectedSourceInstanceId = revision.sourceInstanceId,
		expectedCollectedDataEpoch = revision.collectedDataEpoch,
		expectedClockDomainId = revision.clockDomainId,
		expectedPhysicalFingerprint = revision.physicalConfigurationFingerprint,
		expectedAuthorizationRevision = revision.authorizationRevision,
	)
	val registrationStart = registrationInterval.first
	val registrationEnd = registrationInterval.last
	if (registrationStart != revision.providerAcceptanceStartNanos ||
		!registrationEnd.isExactSettlementOf(revision.providerAcceptanceEndNanos)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val authorizationRows = boundedAuthorizationMembers(
		revision.registrationGeneration,
		revision.authorizationRevision,
		limits,
	)
	val authorization = authorizationRows.toAuthorizationSnapshotOrNull()
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val startAuthorization = boundedAuthorizationAt(
		revision.registrationGeneration,
		revision.clockDomainId,
		revision.coverageIntervalStartNanos,
		limits,
	)
	val endAuthorization = boundedAuthorizationAt(
		revision.registrationGeneration,
		revision.clockDomainId,
		revision.coverageIntervalEndNanos,
		limits,
	)
	val nextRows = cellCapturedFactDao().maintenanceNextAuthorizationMembers(
		CELL_SOURCE,
		revision.registrationGeneration,
		revision.authorizationRevision,
		limits.maximumAuthorizationMembers + 1,
	)
	if (nextRows.size > limits.maximumAuthorizationMembers) throw CellCapturedMaintenanceLimitExceeded()
	val nextAuthorization = if (nextRows.isEmpty()) null else nextRows.toAuthorizationSnapshotOrNull()
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (nextAuthorization != null &&
		(nextAuthorization.effectiveBootId != wal.clockDomainId ||
			nextAuthorization.effectiveElapsedRealtimeNanos <
				authorization.effectiveElapsedRealtimeNanos)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val authorizationEnd = minOf(
		registrationEnd,
		nextAuthorization?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE,
	)
	val captureMember = authorization.authorizedMembers.singleOrNull { member ->
		member.purpose == CAPTURE_PURPOSE && member.persistenceEligible &&
			member.logicalTrackingId == revision.logicalTrackingId &&
			member.serviceRunId == revision.serviceRunId &&
			member.manifestRevision == revision.manifestRevision &&
			member.lifecycleLeaseGeneration == revision.lifecycleLeaseGeneration &&
			member.sourcePolicyRevision == revision.sourcePolicyRevision &&
			member.consentEpoch == revision.captureConsentEpoch
	}
	val demandIds = authorization.authorizedMembers.mapNotNull { member -> member.demandId }
	val demands = brokerDao.demandsByIds(demandIds)
	val recomputed = runCatching {
		SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = revision.registrationGeneration,
			authorizationRevision = revision.authorizationRevision,
			demands = demands,
			effectiveBootId = authorization.effectiveBootId,
			effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
		)
	}.getOrNull()
	if (authorization.isDenied || captureMember == null ||
		startAuthorization != authorization || endAuthorization != authorization ||
		demandIds.distinct().size != authorization.authorizedMembers.size ||
		demands.size != authorization.authorizedMembers.size ||
		recomputed?.sortedBy { it.memberId } != authorization.members.sortedBy { it.memberId } ||
		authorization.authorizationFingerprint != revision.authorizationFingerprint ||
		authorization.purposeEligibilityMask != revision.purposeEligibilityMask ||
		authorization.effectiveBootId != revision.clockDomainId ||
		authorization.effectiveElapsedRealtimeNanos != revision.authorizationEffectStartNanos ||
		!authorizationEnd.isExactSettlementOf(revision.authorizationEffectEndNanos) ||
		!authorizationEnd.isExactSettlementOf(revision.consentEffectEndNanos)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val earliestWall = earliestCoveredWallTime(
		revision.observedWallTimeMs,
		revision.wallTimeUncertaintyMs,
		revision.observedElapsedNanos,
		revision.coverageIntervalStartNanos,
	)
	val latestWall = runCatching {
		Math.addExact(revision.observedWallTimeMs, revision.wallTimeUncertaintyMs)
	}.getOrNull() ?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val zone = runCatching { ZoneId.of(revision.storedZoneId) }.getOrNull()
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val firstDay = Instant.ofEpochMilli(earliestWall).atZone(zone).toLocalDate().toEpochDay()
	val lastDay = Instant.ofEpochMilli(latestWall).atZone(zone).toLocalDate().toEpochDay()
	if (firstDay != lastDay || firstDay != revision.structuralEpochDay ||
		earliestWall < segment.startTimeMs || latestWall > segment.endTimeMs ||
		revision.coverageIntervalStartNanos < registrationStart ||
		revision.coverageIntervalEndNanos >= registrationEnd ||
		revision.coverageIntervalEndNanos >= authorizationEnd ||
		revision.coverageIntervalEndNanos >= sessionEnd ||
		revision.coverageIntervalEndNanos >= manifestEnd
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val capturedSources = membership.filter { source ->
		source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && source.persistenceEligible
	}.mapTo(mutableSetOf(), SessionManifestSourceEntity::sourceKind)
	val controlSources = membership.filter { source ->
		source.purpose == SessionManifestPurposeCode.CONTROL
	}.mapTo(mutableSetOf(), SessionManifestSourceEntity::sourceKind)
	if (CELL_SOURCE !in capturedSources || capturedSources.intersect(controlSources).isNotEmpty()) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return AuthenticatedCellFactAuthority(
		reuseAuthority = CellAggregateReuseAuthority(
			logicalTrackingId = revision.logicalTrackingId,
			serviceRunId = revision.serviceRunId,
			sessionSegmentId = revision.sessionSegmentId,
			capturedSources = capturedSources,
			controlSources = controlSources,
			sourceInstanceId = revision.sourceInstanceId,
			registrationGeneration = revision.registrationGeneration,
			configurationRevision = revision.configurationRevision,
			physicalConfigurationFingerprint = revision.physicalConfigurationFingerprint,
			authorizationRevision = revision.authorizationRevision,
			authorizationFingerprint = revision.authorizationFingerprint,
			purposeEligibilityMask = revision.purposeEligibilityMask,
			sourcePolicyRevision = revision.sourcePolicyRevision,
			captureConsentEpoch = revision.captureConsentEpoch,
			manifestRevision = revision.manifestRevision,
			lifecycleLeaseGeneration = revision.lifecycleLeaseGeneration,
			collectedDataEpoch = revision.collectedDataEpoch,
			scopeDeletionGeneration = revision.scopeDeletionGeneration,
			clockDomainId = revision.clockDomainId,
			storedZoneId = revision.storedZoneId,
			structuralEpochDay = revision.structuralEpochDay,
			providerAcceptanceStartNanos = revision.providerAcceptanceStartNanos,
			providerAcceptanceEndNanos = revision.providerAcceptanceEndNanos,
			authorizationEffectStartNanos = revision.authorizationEffectStartNanos,
			authorizationEffectEndNanos = revision.authorizationEffectEndNanos,
			consentEffectStartNanos = revision.consentEffectStartNanos,
			consentEffectEndNanos = revision.consentEffectEndNanos,
			sessionRunEffectStartNanos = revision.sessionRunEffectStartNanos,
			sessionRunEffectEndNanos = revision.sessionRunEffectEndNanos,
			deletionEffectStartNanos = revision.deletionEffectStartNanos,
			deletionEffectEndNanos = revision.deletionEffectEndNanos,
			maximumObservationAgeNanos = revision.maximumObservationAgeNanos,
		),
		aggregate = payloadEffect.aggregate,
		walCreatedAtMs = wal.createdAtMs,
	)
}

private suspend fun AppDatabase.boundedAuthorizationMembers(
	registrationGeneration: Long,
	authorizationRevision: Long,
	limits: CellCapturedMaintenanceLimits,
) = cellCapturedFactDao().maintenanceAuthorizationMembers(
	CELL_SOURCE,
	registrationGeneration,
	authorizationRevision,
	limits.maximumAuthorizationMembers + 1,
).also { rows ->
	if (rows.size > limits.maximumAuthorizationMembers) throw CellCapturedMaintenanceLimitExceeded()
}

private suspend fun AppDatabase.boundedAuthorizationAt(
	registrationGeneration: Long,
	bootId: String,
	observedElapsedRealtimeNanos: Long,
	limits: CellCapturedMaintenanceLimits,
) = cellCapturedFactDao().maintenanceAuthorizationAt(
	CELL_SOURCE,
	registrationGeneration,
	bootId,
	observedElapsedRealtimeNanos,
	limits.maximumAuthorizationMembers + 1,
).also { rows ->
	if (rows.size > limits.maximumAuthorizationMembers) throw CellCapturedMaintenanceLimitExceeded()
}.toAuthorizationSnapshotOrNull()
	?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

private fun authenticateAggregateDependencies(
	lineages: List<CellCapturedLineage>,
	lineagesById: Map<String, CellCapturedLineage>,
	reuseAuthorityById: Map<String, CellAggregateReuseAuthority>,
	aggregateById: Map<String, CellHistoricalIdentityFreeAggregate>,
) {
	for (lineage in lineages) {
		val ownerId = lineage.aggregateOwnerLogicalFactId ?: continue
		val ownerRevision = lineage.aggregateOwnerSemanticRevision
			?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val owner = lineagesById[ownerId]
			?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val referenced = owner.revisions.singleOrNull { revision ->
			revision.semanticRevision == ownerRevision
		}
		val dependent = lineage.revisions.last()
		val ownerAuthority = reuseAuthorityById[owner.logicalFactId]
		val dependentAuthority = reuseAuthorityById[lineage.logicalFactId]
		val dependentAggregate = aggregateById[lineage.logicalFactId]
		if (referenced == null || owner.revisions.last() != referenced || owner.revisions.any { revision ->
				revision.factKind != CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE
			} || dependent.factKind != CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ||
			ownerAuthority == null || dependentAuthority == null ||
			ownerAuthority != dependentAuthority ||
			!referenced.hasFiniteAggregateReuseAuthority() ||
			dependentAggregate == null || !referenced.matchesIdentityFreeAggregate(dependentAggregate)
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.authenticateCellDeletionGenerations(
	lineages: List<CellCapturedLineage>,
	generations: List<CellCaptureDeletionGenerationEntity>,
	collectedDataEpoch: Long,
) {
	val scopesWithFacts = lineages.groupBy(CellCapturedLineage::scope)
	val generationByScope = generations.associateBy { generation ->
		CellCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
	}
	if (generationByScope.size != generations.size) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	for ((scope, scopedLineages) in scopesWithFacts) {
		val declaredGenerations = scopedLineages.map { lineage ->
			lineage.revisions.first().scopeDeletionGeneration
		}.distinct()
		if (declaredGenerations != listOf(0L) || generationByScope[scope] != null) {
			block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			CELL_SOURCE,
			CAPTURE_PURPOSE,
			scope.logicalTrackingId,
			scope.serviceRunId,
		)
		if (sourceDeletionFenceDao().contains(
				CELL_SOURCE,
				CAPTURE_PURPOSE,
				SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				digest,
			)
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	for (generation in generations) {
		val scope = CellCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
		if (generation.collectedDataEpoch != collectedDataEpoch || scope in scopesWithFacts) {
			block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val expectedFence = SourceDeletionFenceEntity.createLogicalServiceRun(
			CELL_SOURCE,
			CAPTURE_PURPOSE,
			generation.logicalTrackingId,
			generation.serviceRunId,
			generation.generation,
			generation.collectedDataEpoch,
			generation.updatedAtMs,
		)
		val retained = sourceDeletionFenceDao().get(
			expectedFence.sourceKind,
			expectedFence.purpose,
			expectedFence.scopeKind,
			expectedFence.scopeIdentityDigest,
		)
		if (retained != expectedFence) {
			block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
}

private fun affectedDependencyClosure(
	lineages: List<CellCapturedLineage>,
	beforeMs: Long,
): Set<String> {
	val selected = lineages.filter { lineage ->
		lineage.earliestPossibleWallTimeMs < beforeMs
	}.mapTo(mutableSetOf(), CellCapturedLineage::logicalFactId)
	if (selected.isEmpty()) return emptySet()
	val dependentsByOwner = lineages.filter { lineage -> lineage.aggregateOwnerLogicalFactId != null }
		.groupBy(CellCapturedLineage::aggregateOwnerLogicalFactId)
	val ownerByDependent = lineages.associate { lineage ->
		lineage.logicalFactId to lineage.aggregateOwnerLogicalFactId
	}
	var changed: Boolean
	do {
		changed = false
		for (lineageId in selected.toList()) {
			val ownerId = ownerByDependent[lineageId]
			if (ownerId != null && selected.add(ownerId)) changed = true
		}
		for (ownerId in selected.toList()) {
			for (dependent in dependentsByOwner[ownerId].orEmpty()) {
				if (selected.add(dependent.logicalFactId)) changed = true
			}
		}
	} while (changed)
	return selected
}

private suspend fun AppDatabase.authenticateHistoricalCellPlan(
	revision: Long,
	sourcePolicyRevision: Long,
	expectedPhysicalFingerprint: String,
	limits: CellCapturedMaintenanceLimits,
): CellHistoricalPlan {
	val planHeader = sourcePlanStateDao().revision(revision)
	val desiredPlans = cellCapturedFactDao().maintenanceDesiredPlans(
		revision,
		limits.maximumSourcesPerPlan + 1,
	)
	val desiredCell = desiredPlans.singleOrNull { plan -> plan.sourceKind == CELL_SOURCE }
	if (desiredPlans.size > limits.maximumSourcesPerPlan || planHeader == null || desiredCell == null ||
		planHeader.revision != revision || planHeader.planId.isBlank() || planHeader.createdAtMs < 0L ||
		planHeader.sourcePolicyRevision != sourcePolicyRevision ||
		desiredCell.revision != revision || desiredCell.payloadVersion != CELL_PLAN_PAYLOAD_VERSION ||
		desiredCell.payload.size > limits.maximumPlanPayloadBytes ||
		desiredCell.payloadChecksum != sha256(desiredCell.payload)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val plan = decodeCanonicalCellPlan(desiredCell.payload)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (plan.revision != revision || !plan.hasSupportedHistoricalShape() ||
		plan.physicalConfigurationFingerprint() != expectedPhysicalFingerprint
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	return plan
}

private suspend fun AppDatabase.authenticateCellRegistration(
	registration: ProviderRegistrationGenerationEntity,
	expectedSourceInstanceId: String,
	expectedCollectedDataEpoch: Long,
	expectedClockDomainId: String,
	expectedPhysicalFingerprint: String,
	expectedAuthorizationRevision: Long,
): LongRange {
	val acceptedAtMs = registration.acceptedAtMs
	val acceptedElapsedNanos = registration.acceptedElapsedRealtimeNanos
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val retiredAtMs = registration.retiredAtMs
	val retiredElapsedNanos = registration.retiredElapsedRealtimeNanos
	val maximumCaptureAuthorizationRevision = sourceBrokerDao().maximumCaptureAuthorizationRevision(
		CELL_SOURCE,
		registration.registrationGeneration,
	)
	// Cell closes callback intake before retiring the provider and persists the exact retirement
	// interval used below. Unlike Activity's registration arbiter, the Cell runtime does not publish
	// the broker's capture-authorization barrier field, so its production value remains zero. Treat
	// only a barrier beyond the retained authorization history as impossible; requiring equality here
	// would make every ordinary retired Cell registration unverifiable.
	val acceptedShape = when (registration.status) {
		ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
			retiredAtMs == null && retiredElapsedNanos == null && registration.failureCode == null
		ProviderRegistrationGenerationEntity.STATUS_RETIRING ->
			retiredAtMs != null && retiredElapsedNanos != null && !registration.failureCode.isNullOrBlank()
		ProviderRegistrationGenerationEntity.STATUS_RETIRED ->
			retiredAtMs != null && retiredElapsedNanos != null
		else -> false
	}
	if (!acceptedShape || registration.sourceKind != CELL_SOURCE ||
		registration.sourceInstanceId != expectedSourceInstanceId ||
		registration.ownerScope != "source-broker:$CELL_SOURCE" ||
		registration.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
		registration.providerProcessIncarnationId.isNullOrBlank() ||
		registration.physicalConfigurationFingerprint != expectedPhysicalFingerprint ||
		registration.collectedDataEpoch != expectedCollectedDataEpoch ||
		registration.clockDomainId != expectedClockDomainId || acceptedAtMs == null ||
		registration.reservedAtMs > acceptedAtMs ||
		registration.reservedElapsedRealtimeNanos > acceptedElapsedNanos ||
		(retiredAtMs != null && acceptedAtMs > retiredAtMs) ||
		(retiredElapsedNanos != null && acceptedElapsedNanos > retiredElapsedNanos) ||
		expectedAuthorizationRevision <= 0L ||
		expectedAuthorizationRevision > maximumCaptureAuthorizationRevision ||
		registration.captureCallbackBarrierAuthorizationRevision != 0L
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	return acceptedElapsedNanos..(retiredElapsedNanos ?: Long.MAX_VALUE)
}

private suspend fun AppDatabase.loadCellCapturedCursors(
	limits: CellCapturedMaintenanceLimits,
): Map<String, CellCapturedFactCursorEntity> {
	val rows = mutableListOf<CellCapturedFactCursorEntity>()
	var after: String? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumCursors - rows.size
		if (remaining <= 0) {
			if (cellCapturedFactDao().maintenanceCursorPage(WRITER_ID, WRITER_VERSION, after, 1)
					.isNotEmpty()
			) throw CellCapturedMaintenanceLimitExceeded()
			break
		}
		val pageSize = minOf(remaining, CURSOR_PAGE_SIZE)
		val page = cellCapturedFactDao().maintenanceCursorPage(
			WRITER_ID,
			WRITER_VERSION,
			after,
			pageSize,
		)
		if (page.isEmpty()) break
		if (page != page.sortedBy(CellCapturedFactCursorEntity::logicalFactId) ||
			page.distinctBy(CellCapturedFactCursorEntity::logicalFactId).size != page.size
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		rows += page
		after = page.last().logicalFactId
		if (page.size < pageSize) break
	}
	val result = rows.associateBy(CellCapturedFactCursorEntity::logicalFactId)
	if (result.size != rows.size) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	return result
}

private suspend fun AppDatabase.loadCellDeletionGenerations(
	limits: CellCapturedMaintenanceLimits,
): List<CellCaptureDeletionGenerationEntity> {
	val rows = mutableListOf<CellCaptureDeletionGenerationEntity>()
	var afterLogicalTrackingId: String? = null
	var afterServiceRunId: String? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumDeletionGenerations - rows.size
		if (remaining <= 0) {
			if (cellCapturedFactDao().maintenanceDeletionGenerationPage(
					afterLogicalTrackingId,
					afterServiceRunId,
					1,
				).isNotEmpty()
			) throw CellCapturedMaintenanceLimitExceeded()
			break
		}
		val pageSize = minOf(remaining, DELETION_GENERATION_PAGE_SIZE)
		val page = cellCapturedFactDao().maintenanceDeletionGenerationPage(
			afterLogicalTrackingId,
			afterServiceRunId,
			pageSize,
		)
		if (page.isEmpty()) break
		rows += page
		afterLogicalTrackingId = page.last().logicalTrackingId
		afterServiceRunId = page.last().serviceRunId
		if (page.size < pageSize) break
	}
	return rows
}

/**
 * Audits every retained current-epoch Cell WAL carrier without retaining its payload in memory.
 * Capture-authorized carriers keep a run fence discoverable even after product retention removed
 * their fact, which prevents a later writer replay from resurrecting deleted Cell history.
 */
private suspend fun AppDatabase.loadCapturedCellWalScopesForDeletion(
	evidenceState: SourceEvidenceState,
	limits: CellCapturedMaintenanceLimits,
): CellCapturedWalAudit {
	val dao = cellCapturedFactDao()
	val total = dao.maintenanceWalCount(CELL_SOURCE)
	if (total > limits.maximumWalEvents) throw CellCapturedMaintenanceLimitExceeded()
	val scopes = linkedSetOf<CellCapturedRunScope>()
	var afterAdmissionOrdinal = 0L
	var loaded = 0
	var latestDurableTimeMs = 0L
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumWalEvents - loaded
		if (remaining <= 0) break
		val pageSize = minOf(remaining, WAL_PAGE_SIZE)
		val keys = dao.maintenanceWalKeys(CELL_SOURCE, afterAdmissionOrdinal, pageSize)
		if (keys.isEmpty()) break
		if (keys != keys.sortedBy { key -> key.admissionOrdinal } ||
			keys.any { key -> key.admissionOrdinal <= afterAdmissionOrdinal } ||
			keys.distinctBy { key -> key.admissionOrdinal }.size != keys.size
		) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		for (key in keys) {
			currentCoroutineContext().ensureActive()
			val payloadBytes = dao.maintenanceWalPayloadByteCount(key.eventId)
			if (payloadBytes == null || payloadBytes > limits.maximumWalPayloadBytes) {
				block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			val wal = sourceEventWalDao().getByEventId(key.eventId)
			if (wal == null || wal.admissionOrdinal != key.admissionOrdinal ||
				wal.sourceKind != CELL_SOURCE || !wal.hasQualifiedIntegrity()
			) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			latestDurableTimeMs = maxOf(latestDurableTimeMs, wal.createdAtMs)
			if (wal.capturedCollectedDataEpoch != evidenceState.collectedDataEpoch ||
				wal.admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
			) continue
			val captureFields = listOf(
				wal.logicalTrackingId,
				wal.serviceRunId,
				wal.configRevision,
				wal.physicalConfigurationFingerprint,
				wal.authorizationRevision,
				wal.authorizationFingerprint,
				wal.sourcePolicyRevision,
				wal.captureConsentEpoch,
				wal.sessionManifestRevision,
				wal.lifecycleLeaseGeneration,
			)
			val captureEligible = wal.authorizationPurposeEligibilityMask and
				SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
			if (!captureEligible && captureFields.all { field -> field == null }) continue
			if (!captureEligible || captureFields.any { field -> field == null } ||
				wal.logicalTrackingId.isNullOrBlank() || wal.serviceRunId.isNullOrBlank() ||
				wal.deliveryIdentity?.matches(LOWERCASE_SHA_256) != true ||
				wal.deliveryUnitIndex != 0 || wal.deliveryUnitCount != 1 || wal.sourceSequence <= 0L ||
				wal.configRevision!! <= 0L || wal.authorizationRevision!! <= 0L ||
				wal.sourcePolicyRevision!! <= 0L || wal.captureConsentEpoch!! < 0L ||
				wal.sessionManifestRevision!! <= 0L || wal.lifecycleLeaseGeneration!! <= 0L ||
				wal.planAttribution != CAPTURED_REGISTRATION_PLAN_ATTRIBUTION ||
				wal.activityAutomationEpoch != null
			) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			scopes += authenticateCapturedCellWalScopeForDeletion(wal, limits)
		}
		loaded = Math.addExact(loaded, keys.size)
		afterAdmissionOrdinal = keys.last().admissionOrdinal
		if (keys.size < pageSize) break
	}
	if (loaded.toLong() != total) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return CellCapturedWalAudit(scopes, latestDurableTimeMs)
}

/**
 * Proves that a retained WAL-only scope was genuinely capture-authorized before it can receive a
 * no-resurrection fence. This deliberately does not decode provider payload: deletion neither
 * materializes nor classifies it, but it must not trust caller-shaped scope scalars either.
 */
@Suppress("ComplexCondition", "LongMethod")
private suspend fun AppDatabase.authenticateCapturedCellWalScopeForDeletion(
	wal: SourceEventWalEntity,
	limits: CellCapturedMaintenanceLimits,
): CellCapturedRunScope {
	val logicalTrackingId = requireNotNull(wal.logicalTrackingId)
	val serviceRunId = requireNotNull(wal.serviceRunId)
	val configurationRevision = requireNotNull(wal.configRevision)
	val policyRevision = requireNotNull(wal.sourcePolicyRevision)
	val consentEpoch = requireNotNull(wal.captureConsentEpoch)
	val manifestRevision = requireNotNull(wal.sessionManifestRevision)
	val leaseGeneration = requireNotNull(wal.lifecycleLeaseGeneration)
	val physicalFingerprint = requireNotNull(wal.physicalConfigurationFingerprint)
	val authorizationRevision = requireNotNull(wal.authorizationRevision)
	val authorizationFingerprint = requireNotNull(wal.authorizationFingerprint)
	val observedStart = requireNotNull(wal.observedIntervalStartNanos)
	if (observedStart < 0L || wal.observedElapsedNanos < observedStart ||
		wal.receivedElapsedNanos < wal.observedElapsedNanos || wal.wallTimeMs == null ||
		wal.wallTimeMs < 0L || wal.wallTimeUncertaintyMs == null || wal.wallTimeUncertaintyMs < 0L
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val sequenceRow = sourceEventWalDao().getBySourceSequence(
		CELL_SOURCE,
		wal.sourceInstanceId,
		wal.sourceSequence,
	)
	val completeDelivery = sourceEventWalDao().deliveryUnitsBounded(
		CELL_SOURCE,
		wal.capturedCollectedDataEpoch,
		wal.clockDomainId,
		requireNotNull(wal.deliveryIdentity),
		2,
	).singleOrNull()
	if (sequenceRow?.eventId != wal.eventId || sequenceRow.admissionOrdinal != wal.admissionOrdinal ||
		sequenceRow.integrityIdentity != wal.integrityIdentity || completeDelivery == null ||
		completeDelivery.eventId != wal.eventId ||
		completeDelivery.admissionOrdinal != wal.admissionOrdinal ||
		completeDelivery.deliveryUnitIndex != 0 || completeDelivery.deliveryUnitCount != 1 ||
		completeDelivery.sourceInstanceId != wal.sourceInstanceId ||
		completeDelivery.registrationGeneration != wal.registrationGeneration ||
		completeDelivery.physicalConfigurationFingerprint != physicalFingerprint ||
		completeDelivery.authorizationRevision != authorizationRevision ||
		completeDelivery.observedElapsedNanos != wal.observedElapsedNanos ||
		completeDelivery.observedIntervalStartNanos != observedStart ||
		completeDelivery.payloadVersion != wal.payloadVersion ||
		completeDelivery.payloadChecksum != wal.payloadChecksum
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	authenticateHistoricalCellPlan(
		configurationRevision,
		policyRevision,
		physicalFingerprint,
		limits,
	)

	val policy = sourcePolicyDao().policyAtRevision(policyRevision, CELL_SOURCE)
	val consent = sourcePolicyDao().consentEpoch(CELL_SOURCE, CAPTURE_PURPOSE, consentEpoch)
	if (policy == null || consent == null || !policy.enabled || !policy.capturePersistenceEligible ||
		policy.captureConsentEpoch != consentEpoch || policy.effectiveBootId != wal.clockDomainId ||
		policy.effectiveElapsedRealtimeNanos > observedStart || !consent.eligible ||
		!consent.persistenceEligible || consent.policyRevision > policyRevision ||
		consent.effectiveBootId != wal.clockDomainId ||
		consent.effectiveElapsedRealtimeNanos > observedStart
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val registration = sourceBrokerDao().registration(CELL_SOURCE, wal.registrationGeneration)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val registrationInterval = authenticateCellRegistration(
		registration = registration,
		expectedSourceInstanceId = wal.sourceInstanceId,
		expectedCollectedDataEpoch = wal.capturedCollectedDataEpoch,
		expectedClockDomainId = wal.clockDomainId,
		expectedPhysicalFingerprint = physicalFingerprint,
		expectedAuthorizationRevision = authorizationRevision,
	)
	val registrationStart = registrationInterval.first
	val registrationEnd = registrationInterval.last
	if (observedStart < registrationStart || wal.observedElapsedNanos >= registrationEnd) {
		block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val authorizationRows = boundedAuthorizationMembers(
		wal.registrationGeneration,
		authorizationRevision,
		limits,
	)
	val authorization = authorizationRows.toAuthorizationSnapshotOrNull()
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val startAuthorization = boundedAuthorizationAt(
		wal.registrationGeneration,
		wal.clockDomainId,
		observedStart,
		limits,
	)
	val endAuthorization = boundedAuthorizationAt(
		wal.registrationGeneration,
		wal.clockDomainId,
		wal.observedElapsedNanos,
		limits,
	)
	val nextRows = cellCapturedFactDao().maintenanceNextAuthorizationMembers(
		CELL_SOURCE,
		wal.registrationGeneration,
		authorizationRevision,
		limits.maximumAuthorizationMembers + 1,
	)
	if (nextRows.size > limits.maximumAuthorizationMembers) throw CellCapturedMaintenanceLimitExceeded()
	val nextAuthorization = if (nextRows.isEmpty()) null else nextRows.toAuthorizationSnapshotOrNull()
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (nextAuthorization != null &&
		(nextAuthorization.effectiveBootId != wal.clockDomainId ||
			nextAuthorization.effectiveElapsedRealtimeNanos <
				authorization.effectiveElapsedRealtimeNanos)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val authorizationEnd = minOf(
		registrationEnd,
		nextAuthorization?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE,
	)
	val captureMember = authorization.authorizedMembers.singleOrNull { member ->
		member.purpose == CAPTURE_PURPOSE && member.persistenceEligible &&
			member.logicalTrackingId == logicalTrackingId && member.serviceRunId == serviceRunId &&
			member.manifestRevision == manifestRevision &&
			member.lifecycleLeaseGeneration == leaseGeneration &&
			member.sourcePolicyRevision == policyRevision && member.consentEpoch == consentEpoch
	}
	val demandIds = authorization.authorizedMembers.mapNotNull { member -> member.demandId }
	val demands = sourceBrokerDao().demandsByIds(demandIds)
	val recomputed = runCatching {
		SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = wal.registrationGeneration,
			authorizationRevision = authorizationRevision,
			demands = demands,
			effectiveBootId = authorization.effectiveBootId,
			effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
		)
	}.getOrNull()
	if (authorization.isDenied || captureMember == null || startAuthorization != authorization ||
		endAuthorization != authorization || demandIds.distinct().size != authorization.authorizedMembers.size ||
		demands.size != authorization.authorizedMembers.size ||
		recomputed?.sortedBy { it.memberId } != authorization.members.sortedBy { it.memberId } ||
		authorization.authorizationFingerprint != authorizationFingerprint ||
		authorization.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask ||
		authorization.effectiveBootId != wal.clockDomainId ||
		authorization.effectiveElapsedRealtimeNanos > observedStart ||
		wal.observedElapsedNanos >= authorizationEnd
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val sessionDao = sourceSessionDao()
	val run = sessionDao.serviceRun(serviceRunId)
	val session = sessionDao.session(logicalTrackingId)
	if (run == null || session == null || run.logicalTrackingId != logicalTrackingId ||
		!session.hasValidHistoricalCellShape(wal.clockDomainId) ||
		!run.hasValidHistoricalCellShape(session) ||
		run.bootId != wal.clockDomainId || run.leaseGeneration != leaseGeneration ||
		session.clockDomainId != wal.clockDomainId || session.lifecycleLeaseGeneration < leaseGeneration ||
		run.startedElapsedNanos > observedStart ||
		(session.finalAdmissionOrdinal != null && wal.admissionOrdinal > session.finalAdmissionOrdinal) ||
		(session.cutoffAtMs == null) != (session.cutoffElapsedNanos == null) ||
		wal.observedElapsedNanos >= (session.cutoffElapsedNanos ?: Long.MAX_VALUE)
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifests = sessionDao.manifestsForServiceRun(serviceRunId, limits.maximumManifestsPerRun + 1)
	val sources = trackingHistoryReadDao().manifestSources(
		listOf(serviceRunId),
		limits.maximumSourcesPerRun + 1,
	)
	if (manifests.size > limits.maximumManifestsPerRun || sources.size > limits.maximumSourcesPerRun ||
		!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests) ||
		manifests.any { manifest ->
			!SessionManifestIntegrity.verify(
				manifest,
				sources.filter { source ->
					source.logicalTrackingId == manifest.logicalTrackingId &&
						source.manifestRevision == manifest.manifestRevision
				},
			)
		}
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifestIndex = manifests.indexOfFirst { manifest ->
		manifest.logicalTrackingId == logicalTrackingId &&
			manifest.manifestRevision == manifestRevision
	}
	if (manifestIndex < 0) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifest = manifests[manifestIndex]
	val membership = sources.filter { source ->
		source.logicalTrackingId == logicalTrackingId && source.manifestRevision == manifestRevision
	}
	if (membership.size > limits.maximumSourcesPerManifest) throw CellCapturedMaintenanceLimitExceeded()
	val binding = membership.singleOrNull { source ->
		source.sourceKind == CELL_SOURCE && source.purpose == CAPTURE_PURPOSE
	}
	val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
		?: minOf(session.cutoffElapsedNanos ?: Long.MAX_VALUE, registrationEnd, authorizationEnd)
	val segmentId = run.sessionSegmentId
	val segment = segmentId?.let { sessionSegmentDao().getById(it) }
	val wallInterval = providerWallInterval(wal)
		?: block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (binding == null || !binding.isExactCapturedCellWalBinding(consentEpoch) ||
		manifest.serviceRunId != serviceRunId || manifest.sourcePolicyRevision != policyRevision ||
		manifest.acquisitionPlanRevision != configurationRevision ||
		manifest.effectiveBootId != wal.clockDomainId ||
		manifest.effectiveElapsedRealtimeNanos > observedStart ||
		wal.observedElapsedNanos >= manifestEnd || manifest.zoneId.isBlank() ||
		!hasValidZone(manifest.zoneId) || policy.qosCode != binding.qosCode || segment == null ||
		segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId ||
		wallInterval.first < segment.startTimeMs || wallInterval.last > segment.endTimeMs
	) block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	return CellCapturedRunScope(logicalTrackingId, serviceRunId)
}

@Suppress("ComplexCondition")
private fun CellCapturedFactCursorEntity.matchesCurrent(
	revision: CellCapturedFactRevisionEntity,
): Boolean = logicalFactId == revision.logicalFactId &&
	logicalTrackingId == revision.logicalTrackingId && serviceRunId == revision.serviceRunId &&
	sessionSegmentId == revision.sessionSegmentId &&
	writerOwnerGeneration == revision.writerOwnerGeneration &&
	collectedDataEpoch == revision.collectedDataEpoch &&
	scopeDeletionGeneration == revision.scopeDeletionGeneration &&
	latestSemanticRevision == revision.semanticRevision && latestMutationId == revision.mutationId &&
	latestEffectChecksum == revision.effectChecksum &&
	latestSourceAdmissionOrdinal == revision.sourceAdmissionOrdinal &&
	cursorRevision == revision.semanticRevision && updatedAtMs == revision.appliedAtMs

private fun CellCapturedFactRevisionEntity.isExactSettlementSuccessorOf(
	previous: CellCapturedFactRevisionEntity,
): Boolean = providerAcceptanceEndNanos.isExactSettlementOf(previous.providerAcceptanceEndNanos) &&
	authorizationEffectEndNanos.isExactSettlementOf(previous.authorizationEffectEndNanos) &&
	consentEffectEndNanos.isExactSettlementOf(previous.consentEffectEndNanos) &&
	sessionRunEffectEndNanos.isExactSettlementOf(previous.sessionRunEffectEndNanos) &&
	deletionEffectEndNanos.isExactSettlementOf(previous.deletionEffectEndNanos)

private fun CellCapturedFactRevisionEntity.hasFiniteAggregateReuseAuthority(): Boolean =
	providerAcceptanceEndNanos != Long.MAX_VALUE &&
		authorizationEffectEndNanos != Long.MAX_VALUE &&
		consentEffectEndNanos != Long.MAX_VALUE &&
		sessionRunEffectEndNanos != Long.MAX_VALUE &&
		deletionEffectEndNanos != Long.MAX_VALUE

@Suppress("LongMethod")
private fun CellCapturedFactRevisionEntity.hasSameCorrectionEffect(
	other: CellCapturedFactRevisionEntity,
): Boolean = stableCorrectionParts() == other.stableCorrectionParts()

@Suppress("LongMethod")
private fun CellCapturedFactRevisionEntity.stableCorrectionParts(): List<Any?> = listOf(
	writerProjectionId,
	writerProjectionVersion,
	writerBindingGeneration,
	writerOwnerGeneration,
	logicalFactId,
	factKind,
	aggregateOwnerLogicalFactId,
	aggregateOwnerSemanticRevision,
	logicalTrackingId,
	serviceRunId,
	sessionSegmentId,
	purpose,
	sourceDeliveryIdentity,
	sourceEventId,
	sourceAdmissionOrdinal,
	walIntegrityIdentity,
	payloadChecksum,
	deliveryUnitIndex,
	deliveryUnitCount,
	sourceSequence,
	planAttribution,
	payloadVersion,
	canonicalProviderSemanticsDigest,
	sourceInstanceId,
	registrationGeneration,
	configurationRevision,
	physicalConfigurationFingerprint,
	authorizationRevision,
	authorizationFingerprint,
	purposeEligibilityMask,
	sourcePolicyRevision,
	captureConsentEpoch,
	manifestRevision,
	lifecycleLeaseGeneration,
	collectedDataEpoch,
	scopeDeletionGeneration,
	clockDomainId,
	storedZoneId,
	structuralEpochDay,
	providerAcceptanceStartNanos,
	authorizationEffectStartNanos,
	consentEffectStartNanos,
	sessionRunEffectStartNanos,
	deletionEffectStartNanos,
	maximumObservationAgeNanos,
	observedIntervalStartNanos,
	observedElapsedNanos,
	receivedElapsedNanos,
	coverageIntervalStartNanos,
	coverageIntervalEndNanos,
	observedWallTimeMs,
	wallTimeUncertaintyMs,
	acquiredAtMs,
	createdAtMs,
	qualityFlags,
	qualityConfidence,
	availability,
	submittedChildCount,
	acceptedChildCount,
	staleChildCount,
	futureTimeChildCount,
	missingTimeChildCount,
	clockUnverifiableChildCount,
	authorityMismatchChildCount,
	unsupportedTechnologyChildCount,
	subscriptionCompleteness,
	childCompleteness,
	observationCount,
	registeredObservationCount,
	gsmCount,
	cdmaCount,
	wcdmaCount,
	tdscdmaCount,
	lteCount,
	nrCount,
	qualityUnknownCount,
	qualityNoneOrUnknownCount,
	qualityPoorCount,
	qualityModerateCount,
	qualityGoodCount,
	qualityGreatCount,
	weakObservationCount,
	knownQualityObservationCount,
	allKnownQualityIsWeak,
	appliedAtMs,
)

private fun SessionManifestSourceEntity.isExactCapturedCellBinding(
	revision: CellCapturedFactRevisionEntity,
): Boolean = sourceKind == CELL_SOURCE && purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
	persistenceEligible && consentEpoch == revision.captureConsentEpoch &&
	outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL &&
	writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS &&
	writerOwnerGeneration == revision.writerOwnerGeneration &&
	writerProjectionId == WRITER_ID && writerProjectionVersion == WRITER_VERSION &&
	writerBindingGeneration == SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION

private fun SessionManifestSourceEntity.isExactCapturedCellWalBinding(
	captureConsentEpoch: Long,
): Boolean = sourceKind == CELL_SOURCE && purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
	persistenceEligible && consentEpoch == captureConsentEpoch &&
	outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL &&
	writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS &&
	writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
	writerProjectionId == WRITER_ID && writerProjectionVersion == WRITER_VERSION &&
	writerBindingGeneration == SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION

private fun earliestCoveredWallTime(
	observedWallTimeMs: Long,
	uncertaintyMs: Long,
	observedElapsedNanos: Long,
	coverageStartNanos: Long,
): Long = runCatching {
	val coverageSpanNanos = Math.subtractExact(observedElapsedNanos, coverageStartNanos)
	require(coverageSpanNanos >= 0L)
	val coverageSpanMs = coverageSpanNanos / NANOS_PER_MILLISECOND
	Math.subtractExact(
		Math.subtractExact(observedWallTimeMs, coverageSpanMs),
		uncertaintyMs,
	).also { require(it >= 0L) }
}.getOrElse { block(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE) }

private fun providerWallInterval(wal: SourceEventWalEntity): LongRange? {
	val startNanos = wal.observedIntervalStartNanos ?: return null
	val wallTimeMs = wal.wallTimeMs ?: return null
	val uncertaintyMs = wal.wallTimeUncertaintyMs ?: return null
	return runCatching {
		val spanNanos = Math.subtractExact(wal.observedElapsedNanos, startNanos)
		require(spanNanos >= 0L && uncertaintyMs >= 0L)
		val earliest = Math.subtractExact(
			Math.subtractExact(wallTimeMs, spanNanos / NANOS_PER_MILLISECOND),
			uncertaintyMs,
		)
		val latest = Math.addExact(wallTimeMs, uncertaintyMs)
		require(earliest >= 0L)
		earliest..latest
	}.getOrNull()
}

/** Exact production v1 callback bytes; raw subscription and radio identity are never accepted. */
private fun SourceEventWalEntity.decodeCanonicalCellPayload(): List<CellHistoricalObservation>? = runCatching {
	require(payloadVersion == CELL_PAYLOAD_VERSION)
	val decoded = DataInputStream(ByteArrayInputStream(payload)).use { input ->
		require(input.readInt() == CELL_PAYLOAD_TYPE)
		require(input.readNullableCellInt() == null)
		val childCount = input.readInt()
		require(childCount in 1..MAX_CELL_CHILDREN_PER_DELIVERY)
		val children = List(childCount) {
			CellHistoricalObservation(
				identifierToken = input.readUTF(),
				radioType = input.readUTF(),
				registered = input.readBoolean(),
				signalLevelDbm = input.readNullableCellInt(),
				providerTimestampNanos = input.readNullableCellLong(),
			)
		}
		require(input.readInt() == CELL_REFRESH_OUTCOME_CALLBACK)
		require(input.available() == 0)
		children
	}
	require(decoded.all { child ->
		child.identifierToken.isEmpty() && child.providerTimestampNanos != null &&
			requireNotNull(child.providerTimestampNanos) > 0L
	})
	require(encodeCanonicalCellPayload(decoded).contentEquals(payload))
	val providerTimes = decoded.map { child -> requireNotNull(child.providerTimestampNanos) }
	require(providerTimes.minOrNull() == observedIntervalStartNanos)
	require(providerTimes.maxOrNull() == observedElapsedNanos)
	decoded
}.getOrNull()

/**
 * Deterministically replays the source classifier's identity-free product effect. Maintenance
 * cannot authenticate a fact merely because its WAL header and a self-rehashed fact agree.
 */
private fun SourceEventWalEntity.canonicalCellPayloadEffect(
	revision: CellCapturedFactRevisionEntity,
	decoded: List<CellHistoricalObservation>,
): CellHistoricalPayloadEffect? = runCatching {
	val capturedStart = maxOf(
		revision.providerAcceptanceStartNanos,
		revision.authorizationEffectStartNanos,
		revision.consentEffectStartNanos,
		revision.sessionRunEffectStartNanos,
		revision.deletionEffectStartNanos,
	)
	val capturedEnd = minOf(
		revision.providerAcceptanceEndNanos,
		revision.authorizationEffectEndNanos,
		revision.consentEffectEndNanos,
		revision.sessionRunEffectEndNanos,
		revision.deletionEffectEndNanos,
	)
	require(capturedEnd > capturedStart)

	val accepted = mutableListOf<CellHistoricalObservation>()
	var stale = 0
	var future = 0
	var unsupported = 0
	for (child in decoded) {
		val providerTime = requireNotNull(child.providerTimestampNanos)
		when {
			providerTime > receivedElapsedNanos -> future++
			providerTime < capturedStart || providerTime >= capturedEnd ||
				receivedElapsedNanos - providerTime > revision.maximumObservationAgeNanos -> stale++
			child.radioTechnology == null -> unsupported++
			else -> accepted += child
		}
	}
	require(accepted.isNotEmpty())
	CellHistoricalPayloadEffect(
		providerIntervalStartNanos = accepted.minOf { child -> requireNotNull(child.providerTimestampNanos) },
		providerIntervalEndNanos = accepted.maxOf { child -> requireNotNull(child.providerTimestampNanos) },
		submittedChildCount = decoded.size,
		acceptedChildCount = accepted.size,
		staleChildCount = stale,
		futureTimeChildCount = future,
		missingTimeChildCount = 0,
		unsupportedTechnologyChildCount = unsupported,
		childCompleteness = if (accepted.size == decoded.size) {
			CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_COMPLETE
		} else {
			CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_PARTIAL
		},
		aggregate = accepted.toIdentityFreeAggregate(),
	)
}.getOrNull()

private fun encodeCanonicalCellPayload(children: List<CellHistoricalObservation>): ByteArray =
	ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeInt(CELL_PAYLOAD_TYPE)
			output.writeNullableCellInt(null)
			output.writeInt(children.size)
			children.forEach { child ->
				output.writeUTF(child.identifierToken)
				output.writeUTF(child.radioType)
				output.writeBoolean(child.registered)
				output.writeNullableCellInt(child.signalLevelDbm)
				output.writeNullableCellLong(child.providerTimestampNanos)
			}
			output.writeInt(CELL_REFRESH_OUTCOME_CALLBACK)
		}
		buffer.toByteArray()
	}

private fun DataInputStream.readNullableCellInt(): Int? = if (readBoolean()) readInt() else null

private fun DataInputStream.readNullableCellLong(): Long? = if (readBoolean()) readLong() else null

private fun DataOutputStream.writeNullableCellInt(value: Int?) {
	writeBoolean(value != null)
	if (value != null) writeInt(value)
}

private fun DataOutputStream.writeNullableCellLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun List<CellHistoricalObservation>.toIdentityFreeAggregate(): CellHistoricalIdentityFreeAggregate {
	val technologyCounts = groupingBy { child -> requireNotNull(child.radioTechnology) }.eachCount()
	val qualityCounts = groupingBy(CellHistoricalObservation::qualityLevel).eachCount()
	val unknown = qualityCounts[null] ?: 0
	val noneOrUnknown = qualityCounts[0] ?: 0
	val poor = qualityCounts[1] ?: 0
	val known = size - unknown
	val weak = Math.addExact(noneOrUnknown, poor)
	return CellHistoricalIdentityFreeAggregate(
		observationCount = size,
		registeredObservationCount = count(CellHistoricalObservation::registered),
		gsmCount = technologyCounts[CELL_TECHNOLOGY_GSM] ?: 0,
		cdmaCount = technologyCounts[CELL_TECHNOLOGY_CDMA] ?: 0,
		wcdmaCount = technologyCounts[CELL_TECHNOLOGY_WCDMA] ?: 0,
		tdscdmaCount = technologyCounts[CELL_TECHNOLOGY_TDSCDMA] ?: 0,
		lteCount = technologyCounts[CELL_TECHNOLOGY_LTE] ?: 0,
		nrCount = technologyCounts[CELL_TECHNOLOGY_NR] ?: 0,
		qualityUnknownCount = unknown,
		qualityNoneOrUnknownCount = noneOrUnknown,
		qualityPoorCount = poor,
		qualityModerateCount = qualityCounts[2] ?: 0,
		qualityGoodCount = qualityCounts[3] ?: 0,
		qualityGreatCount = qualityCounts[4] ?: 0,
		weakObservationCount = weak,
		knownQualityObservationCount = known,
		allKnownQualityIsWeak = known > 0 && weak == known,
	)
}

private fun CellHistoricalObservation.toProviderDeliveryIdentityFact() =
	CellProviderDeliveryIdentityFact(
		radioType = radioType,
		registered = registered,
		signalLevelDbm = signalLevelDbm,
		providerTimestampNanos = requireNotNull(providerTimestampNanos),
	)

private val CellHistoricalObservation.radioTechnology: String?
	get() = radioType.uppercase().takeIf { technology -> technology in CELL_RADIO_TECHNOLOGIES }

private val CellHistoricalObservation.qualityLevel: Int?
	get() = when {
		signalLevelDbm == null || signalLevelDbm == Int.MAX_VALUE -> null
		signalLevelDbm <= -120 -> 0
		signalLevelDbm <= -110 -> 1
		signalLevelDbm <= -100 -> 2
		signalLevelDbm <= -90 -> 3
		else -> 4
	}

private fun CellCapturedFactRevisionEntity.matchesCellPayloadEffect(
	effect: CellHistoricalPayloadEffect,
): Boolean = coverageIntervalStartNanos == effect.providerIntervalStartNanos &&
	coverageIntervalEndNanos == effect.providerIntervalEndNanos &&
	submittedChildCount == effect.submittedChildCount &&
	acceptedChildCount == effect.acceptedChildCount && staleChildCount == effect.staleChildCount &&
	futureTimeChildCount == effect.futureTimeChildCount &&
	missingTimeChildCount == effect.missingTimeChildCount &&
	clockUnverifiableChildCount == 0 && authorityMismatchChildCount == 0 &&
	unsupportedTechnologyChildCount == effect.unsupportedTechnologyChildCount &&
	subscriptionCompleteness == CellCapturedFactRevisionEntity.SUBSCRIPTION_COMPLETENESS_UNKNOWN &&
	childCompleteness == effect.childCompleteness && when (factKind) {
		CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE -> matchesIdentityFreeAggregate(effect.aggregate)
		CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY -> true
		else -> false
	}

private fun CellCapturedFactRevisionEntity.matchesIdentityFreeAggregate(
	aggregate: CellHistoricalIdentityFreeAggregate,
): Boolean = observationCount == aggregate.observationCount &&
	registeredObservationCount == aggregate.registeredObservationCount && gsmCount == aggregate.gsmCount &&
	cdmaCount == aggregate.cdmaCount && wcdmaCount == aggregate.wcdmaCount &&
	tdscdmaCount == aggregate.tdscdmaCount && lteCount == aggregate.lteCount && nrCount == aggregate.nrCount &&
	qualityUnknownCount == aggregate.qualityUnknownCount &&
	qualityNoneOrUnknownCount == aggregate.qualityNoneOrUnknownCount &&
	qualityPoorCount == aggregate.qualityPoorCount &&
	qualityModerateCount == aggregate.qualityModerateCount && qualityGoodCount == aggregate.qualityGoodCount &&
	qualityGreatCount == aggregate.qualityGreatCount && weakObservationCount == aggregate.weakObservationCount &&
	knownQualityObservationCount == aggregate.knownQualityObservationCount &&
	allKnownQualityIsWeak == aggregate.allKnownQualityIsWeak

private fun LogicalTrackingSessionEntity.hasValidHistoricalCellShape(
	expectedBootId: String,
): Boolean {
	val terminal = state in TERMINAL_SESSION_STATES
	val nonterminal = state in NONTERMINAL_SESSION_STATES
	if (!terminal && !nonterminal) return false
	if (logicalTrackingId.isBlank() || lifecycleRevision <= 0L || desiredPlanRevision <= 0L ||
		rolloutRevision <= 0L || startOrigin.isBlank() || clockDomainId != expectedBootId ||
		lifecycleBootId != expectedBootId || lifecycleLeaseGeneration <= 0L ||
		startedAtMs < 0L || startedElapsedNanos < 0L ||
		(cutoffAtMs == null) != (cutoffElapsedNanos == null)
	) return false
	return if (terminal) {
		completedAtMs != null && cutoffAtMs != null && cutoffElapsedNanos != null &&
			completedAtMs >= cutoffAtMs && finalAdmissionOrdinal != null && finalAdmissionOrdinal >= 0L &&
			currentServiceRunId == null
	} else {
		completedAtMs == null && finalAdmissionOrdinal == null &&
			((state == SESSION_STATE_STOPPING) == (cutoffAtMs != null)) &&
			currentServiceRunId?.isNotBlank() == true
	}
}

private fun SourceServiceRunEntity.hasValidHistoricalCellShape(
	session: LogicalTrackingSessionEntity,
): Boolean {
	val terminal = state in TERMINAL_SESSION_STATES
	val nonterminal = state in NONTERMINAL_RUN_STATES
	if (!terminal && !nonterminal) return false
	if (serviceRunId.isBlank() || logicalTrackingId != session.logicalTrackingId ||
		bootId != session.clockDomainId ||
		leaseGeneration <= 0L || leaseGeneration > session.lifecycleLeaseGeneration ||
		desiredPlanRevision <= 0L || rolloutRevision <= 0L || startedAtMs < 0L ||
		startedElapsedNanos < 0L || startOrigin.isBlank() || sessionSegmentId == null ||
		preparedManifestRevision <= 0L || preparedIntentRevision <= 0L
	) return false
	if (terminal != (completedAtMs != null) || terminal != !completionReason.isNullOrBlank()) return false
	if (session.state in TERMINAL_SESSION_STATES) return terminal
	if (terminal) return session.currentServiceRunId != serviceRunId
	if (session.currentServiceRunId != serviceRunId) return false
	return (session.state in ACTIVE_ADMISSION_SESSION_STATES && state in ACTIVE_ADMISSION_RUN_STATES) ||
		(session.state == SESSION_STATE_ACTIVE && state == SESSION_STATE_STOPPING) ||
		(session.state == SESSION_STATE_STOPPING && state == SESSION_STATE_STOPPING)
}

private fun decodeCanonicalCellPlan(bytes: ByteArray): CellHistoricalPlan? = runCatching {
	val plan = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
		require(input.readInt() == CELL_PLAN_FORMAT_VERSION)
		require(input.readUTF() == CELL_PLAN_SOURCE_NAME)
		val revision = input.readLong()
		val mode = input.readUTF()
		val minimumRefreshAttemptIntervalMs = input.readLong()
		val maximumAcceptableCachedAgeMs = input.readLong()
		val count = input.readInt()
		require(count in 0..MAX_CELL_PLAN_SUBSCRIPTIONS)
		val subscriptionIds = buildSet(count) { repeat(count) { add(input.readInt()) } }
		require(subscriptionIds.size == count)
		val backoffInitialDelayMs = input.readLong()
		val backoffMaximumDelayMs = input.readLong()
		val backoffMultiplier = input.readDouble()
		require(input.available() == 0)
		CellHistoricalPlan(
			revision = revision,
			mode = mode,
			minimumRefreshAttemptIntervalMs = minimumRefreshAttemptIntervalMs,
			maximumAcceptableCachedAgeMs = maximumAcceptableCachedAgeMs,
			subscriptionIds = subscriptionIds,
			backoffInitialDelayMs = backoffInitialDelayMs,
			backoffMaximumDelayMs = backoffMaximumDelayMs,
			backoffMultiplier = backoffMultiplier,
		)
	}
	val canonical = ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeInt(CELL_PLAN_FORMAT_VERSION)
			output.writeUTF(CELL_PLAN_SOURCE_NAME)
			output.writeLong(plan.revision)
			output.writeUTF(plan.mode)
			output.writeLong(plan.minimumRefreshAttemptIntervalMs)
			output.writeLong(plan.maximumAcceptableCachedAgeMs)
			output.writeInt(plan.subscriptionIds.size)
			plan.subscriptionIds.sorted().forEach(output::writeInt)
			output.writeLong(plan.backoffInitialDelayMs)
			output.writeLong(plan.backoffMaximumDelayMs)
			output.writeDouble(plan.backoffMultiplier)
		}
		buffer.toByteArray()
	}
	require(canonical.contentEquals(bytes))
	plan
}.getOrNull()

private fun Long.isExactSettlementOf(previous: Long): Boolean =
	this == previous || (previous == Long.MAX_VALUE && this < Long.MAX_VALUE)

private fun hasValidZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

private data class CellCapturedLineage(
	val logicalFactId: String,
	val revisions: List<CellCapturedFactRevisionEntity>,
	val earliestPossibleWallTimeMs: Long,
	val aggregateOwnerLogicalFactId: String?,
	val aggregateOwnerSemanticRevision: Long?,
) {
	val scope: CellCapturedRunScope
		get() = revisions.first().let { revision ->
			CellCapturedRunScope(revision.logicalTrackingId, revision.serviceRunId)
		}
}

private data class CellCapturedRunScope(
	val logicalTrackingId: String,
	val serviceRunId: String,
)

private data class CellHistoricalPlan(
	val revision: Long,
	val mode: String,
	val minimumRefreshAttemptIntervalMs: Long,
	val maximumAcceptableCachedAgeMs: Long,
	val subscriptionIds: Set<Int>,
	val backoffInitialDelayMs: Long,
	val backoffMaximumDelayMs: Long,
	val backoffMultiplier: Double,
) {
	fun hasSupportedHistoricalShape(): Boolean =
		mode in CELL_CAPTURE_MODES && minimumRefreshAttemptIntervalMs >= 0L &&
			maximumAcceptableCachedAgeMs >= 0L && subscriptionIds.size <= MAX_CELL_PLAN_SUBSCRIPTIONS &&
			subscriptionIds.all { it >= 0 } && backoffInitialDelayMs >= 0L &&
			backoffMaximumDelayMs >= backoffInitialDelayMs && backoffMultiplier.isFinite() &&
			backoffMultiplier >= 1.0

	fun maximumObservationAgeNanos(): Long =
		if (maximumAcceptableCachedAgeMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
			Long.MAX_VALUE
		} else {
			maximumAcceptableCachedAgeMs * NANOS_PER_MILLISECOND
		}

	fun physicalConfigurationFingerprint(): String {
		val components = when (mode) {
			CELL_MODE_OBSERVE_CHANGES -> listOf(
				CELL_PLAN_SOURCE_NAME,
				"CHANGE_CALLBACK",
				subscriptionIds.sorted().joinToString(","),
			)
			CELL_MODE_OBSERVE_AND_SPARSE_REFRESH -> listOf(
				CELL_PLAN_SOURCE_NAME,
				"CHANGE_CALLBACK",
				"EXPLICIT_REFRESH",
				subscriptionIds.sorted().joinToString(","),
				minimumRefreshAttemptIntervalMs,
				backoffInitialDelayMs,
				backoffMaximumDelayMs,
				backoffMultiplier,
			)
			else -> return ""
		}.joinToString("\u001f")
		return sha256(components.toByteArray(Charsets.UTF_8))
	}
}

private data class CellCapturedWalAudit(
	val scopes: Set<CellCapturedRunScope>,
	val latestDurableTimeMs: Long,
)

/** Exact source-local product authority required by one-hop aggregate reuse. */
private data class CellAggregateReuseAuthority(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val sessionSegmentId: Long,
	val capturedSources: Set<Int>,
	val controlSources: Set<Int>,
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val manifestRevision: Long,
	val lifecycleLeaseGeneration: Long,
	val collectedDataEpoch: Long,
	val scopeDeletionGeneration: Long,
	val clockDomainId: String,
	val storedZoneId: String,
	val structuralEpochDay: Long,
	val providerAcceptanceStartNanos: Long,
	val providerAcceptanceEndNanos: Long,
	val authorizationEffectStartNanos: Long,
	val authorizationEffectEndNanos: Long,
	val consentEffectStartNanos: Long,
	val consentEffectEndNanos: Long,
	val sessionRunEffectStartNanos: Long,
	val sessionRunEffectEndNanos: Long,
	val deletionEffectStartNanos: Long,
	val deletionEffectEndNanos: Long,
	val maximumObservationAgeNanos: Long,
)

private data class AuthenticatedCellFactAuthority(
	val reuseAuthority: CellAggregateReuseAuthority,
	val aggregate: CellHistoricalIdentityFreeAggregate,
	val walCreatedAtMs: Long,
)

private data class CellHistoricalObservation(
	val identifierToken: String,
	val radioType: String,
	val registered: Boolean,
	val signalLevelDbm: Int?,
	val providerTimestampNanos: Long?,
)

private data class CellHistoricalPayloadEffect(
	val providerIntervalStartNanos: Long,
	val providerIntervalEndNanos: Long,
	val submittedChildCount: Int,
	val acceptedChildCount: Int,
	val staleChildCount: Int,
	val futureTimeChildCount: Int,
	val missingTimeChildCount: Int,
	val unsupportedTechnologyChildCount: Int,
	val childCompleteness: String,
	val aggregate: CellHistoricalIdentityFreeAggregate,
)

private data class CellHistoricalIdentityFreeAggregate(
	val observationCount: Int,
	val registeredObservationCount: Int,
	val gsmCount: Int,
	val cdmaCount: Int,
	val wcdmaCount: Int,
	val tdscdmaCount: Int,
	val lteCount: Int,
	val nrCount: Int,
	val qualityUnknownCount: Int,
	val qualityNoneOrUnknownCount: Int,
	val qualityPoorCount: Int,
	val qualityModerateCount: Int,
	val qualityGoodCount: Int,
	val qualityGreatCount: Int,
	val weakObservationCount: Int,
	val knownQualityObservationCount: Int,
	val allKnownQualityIsWeak: Boolean,
)

private data class CellCapturedFactAudit(
	val lineages: List<CellCapturedLineage>,
	val lineagesById: Map<String, CellCapturedLineage>,
	val generationByScope: Map<CellCapturedRunScope, CellCaptureDeletionGenerationEntity>,
	val latestDurableTimeMs: Long,
)

private class CellCapturedRetentionBlockedException(
	val reason: CellCapturedRetentionBlockedReason,
) : IllegalStateException(reason.name)

private class CellCapturedSourceDeletionBlockedException(
	val reason: CellCapturedSourceDeletionBlockedReason,
) : IllegalStateException(reason.name)

private class CellCapturedMaintenanceLimitExceeded : IllegalStateException()

private fun block(reason: CellCapturedRetentionBlockedReason): Nothing =
	throw CellCapturedRetentionBlockedException(reason)

private fun block(reason: CellCapturedSourceDeletionBlockedReason): Nothing =
	throw CellCapturedSourceDeletionBlockedException(reason)

private const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
private const val CAPTURE_PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
private const val WRITER_ID = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
private const val WRITER_VERSION = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
private const val CAPTURED_REGISTRATION_PLAN_ATTRIBUTION = 0
private const val INSERT_IGNORED = -1L
private const val DELETE_BATCH_SIZE = 128
private const val CURSOR_PAGE_SIZE = 256
private const val DELETION_GENERATION_PAGE_SIZE = 256
private const val WAL_PAGE_SIZE = 256
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val CELL_PLAN_FORMAT_VERSION = 1
private const val CELL_PLAN_PAYLOAD_VERSION = 1
private const val CELL_PAYLOAD_VERSION = 1
private const val CELL_PAYLOAD_TYPE = 8
private const val CELL_REFRESH_OUTCOME_CALLBACK = 0
private const val MAX_CELL_CHILDREN_PER_DELIVERY = 256
private const val CELL_PLAN_SOURCE_NAME = "CELL"
private const val CELL_MODE_OBSERVE_CHANGES = "OBSERVE_CHANGES"
private const val CELL_MODE_OBSERVE_AND_SPARSE_REFRESH = "OBSERVE_AND_SPARSE_REFRESH"
private const val MAX_CELL_PLAN_SUBSCRIPTIONS = 10_000
private const val CELL_TECHNOLOGY_GSM = "GSM"
private const val CELL_TECHNOLOGY_CDMA = "CDMA"
private const val CELL_TECHNOLOGY_WCDMA = "WCDMA"
private const val CELL_TECHNOLOGY_TDSCDMA = "TDSCDMA"
private const val CELL_TECHNOLOGY_LTE = "LTE"
private const val CELL_TECHNOLOGY_NR = "NR"
private val CELL_RADIO_TECHNOLOGIES = setOf(
	CELL_TECHNOLOGY_GSM,
	CELL_TECHNOLOGY_CDMA,
	CELL_TECHNOLOGY_WCDMA,
	CELL_TECHNOLOGY_TDSCDMA,
	CELL_TECHNOLOGY_LTE,
	CELL_TECHNOLOGY_NR,
)
private val LOWERCASE_SHA_256 = Regex("^[0-9a-f]{64}$")

private val CELL_CAPTURE_MODES = setOf(
	CELL_MODE_OBSERVE_CHANGES,
	CELL_MODE_OBSERVE_AND_SPARSE_REFRESH,
)
private const val SESSION_STATE_ACTIVE = "ACTIVE"
private const val SESSION_STATE_STOPPING = "STOPPING"
private val TERMINAL_SESSION_STATES = setOf("FINALIZED", "FAILED")
private val NONTERMINAL_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING", "STOPPING")
private val NONTERMINAL_RUN_STATES = setOf("STARTING", "ACTIVE", "STOPPING")
private val ACTIVE_ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
private val ACTIVE_ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")

private val DEFAULT_CELL_CAPTURED_MAINTENANCE_LIMITS = CellCapturedMaintenanceLimits()
