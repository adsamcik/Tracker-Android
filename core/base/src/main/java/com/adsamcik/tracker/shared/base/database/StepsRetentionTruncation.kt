package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.StepsFactCandidateIdentity
import com.adsamcik.tracker.shared.base.database.dao.hasValidStepsFactCandidateState
import com.adsamcik.tracker.shared.base.database.dao.visitStepsFactCandidateStatesByRun
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity

/**
 * Installs one immutable, payload-free marker for an exact Steps run crossed by raw retention.
 *
 * The distinct purpose is intentionally not a capture deletion fence, so an active run may keep
 * writing while every product reader remains aware that its earlier prefix can no longer be
 * reconstructed. Returns true only when this call installed the first marker.
 */
suspend fun AppDatabase.markStepsRetentionTruncation(
	logicalTrackingId: String,
	serviceRunId: String,
	collectedDataEpoch: Long,
	markedAtMs: Long,
): Boolean {
	val expected = StepFactRevisionIntegrity.retentionTruncationFence(
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		collectedDataEpoch = collectedDataEpoch,
		markedAtMs = markedAtMs,
	)
	val dao = sourceDeletionFenceDao()
	if (dao.insertIfAbsent(expected) != INSERT_IGNORED) {
		return true
	}
	val retained = dao.get(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
		scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeIdentityDigest = expected.scopeIdentityDigest,
	)
	check(retained != null && StepFactRevisionIntegrity.isRetentionTruncationFence(
		fence = retained,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		collectedDataEpoch = collectedDataEpoch,
	)) { "Conflicting Steps retention-truncation marker" }
	return false
}

/**
 * Authenticates every candidate attached to an affected run, then marks exact current-epoch runs.
 *
 * This remains a separate preflight so callers can durably record product-history loss before a
 * pending raw signal defers physical pruning. Any invalid or stale candidate attached to a run with
 * an authenticated floor crossing aborts the transaction before a marker can commit.
 */
suspend fun AppDatabase.markAuthenticatedStepsRunsAffectedByRetentionFloor(
	beforeMs: Long,
	collectedDataEpoch: Long,
	markedAtMs: Long,
): Int = withTransaction {
	require(beforeMs >= 0L)
	require(collectedDataEpoch >= 0L)
	require(markedAtMs >= 0L)
	requireCurrentCollectedDataEpoch(collectedDataEpoch)
	var inserted = 0
	visitAuthenticatedRetentionRunBatches(beforeMs, collectedDataEpoch) { runs ->
		runs.filter(AuthenticatedRetentionRun::requiresRetentionMarker).forEach { run ->
			if (markStepsRetentionTruncation(
					logicalTrackingId = run.serviceRun.logicalTrackingId,
					serviceRunId = run.serviceRun.serviceRunId,
					collectedDataEpoch = collectedDataEpoch,
					markedAtMs = markedAtMs,
				)
			) {
				inserted++
			}
		}
	}
	inserted
}

/**
 * Re-authenticates and prunes only exact fact identities selected by the retention preflight.
 *
 * A current UPSERT below the floor installs a run marker before only the authenticated, expired
 * UPSERT identities are removed. Retaining post-floor suffix facts preserves bounded day/history
 * discovery, while the marker prevents that suffix from ever looking complete. A fact already
 * hidden by a valid redacted RETRACT only removes its own expired UPSERT. No wall-time-only DELETE
 * fallback exists.
 */
suspend fun AppDatabase.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(
	beforeMs: Long,
	collectedDataEpoch: Long,
	markedAtMs: Long,
): Int = withTransaction {
	require(beforeMs >= 0L)
	require(collectedDataEpoch >= 0L)
	require(markedAtMs >= 0L)
	requireCurrentCollectedDataEpoch(collectedDataEpoch)
	val factDao = stepFactRevisionDao()
	var deleted = 0
	visitAuthenticatedRetentionRunBatches(beforeMs, collectedDataEpoch) { runs ->
		runs.forEach { run ->
			if (run.requiresRetentionMarker) {
				markStepsRetentionTruncation(
					logicalTrackingId = run.serviceRun.logicalTrackingId,
					serviceRunId = run.serviceRun.serviceRunId,
					collectedDataEpoch = collectedDataEpoch,
					markedAtMs = markedAtMs,
				)
			}
			val selected = run.expiredFactIdentities.map(run.authenticatedFacts::getValue)
			selected
				.groupBy { fact ->
					Triple(
						fact.writerProjectionId,
						fact.writerProjectionVersion,
						fact.semanticRevision,
					)
				}
				.forEach { (writer, facts) ->
					facts.chunked(RETENTION_FACT_DELETE_BATCH_SIZE).forEach { batch ->
						val deletedBatch = factDao.deleteAuthenticatedUpsertRevisions(
							writerProjectionId = writer.first,
							writerProjectionVersion = writer.second,
							semanticRevision = writer.third,
							logicalFactIds = batch.map(StepFactRevisionEntity::logicalFactId),
						)
						check(deletedBatch == batch.size) {
							"Authenticated Steps retention set changed during pruning"
						}
						deleted += deletedBatch
					}
				}
		}
	}
	deleted
}

