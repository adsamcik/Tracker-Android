package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionScopeProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.dao.CellCapturedFactDao
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellAggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun AppDatabase.auditCapturedCellFacts(
	evidenceState: SourceEvidenceState,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedFactAudit {
	val dao = cellCapturedFactDao()
	if (dao.unsupportedRevisionCount(WRITER_ID, WRITER_VERSION) != 0L ||
		dao.unsupportedCursorCount(WRITER_ID, WRITER_VERSION) != 0L
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)
	}

	val (lineages, revisionCount) = loadCellCapturedLineages(dao, limits, checkpoint)

	val owner = readCellCapturedFactOwner(lineages.isNotEmpty())

	val cursors = loadCellCapturedCursors(limits)
	val generations = loadCellDeletionGenerations(limits)
	val byId = authenticateCellAuditMaterialization(dao, revisionCount, lineages, cursors, generations)

	val reuseAuthorityById = mutableMapOf<String, CellAggregateReuseAuthority>()
	val aggregateById = mutableMapOf<String, CellHistoricalIdentityFreeAggregate>()
	val canonicalRetentionLowerBoundById = mutableMapOf<String, Long>()
	var latestWalTimeMs = 0L
	for (lineage in lineages) {
		val authenticated = authenticateCellCapturedCurrentLineage(
			lineage, cursors, evidenceState, limits)
		reuseAuthorityById[lineage.logicalFactId] = authenticated.reuseAuthority
		aggregateById[lineage.logicalFactId] = authenticated.aggregate
		canonicalRetentionLowerBoundById[lineage.logicalFactId] =
			authenticated.retentionLowerBoundMs
		latestWalTimeMs = maxOf(latestWalTimeMs, authenticated.walCreatedAtMs)
		checkpoint(CellCapturedMaintenanceCheckpoint.LINEAGE_AUTHENTICATED)
	}
	authenticateCapturedCellAggregateDependencies(lineages, byId, reuseAuthorityById, aggregateById)
	authenticateCellDeletionGenerations(lineages, generations, evidenceState)

	return CellCapturedFactAudit(
		lineages = lineages,
		lineagesById = byId,
		cursorsById = cursors,
		canonicalRetentionLowerBoundById = canonicalRetentionLowerBoundById,
		aggregateById = aggregateById,
		generationByScope = generations.associateBy { generation ->
			CellCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
		},
		latestDurableTimeMs = maxOf(
			owner?.updatedAtMs ?: 0L,
			latestWalTimeMs,
			lineages.maxOfOrNull { lineage -> lineage.revisions.maxOf { it.appliedAtMs } } ?: 0L,
			cursors.values.maxOfOrNull(CellCapturedFactCursorEntity::updatedAtMs) ?: 0L,
			generations.maxOfOrNull(CellCaptureDeletionGenerationEntity::updatedAtMs) ?: 0L,
		),
	)
}

/**
 * Audits the complete retained Cell fact closure that can affect one portable logical entry.
 * Rows are selected from both sides of the logical-entry/service-run identity so a one-sided
 * corruption cannot escape the audit, while unrelated retained history cannot exhaust its bounds.
 */
