package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.CellAggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Low-level exact selected Cell fact deletion. The caller authenticates replacement membership,
 * manifests, terminal lifecycle, active-demand absence and day repair before entering this call.
 */
suspend fun AppDatabase.deleteSelectedCapturedCellFactsInTransaction(
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	expectedCollectedDataEpoch: Long,
	deletedAtMs: Long,
	checkpoint: suspend (CellCapturedSelectedDeletionCheckpoint) -> Unit = {
		currentCoroutineContext().ensureActive()
	},
): CellCapturedSelectedDeletionResult {
	requireSelectedCellDeletionRequest(logicalTrackingId, serviceRunIds, expectedCollectedDataEpoch, deletedAtMs)
	val limits = DEFAULT_CELL_CAPTURED_MAINTENANCE_LIMITS
	return try {
		checkpoint(CellCapturedSelectedDeletionCheckpoint.TRANSACTION_STARTED)
		val state = sourceEvidenceStateDao().get()
		if (state == null || state.collectedDataEpoch != expectedCollectedDataEpoch) {
			selectedBlock(CellCapturedSelectedDeletionBlockedReason.STALE_COLLECTED_DATA_EPOCH)
		}
		val (lineages, factTimeMs) = authenticateSelectedCellPayload(logicalTrackingId, serviceRunIds, state, limits)
		val (generationsByRun, scopeTimeMs) = authenticateSelectedCellDeletionScopes(
			logicalTrackingId, serviceRunIds, state, limits,
		)
		val latestDurableTimeMs = maxOf(factTimeMs, scopeTimeMs)
		if (deletedAtMs < latestDurableTimeMs) {
			selectedBlock(CellCapturedSelectedDeletionBlockedReason.STALE_REQUEST)
		}
		if (lineages.isEmpty() && serviceRunIds.all(generationsByRun::containsKey)) {
			return CellCapturedSelectedDeletionResult.AlreadyDeleted
		}
		checkpoint(CellCapturedSelectedDeletionCheckpoint.AUTHORITY_AUTHENTICATED)
		installSelectedCellDeletionFences(
			logicalTrackingId, serviceRunIds.filterNot(generationsByRun::containsKey),
			expectedCollectedDataEpoch, deletedAtMs,
		)
		checkpoint(CellCapturedSelectedDeletionCheckpoint.DELETION_FENCES_INSTALLED)
		val deletedRevisionCount = removeSelectedCellPayload(lineages, deletedAtMs)
		checkpoint(CellCapturedSelectedDeletionCheckpoint.PAYLOAD_REMOVED)
		CellCapturedSelectedDeletionResult.Deleted(
			lineages.size,
			deletedRevisionCount,
			serviceRunIds.count { it !in generationsByRun },
		)
	} catch (blocked: CellCapturedSelectedDeletionBlockedException) {
		CellCapturedSelectedDeletionResult.Blocked(blocked.reason)
	} catch (_: CellCapturedRetentionBlockedException) {
		CellCapturedSelectedDeletionResult.Blocked(
			CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	} catch (_: CellCapturedMaintenanceLimitExceeded) {
		CellCapturedSelectedDeletionResult.Blocked(
			CellCapturedSelectedDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	} catch (_: IllegalArgumentException) {
		CellCapturedSelectedDeletionResult.Blocked(
			CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
}

private fun AppDatabase.requireSelectedCellDeletionRequest(
	logicalTrackingId: String, serviceRunIds: List<String>, expectedCollectedDataEpoch: Long, deletedAtMs: Long,
) {
	require(logicalTrackingId.isNotBlank())
	require(serviceRunIds.isNotEmpty() && serviceRunIds.distinct().size == serviceRunIds.size)
	require(expectedCollectedDataEpoch >= 0L && deletedAtMs >= 0L)
	check(inTransaction()) { "Selected captured Cell deletion requires one caller-owned transaction" }
}

private suspend fun AppDatabase.loadSelectedCellCursors(
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	logicalFactIds: Set<String>,
	limits: CellCapturedMaintenanceLimits,
): Map<String, CellCapturedFactCursorEntity> {
	val cursorRows = cellCapturedFactDao().historyCursorsForScopes(
		WRITER_ID,
		WRITER_VERSION,
		serviceRunIds,
		listOf(logicalTrackingId),
		limits.maximumCursors + 1,
	)
	if (cursorRows.size > limits.maximumCursors) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val cursors = cursorRows.associateBy(CellCapturedFactCursorEntity::logicalFactId)
	if (cursors.size != cursorRows.size || cursors.keys != logicalFactIds ||
		cursorRows.any {
			it.logicalTrackingId != logicalTrackingId || it.serviceRunId !in serviceRunIds
		}
	) {
		selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return cursors
}

private suspend fun AppDatabase.installSelectedCellDeletionFences(
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	expectedCollectedDataEpoch: Long,
	deletedAtMs: Long,
) {
	for (runId in serviceRunIds) {
		val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
			CELL_SOURCE,
			CAPTURE_PURPOSE,
			logicalTrackingId,
			runId,
			1L,
			expectedCollectedDataEpoch,
			deletedAtMs,
		)
		if (sourceDeletionFenceDao().insertIfAbsent(fence) == CELL_CAPTURED_INSERT_IGNORED &&
			sourceDeletionFenceDao().get(
				fence.sourceKind,
				fence.purpose,
				fence.scopeKind,
				fence.scopeIdentityDigest,
			) != fence
		) {
			selectedBlock(CellCapturedSelectedDeletionBlockedReason.DELETION_FENCE_CONFLICT)
		}
		cellCapturedFactDao().insertDeletionGeneration(
			CellCaptureDeletionGenerationEntity(
				logicalTrackingId,
				runId,
				expectedCollectedDataEpoch,
				1L,
				deletedAtMs,
			),
		)
	}
}

private suspend fun AppDatabase.removeSelectedCellPayload(
	lineages: List<CellCapturedLineage>,
	deletedAtMs: Long,
): Int {
	var deletedRevisionCount = 0
	lineages.chunked(CELL_CAPTURED_DELETE_BATCH_SIZE).forEach { batch ->
		val ids = batch.map(CellCapturedLineage::logicalFactId)
		ids.forEach { radioObservationChildDao().deleteCellSessionFact(WRITER_ID, WRITER_VERSION, it) }
		if (cellCapturedFactDao().deleteExactCursors(WRITER_ID, WRITER_VERSION, ids) != ids.size) {
			selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val expected = batch.sumOf { it.revisions.size }
		val deleted = cellCapturedFactDao().deleteExactRevisionLineages(
			WRITER_ID,
			WRITER_VERSION,
			ids,
		)
		if (deleted != expected) {
			selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		deletedRevisionCount = Math.addExact(deletedRevisionCount, deleted)
	}
	if (sourceEvidenceStateDao().incrementRevision(deletedAtMs) != 1) {
		selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return deletedRevisionCount
}

/** Load the bounded selected revision set before authenticating or changing its deletion authority. */
private suspend fun AppDatabase.loadSelectedCellCapturedRevisions(
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	limits: CellCapturedMaintenanceLimits,
): List<CellCapturedFactRevisionEntity> {
	val revisions = mutableListOf<CellCapturedFactRevisionEntity>()
	var cursor: CellSelectedRevisionCursor? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumRevisions - revisions.size
		val page = loadSelectedCellRevisionPage(logicalTrackingId, serviceRunIds, limits, cursor, remaining)
		if (page.isEmpty()) {
			break
		}
		val next = CellSelectedRevisionCursor(page.last())
		if (cursor != null && next <= cursor) {
			selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		revisions += page
		cursor = next
		if (page.size < minOf(limits.revisionPageSize, remaining + 1)) {
			break
		}
	}
	return revisions
}

private data class CellSelectedRevisionCursor(
	val projectionId: String,
	val projectionVersion: Int,
	val logicalFactId: String,
	val semanticRevision: Long,
) : Comparable<CellSelectedRevisionCursor> {
	constructor(value: CellCapturedFactRevisionEntity) : this(
		value.writerProjectionId,
		value.writerProjectionVersion,
		value.logicalFactId,
		value.semanticRevision,
	)

	override fun compareTo(other: CellSelectedRevisionCursor): Int = compareValuesBy(
		this,
		other,
		CellSelectedRevisionCursor::projectionId,
		CellSelectedRevisionCursor::projectionVersion,
		CellSelectedRevisionCursor::logicalFactId,
		CellSelectedRevisionCursor::semanticRevision,
	)
}

private fun selectedBlock(reason: CellCapturedSelectedDeletionBlockedReason): Nothing =
	throw CellCapturedSelectedDeletionBlockedException(reason)

private class CellCapturedSelectedDeletionBlockedException(
	val reason: CellCapturedSelectedDeletionBlockedReason,
) : RuntimeException(null, null, false, false)

private suspend fun AppDatabase.authenticateSelectedCellPayload(
	logicalTrackingId: String, serviceRunIds: List<String>, state: SourceEvidenceState,
	limits: CellCapturedMaintenanceLimits,
): Pair<List<CellCapturedLineage>, Long> {
	val revisions = loadSelectedCellCapturedRevisions(logicalTrackingId, serviceRunIds, limits)
	val lineages = revisions.groupBy(CellCapturedFactRevisionEntity::logicalFactId)
		.values.map(::authenticateCellCapturedLineage)
	if (lineages.size > limits.maximumLogicalFacts) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val byId = lineages.associateBy(CellCapturedLineage::logicalFactId)
	if (byId.size != lineages.size) {
		selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val cursors = loadSelectedCellCursors(logicalTrackingId, serviceRunIds, byId.keys, limits)
	val owner = sourceDestinationOwnerDao().get(
		CELL_SOURCE,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
	)
	if (lineages.isNotEmpty() && (owner == null ||
		owner.owner != SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS ||
		!owner.ownerGeneration.isKnownCapturedWriterOwnerGeneration())
	) {
		selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val latestDurableTimeMs = authenticateSelectedCellLineageSet(lineages, byId, cursors, owner, state, limits)
	return lineages to latestDurableTimeMs
}

private suspend fun AppDatabase.authenticateSelectedCellLineageSet(
	lineages: List<CellCapturedLineage>, byId: Map<String, CellCapturedLineage>,
	cursors: Map<String, CellCapturedFactCursorEntity>, owner: SourceDestinationOwnerEntity?,
	state: SourceEvidenceState, limits: CellCapturedMaintenanceLimits,
): Long {
	val reuseAuthority = mutableMapOf<String, CellAggregateReuseAuthority>()
	val aggregates = mutableMapOf<String, CellHistoricalIdentityFreeAggregate>()
	var latestDurableTimeMs = maxOf(state.updatedAtMs, owner?.updatedAtMs ?: 0L)
	for (lineage in lineages) {
		currentCoroutineContext().ensureActive()
		val latest = lineage.revisions.last()
		if (cursors[lineage.logicalFactId]?.matchesCurrentCapturedCellRevision(latest) != true ||
			!state.acceptsRetainedEpoch(latest.collectedDataEpoch) ||
			latest.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal
		) {
			selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val authority = authenticateCellCapturedAuthority(latest, limits)
		reuseAuthority[lineage.logicalFactId] = authority.reuseAuthority
		aggregates[lineage.logicalFactId] = authority.aggregate
		latestDurableTimeMs = maxOf(
			latestDurableTimeMs,
			authority.walCreatedAtMs,
			lineage.revisions.maxOf(CellCapturedFactRevisionEntity::appliedAtMs),
			requireNotNull(cursors[lineage.logicalFactId]).updatedAtMs,
		)
	}
	authenticateCapturedCellAggregateDependencies(lineages, byId, reuseAuthority, aggregates)
	return latestDurableTimeMs
}

private suspend fun AppDatabase.authenticateSelectedCellDeletionScopes(
	logicalTrackingId: String, serviceRunIds: List<String>, state: SourceEvidenceState,
	limits: CellCapturedMaintenanceLimits,
): Pair<Map<String, CellCaptureDeletionGenerationEntity>, Long> {
	val generations = cellCapturedFactDao().portableDeletionGenerationClosure(
		logicalTrackingId,
		serviceRunIds,
		limits.maximumDeletionGenerations + 1,
	)
	if (generations.size > limits.maximumDeletionGenerations) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val generationsByRun = generations.associateBy(CellCaptureDeletionGenerationEntity::serviceRunId)
	if (generationsByRun.size != generations.size || generations.any {
		!it.isCompatibleSelectedCellGeneration(logicalTrackingId, serviceRunIds, state)
	}) {
		selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val latestDurableTimeMs = authenticateSelectedCellFenceClosure(
		logicalTrackingId, serviceRunIds, state, generationsByRun,
	)

	return generationsByRun to latestDurableTimeMs
}

private suspend fun AppDatabase.authenticateSelectedCellFenceClosure(
	logicalTrackingId: String, serviceRunIds: List<String>, state: SourceEvidenceState,
	generationsByRun: Map<String, CellCaptureDeletionGenerationEntity>,
): Long {
	val scopeByRun = serviceRunIds.associateWith { runId ->
		SourceDeletionFenceEntity.logicalServiceRunIdentity(
			CELL_SOURCE,
			CAPTURE_PURPOSE,
			logicalTrackingId,
			runId,
		)
	}
	val fences = trackingHistoryReadDao().deletionFences(
		CELL_SOURCE,
		CAPTURE_PURPOSE,
		SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeByRun.values.toList(),
	)
	val fenceByScope = fences.associateBy(SourceDeletionFenceEntity::scopeIdentityDigest)
	if (fenceByScope.size != fences.size || fences.any {
		it.scopeIdentityDigest !in scopeByRun.values ||
			!state.acceptsRetainedEpoch(it.collectedDataEpoch) || it.fenceGeneration != 1L
	}) {
		selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (serviceRunIds.any { runId ->
		val generation = generationsByRun[runId]
		val fence = fenceByScope[scopeByRun.getValue(runId)]
		(generation == null) != (fence == null) ||
			(generation != null && generation.collectedDataEpoch != fence?.collectedDataEpoch)
	}) {
		selectedBlock(CellCapturedSelectedDeletionBlockedReason.DELETION_FENCE_CONFLICT)
	}
	val latestDurableTimeMs = maxOf(
		generationsByRun.values.maxOfOrNull(CellCaptureDeletionGenerationEntity::updatedAtMs) ?: 0L,
		fences.maxOfOrNull(SourceDeletionFenceEntity::deletedAtMs) ?: 0L,
	)
	return latestDurableTimeMs
}

private fun CellCaptureDeletionGenerationEntity.isCompatibleSelectedCellGeneration(
	logicalId: String, runIds: List<String>, state: SourceEvidenceState,
): Boolean = logicalTrackingId == logicalId && serviceRunId in runIds &&
	state.acceptsRetainedEpoch(collectedDataEpoch) && generation == 1L

private suspend fun AppDatabase.loadSelectedCellRevisionPage(
	logicalTrackingId: String, serviceRunIds: List<String>, limits: CellCapturedMaintenanceLimits,
	cursor: CellSelectedRevisionCursor?, remaining: Int,
): List<CellCapturedFactRevisionEntity> {
	val page = cellCapturedFactDao().historyRevisionPage(
		serviceRunIds,
		listOf(logicalTrackingId),
		minOf(limits.revisionPageSize, remaining + 1),
		cursor?.projectionId,
		cursor?.projectionVersion,
		cursor?.logicalFactId,
		cursor?.semanticRevision,
	)
	if (page.size > remaining) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	if (page.any {
		it.logicalTrackingId != logicalTrackingId || it.serviceRunId !in serviceRunIds
	}) {
		selectedBlock(CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return page
}