private suspend fun AppDatabase.requireCurrentCollectedDataEpoch(expectedEpoch: Long) {
	val current = requireNotNull(sourceEvidenceStateDao().get()) {
		"Steps retention requires initialized source-evidence state"
	}
	check(current.collectedDataEpoch == expectedEpoch) {
		"Steps retention epoch does not match current source-evidence state"
	}
}

@Suppress("CyclomaticComplexMethod", "ComplexCondition")
private suspend fun AppDatabase.visitAuthenticatedRetentionRunBatches(
	beforeMs: Long,
	collectedDataEpoch: Long,
	visit: suspend (List<AuthenticatedRetentionRun>) -> Unit,
) {
	val factDao = stepFactRevisionDao()
	val historyDao = trackingHistoryReadDao()
	authenticateCompleteStepsFactStore(collectedDataEpoch)
	var afterServiceRunId: String? = null
	while (true) {
		val candidateRunIds = factDao.retentionCandidateServiceRunIds(
			beforeMs = beforeMs,
			afterServiceRunId = afterServiceRunId,
			limit = RETENTION_RUN_PAGE_SIZE,
		)
		if (candidateRunIds.isEmpty()) {
			break
		}
		val builders = historyDao.serviceRuns(candidateRunIds)
			.associate { run -> run.serviceRunId to AuthenticatedRetentionRunBuilder(run) }
			.toMutableMap()
		check(builders.keys == candidateRunIds.toSet()) {
			"Steps retention candidate is missing its authoritative service run"
		}
		historyDao.visitStepsFactCandidateStatesByRun(builders.values.map { it.serviceRun }) {
			serviceRunId, identity, candidate ->
			val builder = builders.getValue(serviceRunId)
			val scope = candidate.scopeCarrier
			val state = candidate.state
			if (!hasValidStepsFactCandidateState(candidate) || scope == null || state == null ||
				scope.logicalTrackingId != builder.serviceRun.logicalTrackingId ||
				scope.serviceRunId != builder.serviceRun.serviceRunId ||
				scope.collectedDataEpoch != collectedDataEpoch ||
				state.collectedDataEpoch != collectedDataEpoch ||
				identity.writerProjectionVersion != scope.writerProjectionVersion.toLong()
			) {
				builder.hasInvalidCandidate = true
				return@visitStepsFactCandidateStatesByRun true
			}
			builder.authenticatedFacts[identity] = scope
			if (requireNotNull(scope.intervalEndTimeMs) < beforeMs) {
				builder.expiredFactIdentities += identity
				if (state.operation == StepFactRevisionEntity.OPERATION_UPSERT) {
					builder.requiresRetentionMarker = true
				}
			}
			true
		}
		val affectedRuns = builders.values.map(AuthenticatedRetentionRunBuilder::validated)
		visit(affectedRuns)
		afterServiceRunId = candidateRunIds.last()
		if (candidateRunIds.size < RETENTION_RUN_PAGE_SIZE) {
			break
		}
	}
}

/**
 * Authenticates every revision before SQL may use checksum-covered fields for retention discovery.
 *
 * Portable-import rows have no active production writer yet, so they deliberately stop retention
 * until that vertical supplies its own intrinsic integrity verifier. A standalone, payload-free
 * local-delete revision may survive an earlier retention prune; its self-contained deterministic
 * authorship remains verifiable without restoring the deleted UPSERT.
 */