@Suppress("LongMethod")
internal suspend fun AppDatabase.auditPortableCapturedCellFacts(
	evidenceState: SourceEvidenceState,
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	capturedServiceRunIds: List<String>,
	activationFloorOrdinal: Long,
	admissionCeilingOrdinal: Long,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedFactAudit {
	requirePortableCapturedCellAuditScope(
		logicalTrackingId, serviceRunIds, capturedServiceRunIds, activationFloorOrdinal, admissionCeilingOrdinal,
	)
	val dao = cellCapturedFactDao()
	val revisions = dao.loadPortableCapturedCellRevisions(
		logicalTrackingId, serviceRunIds, capturedServiceRunIds, activationFloorOrdinal, admissionCeilingOrdinal, limits,
	)
	if (revisions.isNotEmpty()) {
		checkpoint(CellCapturedMaintenanceCheckpoint.REVISION_PAGE_LOADED)
	}

	val (lineages, byId) = authenticatePortableCellLineageSet(revisions, limits)

	val owner = readCellCapturedFactOwner(lineages.isNotEmpty())

	val cursors = dao.loadPortableCapturedCellCursors(
		logicalTrackingId, serviceRunIds, capturedServiceRunIds, activationFloorOrdinal, admissionCeilingOrdinal, limits,
	)
	if (cursors.keys != byId.keys) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val (generations, generationByScope) = dao.loadPortableCapturedCellDeletionGenerations(
		logicalTrackingId, serviceRunIds, limits,
	)

	val reuseAuthorityById = mutableMapOf<String, CellAggregateReuseAuthority>()
	val aggregateById = mutableMapOf<String, CellHistoricalIdentityFreeAggregate>()
	val canonicalRetentionLowerBoundById = mutableMapOf<String, Long>()
	var latestWalTimeMs = 0L
	for (lineage in lineages) {
		val authenticated = authenticateCellCapturedCurrentLineage(
			lineage, cursors, evidenceState, limits)
		reuseAuthorityById[lineage.logicalFactId] = authenticated.reuseAuthority
		aggregateById[lineage.logicalFactId] = authenticated.aggregate
		canonicalRetentionLowerBoundById[lineage.logicalFactId] =
			authenticated.retentionLowerBoundMs
		latestWalTimeMs = maxOf(latestWalTimeMs, authenticated.walCreatedAtMs)
		checkpoint(CellCapturedMaintenanceCheckpoint.LINEAGE_AUTHENTICATED)
	}
	// Aggregate owners share the selected capture scope and are therefore included by the closure
	// query even when only their dependent correction is a retained export observation.
	authenticateCapturedCellAggregateDependencies(lineages, byId, reuseAuthorityById, aggregateById)
	authenticateCellDeletionGenerations(lineages, generations, evidenceState)

	return CellCapturedFactAudit(
		lineages = lineages,
		lineagesById = byId,
		cursorsById = cursors,
		canonicalRetentionLowerBoundById = canonicalRetentionLowerBoundById,
		aggregateById = aggregateById,
		generationByScope = generationByScope,
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

private suspend fun loadCellCapturedLineages(
	dao: CellCapturedFactDao,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): Pair<List<CellCapturedLineage>, Int> {
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
		if (page.isEmpty()) {
			break
		}
		revisionCount = Math.addExact(revisionCount, page.size)
		if (revisionCount > limits.maximumRevisions) {
			throw CellCapturedMaintenanceLimitExceeded()
		}
		checkpoint(CellCapturedMaintenanceCheckpoint.REVISION_PAGE_LOADED)
		current = appendCapturedCellRevisionPage(lineages, current, page, limits)
		afterLogicalFactId = page.last().logicalFactId
		afterSemanticRevision = page.last().semanticRevision
		if (page.size < limits.revisionPageSize) {
			break
		}
	}
	if (current.isNotEmpty()) {
		appendAuthenticatedCellLineage(lineages, current, limits)
	}
	return lineages to revisionCount
}

private fun appendCapturedCellRevisionPage(
	lineages: MutableList<CellCapturedLineage>,
	openLineage: MutableList<CellCapturedFactRevisionEntity>,
	page: List<CellCapturedFactRevisionEntity>,
	limits: CellCapturedMaintenanceLimits,
): MutableList<CellCapturedFactRevisionEntity> {
	var current = openLineage
	for (revision in page) {
		if (current.isNotEmpty() && current.last().logicalFactId != revision.logicalFactId) {
			appendAuthenticatedCellLineage(lineages, current, limits)
			current = mutableListOf()
		}
		current += revision
	}
	return current
}

private suspend fun AppDatabase.readCellCapturedFactOwner(hasFacts: Boolean): SourceDestinationOwnerEntity? {
	val owner = sourceDestinationOwnerDao().get(
		CELL_SOURCE,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
	)
	if (hasFacts && (owner == null ||
		owner.owner != SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS ||
		!owner.ownerGeneration.isKnownCapturedWriterOwnerGeneration())
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED)
	}
	return owner
}

private suspend fun authenticateCellAuditMaterialization(
	dao: CellCapturedFactDao,
	revisionCount: Int,
	lineages: List<CellCapturedLineage>,
	cursors: Map<String, CellCapturedFactCursorEntity>,
	generations: List<CellCaptureDeletionGenerationEntity>,
): Map<String, CellCapturedLineage> {
	if (dao.revisionCount() != revisionCount.toLong() ||
		dao.cursorCount() != cursors.size.toLong() ||
		dao.deletionGenerationCount() != generations.size.toLong()
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (cursors.size != lineages.size || cursors.keys != lineages.mapTo(mutableSetOf()) {
			lineage -> lineage.logicalFactId
		}
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val byId = lineages.associateBy(CellCapturedLineage::logicalFactId)
	if (byId.size != lineages.size) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return byId
}

private suspend fun AppDatabase.authenticateCellCapturedCurrentLineage(
	lineage: CellCapturedLineage,
	cursors: Map<String, CellCapturedFactCursorEntity>,
	evidenceState: SourceEvidenceState,
	limits: CellCapturedMaintenanceLimits,
): AuthenticatedCellFactAuthority {
	currentCoroutineContext().ensureActive()
	val latest = lineage.revisions.last()
	val cursor = cursors[lineage.logicalFactId]
	if (cursor == null || !cursor.matchesCurrentCapturedCellRevision(latest)) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!evidenceState.acceptsRetainedEpoch(latest.collectedDataEpoch) ||
		latest.sourceAdmissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
	}
	return authenticateCellCapturedAuthority(latest, limits)
}

private suspend fun AppDatabase.authenticateCellDeletionGenerations(
	lineages: List<CellCapturedLineage>,
	generations: List<CellCaptureDeletionGenerationEntity>,
	evidenceState: SourceEvidenceState,
) {
	val scopesWithFacts = lineages.groupBy(CellCapturedLineage::scope)
	val generationByScope = generations.associateBy { generation ->
		CellCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
	}
	if (generationByScope.size != generations.size) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	for ((scope, scopedLineages) in scopesWithFacts) {
		val declaredGenerations = scopedLineages.map { lineage ->
			lineage.revisions.first().scopeDeletionGeneration
		}.distinct()
		if (declaredGenerations != listOf(0L) || generationByScope[scope] != null) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
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
		) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
	for (generation in generations) {
		val scope = CellCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
		if (!evidenceState.acceptsRetainedEpoch(generation.collectedDataEpoch) || scope in scopesWithFacts) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
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
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
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
			) {
				throw CellCapturedMaintenanceLimitExceeded()
			}
			break
		}
		val pageSize = minOf(remaining, CURSOR_PAGE_SIZE)
		val page = cellCapturedFactDao().maintenanceCursorPage(
			WRITER_ID,
			WRITER_VERSION,
			after,
			pageSize,
		)
		if (page.isEmpty()) {
			break
		}
		requireOrderedCapturedCellCursorPage(page)
		rows += page
		after = page.last().logicalFactId
		if (page.size < pageSize) {
			break
		}
	}
	val result = rows.associateBy(CellCapturedFactCursorEntity::logicalFactId)
	if (result.size != rows.size) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return result
}

private suspend fun AppDatabase.loadCellDeletionGenerations(
	limits: CellCapturedMaintenanceLimits,
): List<CellCaptureDeletionGenerationEntity> = loadBoundedCellMaintenanceRows(
	maximumRows = limits.maximumDeletionGenerations,
	pageSizeLimit = DELETION_GENERATION_PAGE_SIZE,
	nextCursor = { it.logicalTrackingId to it.serviceRunId },
) { after, limit ->
	cellCapturedFactDao().maintenanceDeletionGenerationPage(after?.first, after?.second, limit)
}

private const val CURSOR_PAGE_SIZE = 256

private const val DELETION_GENERATION_PAGE_SIZE = 256

private suspend fun CellCapturedFactDao.loadPortableCapturedCellRevisions(
	logicalTrackingId: String, serviceRunIds: List<String>, capturedServiceRunIds: List<String>,
	activationFloorOrdinal: Long, admissionCeilingOrdinal: Long, limits: CellCapturedMaintenanceLimits,
): List<CellCapturedFactRevisionEntity> {
	val revisions = portableRevisionClosure(
		CELL_SOURCE,
		logicalTrackingId,
		serviceRunIds,
		capturedServiceRunIds,
		activationFloorOrdinal,
		admissionCeilingOrdinal,
		limits.maximumRevisions + 1,
	)
	if (revisions.size > limits.maximumRevisions) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	return revisions
}

private fun authenticatePortableCellLineageSet(
	revisions: List<CellCapturedFactRevisionEntity>, limits: CellCapturedMaintenanceLimits,
): Pair<List<CellCapturedLineage>, Map<String, CellCapturedLineage>> {
	val lineages = revisions.groupBy(CellCapturedFactRevisionEntity::logicalFactId)
		.values
		.map(::authenticateCellCapturedLineage)
	if (lineages.size > limits.maximumLogicalFacts) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val byId = lineages.associateBy(CellCapturedLineage::logicalFactId)
	if (byId.size != lineages.size) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return lineages to byId
}

private suspend fun CellCapturedFactDao.loadPortableCapturedCellCursors(
	logicalTrackingId: String, serviceRunIds: List<String>, capturedServiceRunIds: List<String>,
	activationFloorOrdinal: Long, admissionCeilingOrdinal: Long, limits: CellCapturedMaintenanceLimits,
): Map<String, CellCapturedFactCursorEntity> {
	val cursorRows = portableCursorClosure(
		CELL_SOURCE,
		logicalTrackingId,
		serviceRunIds,
		capturedServiceRunIds,
		activationFloorOrdinal,
		admissionCeilingOrdinal,
		limits.maximumCursors + 1,
	)
	if (cursorRows.size > limits.maximumCursors) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val cursors = cursorRows.associateBy(CellCapturedFactCursorEntity::logicalFactId)
	if (cursors.size != cursorRows.size) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return cursors
}