private suspend fun AppDatabase.authenticateCompleteStepsFactStore(collectedDataEpoch: Long) {
	val dao = stepFactRevisionDao()
	var cursor: RetentionFactAuditCursor? = null
	while (true) {
		val page = dao.retentionAuditRevisionPage(
			afterWriterProjectionId = cursor?.writerProjectionId,
			afterWriterProjectionVersion = cursor?.writerProjectionVersion,
			afterLogicalFactId = cursor?.logicalFactId,
			afterSemanticRevision = cursor?.semanticRevision,
			limit = RETENTION_FACT_AUDIT_PAGE_SIZE,
		)
		if (page.isEmpty()) {
			break
		}
		page.forEach { unvalidated ->
			val fact = unvalidated.validatedOrNull()
			check(fact != null && fact.collectedDataEpoch == collectedDataEpoch) {
				"Steps retention encountered malformed or stale fact state"
			}
			check(fact.hasAuthenticatedRetentionAuditState()) {
				"Steps retention encountered unauthenticated fact state"
			}
		}
		val last = page.last()
		val nextCursor = RetentionFactAuditCursor(
			writerProjectionId = last.writerProjectionId,
			writerProjectionVersion = last.writerProjectionVersion,
			logicalFactId = last.logicalFactId,
			semanticRevision = last.semanticRevision,
		)
		check(nextCursor != cursor) { "Steps retention fact audit page did not advance" }
		cursor = nextCursor
		if (page.size < RETENTION_FACT_AUDIT_PAGE_SIZE) {
			break
		}
	}
}

private fun StepFactRevisionEntity.hasAuthenticatedRetentionAuditState(): Boolean = when {
	originKind == StepFactRevisionEntity.ORIGIN_LIVE_WAL &&
		operation == StepFactRevisionEntity.OPERATION_UPSERT ->
		StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(this)
	originKind == StepFactRevisionEntity.ORIGIN_LOCAL_DELETE &&
		operation == StepFactRevisionEntity.OPERATION_RETRACT ->
		writerProjectionId == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID &&
		writerProjectionVersion == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION &&
		logicalFactId.startsWith("${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:") &&
		semanticRevision > 1L &&
		StepFactRevisionIntegrity.hasValidLocalDeleteEffectChecksum(
			retraction = this,
			expectedScopeIdentityDigest = originIdentity,
		)
	else -> false
}

private data class RetentionFactAuditCursor(
	val writerProjectionId: String,
	val writerProjectionVersion: Long,
	val logicalFactId: String,
	val semanticRevision: Long,
)

private data class AuthenticatedRetentionRun(
	val serviceRun: SourceServiceRunEntity,
	val authenticatedFacts: Map<StepsFactCandidateIdentity, StepFactRevisionEntity>,
	val expiredFactIdentities: Set<StepsFactCandidateIdentity>,
	val requiresRetentionMarker: Boolean,
)

private class AuthenticatedRetentionRunBuilder(val serviceRun: SourceServiceRunEntity) {
	val authenticatedFacts = linkedMapOf<StepsFactCandidateIdentity, StepFactRevisionEntity>()
	val expiredFactIdentities = linkedSetOf<StepsFactCandidateIdentity>()
	var hasInvalidCandidate = false
	var requiresRetentionMarker = false

	fun validated(): AuthenticatedRetentionRun {
		check(!hasInvalidCandidate) {
			"Affected Steps retention run contains unauthenticated candidate state"
		}
		check(expiredFactIdentities.isNotEmpty()) {
			"Steps retention floor crossing did not authenticate against its current lineage"
		}
		check(
			StepFactRevisionIntegrity.hasValidCanonicalLiveWalRunTimeline(
				facts = authenticatedFacts.values,
				logicalTrackingId = serviceRun.logicalTrackingId,
				serviceRunId = serviceRun.serviceRunId,
			),
		) { "Affected Steps retention run has an invalid canonical timeline" }
		return AuthenticatedRetentionRun(
			serviceRun = serviceRun,
			authenticatedFacts = authenticatedFacts,
			expiredFactIdentities = expiredFactIdentities,
			requiresRetentionMarker = requiresRetentionMarker,
		)
	}
}

private const val INSERT_IGNORED = -1L
private const val RETENTION_RUN_PAGE_SIZE = 64
private const val RETENTION_FACT_DELETE_BATCH_SIZE = 256
private const val RETENTION_FACT_AUDIT_PAGE_SIZE = 256