private suspend fun CellCapturedFactDao.loadPortableCapturedCellDeletionGenerations(
	logicalTrackingId: String, serviceRunIds: List<String>, limits: CellCapturedMaintenanceLimits,
): Pair<List<CellCaptureDeletionGenerationEntity>, Map<CellCapturedRunScope, CellCaptureDeletionGenerationEntity>> {
	val generations = portableDeletionGenerationClosure(
		logicalTrackingId,
		serviceRunIds,
		limits.maximumDeletionGenerations + 1,
	)
	if (generations.size > limits.maximumDeletionGenerations) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val generationByScope = generations.associateBy { generation ->
		CellCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
	}
	if (generationByScope.size != generations.size) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return generations to generationByScope
}

internal fun requirePortableCapturedCellAuditScope(
	logicalTrackingId: String, serviceRunIds: List<String>, capturedServiceRunIds: List<String>,
	activationFloorOrdinal: Long, admissionCeilingOrdinal: Long,
) {
	require(logicalTrackingId.isNotBlank())
	require(serviceRunIds.isNotEmpty() && serviceRunIds.distinct().size == serviceRunIds.size)
	require(capturedServiceRunIds.isNotEmpty() && capturedServiceRunIds.all(serviceRunIds::contains))
	require(capturedServiceRunIds.distinct().size == capturedServiceRunIds.size)
	require(activationFloorOrdinal >= 0L)
	require(admissionCeilingOrdinal >= activationFloorOrdinal)
}

private fun appendAuthenticatedCellLineage(
	lineages: MutableList<CellCapturedLineage>, revisions: List<CellCapturedFactRevisionEntity>,
	limits: CellCapturedMaintenanceLimits,
) {
	lineages += authenticateCellCapturedLineage(revisions)
	if (lineages.size > limits.maximumLogicalFacts) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
}

private fun requireOrderedCapturedCellCursorPage(page: List<CellCapturedFactCursorEntity>) {
	if (page != page.sortedBy(CellCapturedFactCursorEntity::logicalFactId) ||
		page.distinctBy(CellCapturedFactCursorEntity::logicalFactId).size != page.size
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

internal suspend fun AppDatabase.loadCellCapturedRetentionProofsForSourceErase(
	limits: CellCapturedMaintenanceLimits,
): List<CapturedRadioFactRetentionProofEntity> = loadBoundedCellMaintenanceRows(
	maximumRows = limits.maximumLogicalFacts,
	pageSizeLimit = limits.revisionPageSize,
	nextCursor = { it.sourceAdmissionOrdinal to it.logicalFactId },
) { after, limit ->
	capturedRadioFactRetentionProofDao().sourceErasePage(
		CELL_SOURCE, after?.first, after?.second, limit,
	)
}

internal suspend fun AppDatabase.loadCellSourceEventRetentionProofsForSourceErase(
	limits: CellCapturedMaintenanceLimits,
): List<SourceEventRetentionProofEntity> = loadBoundedCellMaintenanceRows(
	maximumRows = limits.maximumWalEvents,
	pageSizeLimit = limits.revisionPageSize,
	nextCursor = SourceEventRetentionProofEntity::sourceAdmissionOrdinal,
) { after, limit ->
	sourceEventRetentionProofDao().sourceCaptureErasePage(
		CELL_SOURCE, SourceBrokerPurpose.MASK_SESSION_CAPTURE, after, limit,
	)
}

internal suspend fun AppDatabase.loadCellSourceEventRetentionScopeProofsForSourceErase(
	limits: CellCapturedMaintenanceLimits,
): List<SourceEventRetentionScopeProofEntity> = loadBoundedCellMaintenanceRows(
	maximumRows = limits.maximumDeletionGenerations,
	pageSizeLimit = limits.revisionPageSize,
	nextCursor = SourceEventRetentionScopeProofEntity::logicalRunScopeDigest,
) { after, limit ->
	sourceEventRetentionScopeProofDao().sourceErasePage(CELL_SOURCE, after, limit)
}

/** Shared keyset traversal; each loader retains its concrete query, cursor and work bounds. */
private suspend fun <Row, Cursor> loadBoundedCellMaintenanceRows(
	maximumRows: Int,
	pageSizeLimit: Int,
	nextCursor: (Row) -> Cursor,
	readPage: suspend (Cursor?, Int) -> List<Row>,
): List<Row> {
	val rows = mutableListOf<Row>()
	var after: Cursor? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = maximumRows - rows.size
		if (remaining <= 0) {
			if (readPage(after, 1).isNotEmpty()) {
				throw CellCapturedMaintenanceLimitExceeded()
			}
			break
		}
		val pageSize = minOf(remaining, pageSizeLimit)
		val page = readPage(after, pageSize)
		if (page.isEmpty()) {
			break
		}
		rows += page
		after = nextCursor(page.last())
		if (page.size < pageSize) {
			break
		}
	}
	return rows
}

