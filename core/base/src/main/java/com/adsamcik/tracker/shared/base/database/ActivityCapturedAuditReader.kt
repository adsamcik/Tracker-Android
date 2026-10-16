package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract

/**
 * Produces the immutable, fully authenticated captured-Activity snapshot shared by source-owned
 * maintenance and portable read paths. Callers must keep the returned value inside their Room
 * transaction; it deliberately exposes no mutation operation.
 */
internal suspend fun AppDatabase.auditCapturedActivityFacts(
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
	requireOwnerWhenEmpty: Boolean = true,
	selectedScope: ActivityCapturedAuditScope? = null,
): ActivityCapturedFactAudit {
	val dao = activityCapturedFactDao()
	if (selectedScope == null) {
		requireSupportedActivityCapturedPayload(dao)
	}

	val owner = sourceDestinationOwnerDao().get(
		ACTIVITY_CAPTURED_SOURCE_KIND,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
	)
	val ownerValid = owner.isCanonicalCapturedActivityOwner()
	val lineages = loadActivityCapturedLineages(limits, checkpoint, selectedScope)
	if ((requireOwnerWhenEmpty || lineages.isNotEmpty()) && !ownerValid) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED)
	}

	val cursors = loadActivityCapturedCursors(limits, selectedScope)
	requireActivityCapturedCountTotals(lineages, cursors, selectedScope)
	if (cursors.size != lineages.size || cursors.keys != lineages.mapTo(mutableSetOf()) {
			lineage -> lineage.logicalWindowId
		}
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	for (lineage in lineages) {
		authenticateActivityCapturedLineageAuthority(lineage, cursors[lineage.logicalWindowId], limits, checkpoint)
	}
	return ActivityCapturedFactAudit(
		lineages,
		maxOf(
			lineages.maxOfOrNull { lineage -> lineage.revisions.maxOf { it.revision.appliedAtMs } }
				?: 0L,
			cursors.values.maxOfOrNull(ActivityCapturedWindowCursorEntity::updatedAtMs) ?: 0L,
		),
	)
}

private fun authenticateActivityCapturedLineage(
	revisions: List<ActivityCapturedPersistedRevision>,
	limits: ActivityCapturedMaintenanceLimits,
): ActivityCapturedLineage {
	if (revisions.isEmpty() || revisions.size > limits.maximumRevisions ||
		!ActivityCapturedFactIntegrity.hasCompleteRevisionLineage(revisions.map { it.revision })
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	var earliestWallTimeMs: Long? = null
	for (persisted in revisions) {
		val revision = persisted.revision
		if (revision.logicalWindowId != ActivityCapturedFactIntegrity.logicalWindowId(revision) ||
			revision.mutationId != ActivityCapturedFactIntegrity.mutationId(
				revision.logicalWindowId,
				revision.semanticRevision,
			) || !persisted.hasCanonicalFragmentsAndEvidence() ||
			revision.effectChecksum != ActivityCapturedFactIntegrity.effectChecksum(
				revision,
				persisted.fragments,
				persisted.evidence,
			)
		) {
			blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val revisionEarliest = CanonicalRetentionBounds.activityLineage(persisted.fragments)
		earliestWallTimeMs = listOfNotNull(earliestWallTimeMs, revisionEarliest).minOrNull()
	}
	return ActivityCapturedLineage(
		revisions.first().revision.logicalWindowId,
		revisions,
		earliestWallTimeMs,
	)
}

private suspend fun AppDatabase.loadActivityCapturedCursors(
	limits: ActivityCapturedMaintenanceLimits,
	selectedScope: ActivityCapturedAuditScope? = null,
): Map<String, ActivityCapturedWindowCursorEntity> {
	val rows = mutableListOf<ActivityCapturedWindowCursorEntity>()
	var after: String? = null
	while (true) {
		val remaining = limits.maximumCursors - rows.size
		if (remaining <= 0) {
			val overflow = loadActivityCapturedCursorPage(selectedScope, after, 1)
			if (overflow.isNotEmpty()) {
				throw ActivityCapturedMaintenanceLimitExceeded()
			}
			break
		}
		val page = loadActivityCapturedCursorPage(
			selectedScope,
			after,
			minOf(remaining, CURSOR_PAGE_SIZE),
		)
		if (page.isEmpty()) {
			break
		}
		requireActivityCapturedCursorPage(page, selectedScope)
		rows += page
		after = page.last().logicalWindowId
		if (page.size < minOf(remaining, CURSOR_PAGE_SIZE)) {
			break
		}
	}
	val byId = rows.associateBy(ActivityCapturedWindowCursorEntity::logicalWindowId)
	if (byId.size != rows.size) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return byId
}

/** Normal pages and overflow probes must use the same complete or selected cursor scope. */
private suspend fun AppDatabase.loadActivityCapturedCursorPage(
	selectedScope: ActivityCapturedAuditScope?,
	after: String?,
	limit: Int,
): List<ActivityCapturedWindowCursorEntity> = selectedScope?.let { scope ->
	activityCapturedFactDao().selectedCursorPage(
		logicalTrackingId = scope.logicalTrackingId,
		serviceRunIds = scope.runs.keys.toList(),
		sessionSegmentIds = scope.runs.values.map(ActivityCapturedSelectedRunScope::sessionSegmentId),
		afterLogicalWindowId = after,
		limit = limit,
	)
} ?: activityCapturedFactDao().maintenanceCursorPage(
	writerProjectionId = ACTIVITY_CAPTURED_WRITER_ID,
	writerProjectionVersion = ACTIVITY_CAPTURED_WRITER_VERSION,
	afterLogicalWindowId = after,
	limit = limit,
)

private suspend fun AppDatabase.loadActivityCapturedLineages(
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
	selectedScope: ActivityCapturedAuditScope?,
): List<ActivityCapturedLineage> {
	val lineages = mutableListOf<ActivityCapturedLineage>()
	var afterWindowId: String? = null
	var afterRevision: Long? = null
	var revisionCount = 0
	var fragmentCount = 0
	var evidenceCount = 0
	var current = mutableListOf<ActivityCapturedPersistedRevision>()
	while (true) {
		val page = loadActivityCapturedRevisionPage(selectedScope, afterWindowId, afterRevision, limits)
		if (page.isEmpty()) {
			break
		}
		revisionCount = Math.addExact(revisionCount, page.size)
		limits.requireRevisionCapacity(revisionCount)
		checkpoint(ActivityCapturedMaintenanceCheckpoint.REVISION_PAGE_LOADED)
		for (revision in page) {
			requireActivityCapturedRevisionScope(revision, selectedScope)
			if (current.isNotEmpty() && current.last().revision.logicalWindowId != revision.logicalWindowId) {
				appendActivityCapturedLineage(lineages, current, limits)
				current = mutableListOf()
			}
			val persisted = readActivityCapturedRevisionPayload(revision, limits)
			fragmentCount = Math.addExact(fragmentCount, persisted.fragments.size)
			evidenceCount = Math.addExact(evidenceCount, persisted.evidence.size)
			limits.requirePayloadCapacity(fragmentCount, evidenceCount)
			current += persisted
		}
		afterWindowId = page.last().logicalWindowId
		afterRevision = page.last().semanticRevision
		if (page.size < limits.revisionPageSize) {
			break
		}
	}
	if (current.isNotEmpty()) {
		appendActivityCapturedLineage(lineages, current, limits)
	}
	return lineages
}

private fun appendActivityCapturedLineage(
	lineages: MutableList<ActivityCapturedLineage>, current: List<ActivityCapturedPersistedRevision>,
	limits: ActivityCapturedMaintenanceLimits,
) {
	lineages += authenticateActivityCapturedLineage(current, limits)
	limits.requireWindowCapacity(lineages.size)
}

private fun requireActivityCapturedRevisionScope(
	revision: ActivityCapturedWindowRevisionEntity, selectedScope: ActivityCapturedAuditScope?,
) {
	if (revision.writerProjectionId != ACTIVITY_CAPTURED_WRITER_ID ||
		revision.writerProjectionVersion != ACTIVITY_CAPTURED_WRITER_VERSION ||
		selectedScope?.containsExactly(revision) == false
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.requireActivityCapturedCountTotals(
	lineages: List<ActivityCapturedLineage>,
	cursors: Map<String, ActivityCapturedWindowCursorEntity>,
	selectedScope: ActivityCapturedAuditScope?,
) {
	val dao = activityCapturedFactDao()
	val revisionCount = lineages.sumOf { it.revisions.size.toLong() }
	val fragmentCount = lineages.sumOf { lineage ->
		lineage.revisions.sumOf { it.fragments.size.toLong() }
	}
	val evidenceCount = lineages.sumOf { lineage ->
		lineage.revisions.sumOf { it.evidence.size.toLong() }
	}
	val runIds = selectedScope?.runs?.keys?.toList()
	val segmentIds = selectedScope?.runs?.values?.map(
		ActivityCapturedSelectedRunScope::sessionSegmentId,
	)
	val selectedWindowIds = (lineages.map(ActivityCapturedLineage::logicalWindowId) + cursors.keys)
		.distinct()
	val exactRevisionCount = if (selectedScope == null) {
		dao.revisionCount()
	} else {
		dao.selectedRevisionCount(
			selectedScope.logicalTrackingId,
			requireNotNull(runIds),
			requireNotNull(segmentIds),
		)
	}
	val (exactFragmentCount, exactEvidenceCount) = activityCapturedPayloadCounts(
		selectedScope, selectedWindowIds, runIds, segmentIds,
	)
	val exactCursorCount = if (selectedScope == null) {
		dao.cursorCount()
	} else {
		dao.selectedCursorCount(
			selectedScope.logicalTrackingId,
			requireNotNull(runIds),
			requireNotNull(segmentIds),
		)
	}
	if (exactRevisionCount != revisionCount ||
		exactFragmentCount != fragmentCount ||
		exactEvidenceCount != evidenceCount ||
		exactCursorCount != cursors.size.toLong()
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun requireSupportedActivityCapturedPayload(
	dao: com.adsamcik.tracker.shared.base.database.dao.ActivityCapturedFactDao,
) {
	if (
		dao.unsupportedRevisionCount(ACTIVITY_CAPTURED_WRITER_ID, ACTIVITY_CAPTURED_WRITER_VERSION) != 0L ||
		dao.unsupportedFragmentCount(ACTIVITY_CAPTURED_WRITER_ID, ACTIVITY_CAPTURED_WRITER_VERSION) != 0L ||
		dao.unsupportedEvidenceCount(ACTIVITY_CAPTURED_WRITER_ID, ACTIVITY_CAPTURED_WRITER_VERSION) != 0L ||
		dao.unsupportedCursorCount(ACTIVITY_CAPTURED_WRITER_ID, ACTIVITY_CAPTURED_WRITER_VERSION) != 0L
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)
	}
}

private fun SourceDestinationOwnerEntity?.isCanonicalCapturedActivityOwner(): Boolean = this != null &&
	owner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS &&
	ownerGeneration.let(SourceWriterGenerationContract::bindingGenerationForCanonicalOwner)
		?.let(SourceWriterGenerationContract::captureModeMaskForKnownBinding) != null

private suspend fun AppDatabase.authenticateActivityCapturedLineageAuthority(
	lineage: ActivityCapturedLineage, cursor: ActivityCapturedWindowCursorEntity?,
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
) {
	val latest = lineage.revisions.last().revision
	if (cursor == null || !cursor.matchesCapturedActivityScope(latest) ||
		!cursor.matchesCapturedActivityPublication(latest)
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	authenticateActivityCapturedAuthority(latest, limits)
	checkpoint(ActivityCapturedMaintenanceCheckpoint.LINEAGE_AUTHENTICATED)
}

private suspend fun AppDatabase.loadActivityCapturedRevisionPage(
	selectedScope: ActivityCapturedAuditScope?, afterWindowId: String?, afterRevision: Long?,
	limits: ActivityCapturedMaintenanceLimits,
): List<ActivityCapturedWindowRevisionEntity> {
	val dao = activityCapturedFactDao()
	return selectedScope?.let { scope ->
		dao.selectedRevisionPage(
			logicalTrackingId = scope.logicalTrackingId,
			serviceRunIds = scope.runs.keys.toList(),
			sessionSegmentIds = scope.runs.values.map(
				ActivityCapturedSelectedRunScope::sessionSegmentId,
			),
			afterLogicalWindowId = afterWindowId,
			afterSemanticRevision = afterRevision,
			limit = limits.revisionPageSize,
		)
	} ?: dao.maintenanceRevisionPage(
		writerProjectionId = ACTIVITY_CAPTURED_WRITER_ID,
		writerProjectionVersion = ACTIVITY_CAPTURED_WRITER_VERSION,
		afterLogicalWindowId = afterWindowId,
		afterSemanticRevision = afterRevision,
		limit = limits.revisionPageSize,
	)
}

private suspend fun AppDatabase.readActivityCapturedRevisionPayload(
	revision: ActivityCapturedWindowRevisionEntity, limits: ActivityCapturedMaintenanceLimits,
): ActivityCapturedPersistedRevision {
	val dao = activityCapturedFactDao()
	val fragments = dao.maintenanceFragments(
		ACTIVITY_CAPTURED_WRITER_ID,
		ACTIVITY_CAPTURED_WRITER_VERSION,
		revision.logicalWindowId,
		revision.semanticRevision,
		limits.maximumFragmentsPerRevision + 1,
	)
	val evidence = dao.maintenanceEvidence(
		ACTIVITY_CAPTURED_WRITER_ID,
		ACTIVITY_CAPTURED_WRITER_VERSION,
		revision.logicalWindowId,
		revision.semanticRevision,
		limits.maximumEvidencePerRevision + 1,
	)
	if (fragments.size > limits.maximumFragmentsPerRevision ||
		evidence.size > limits.maximumEvidencePerRevision
	) {
		throw ActivityCapturedMaintenanceLimitExceeded()
	}
	return ActivityCapturedPersistedRevision(revision, fragments, evidence)
}

private suspend fun AppDatabase.activityCapturedPayloadCounts(
	selectedScope: ActivityCapturedAuditScope?, selectedWindowIds: List<String>,
	runIds: List<String>?, segmentIds: List<Long>?,
): Pair<Long, Long> {
	val dao = activityCapturedFactDao()
	val exactFragmentCount = if (selectedScope == null) {
		dao.fragmentCount()
	} else {
		if (selectedWindowIds.isEmpty()) {
			dao.selectedFragmentCount(
				selectedScope.logicalTrackingId,
				requireNotNull(runIds),
				requireNotNull(segmentIds),
			)
		} else {
			dao.payloadFragmentCountForWindows(selectedWindowIds)
		}
	}
	val exactEvidenceCount = if (selectedScope == null) {
		dao.evidenceCount()
	} else {
		if (selectedWindowIds.isEmpty()) {
			dao.selectedEvidenceCount(
				selectedScope.logicalTrackingId,
				requireNotNull(runIds),
				requireNotNull(segmentIds),
			)
		} else {
			dao.payloadEvidenceCountForWindows(selectedWindowIds)
		}
	}
	return exactFragmentCount to exactEvidenceCount
}

private fun requireActivityCapturedCursorPage(
	page: List<ActivityCapturedWindowCursorEntity>, selectedScope: ActivityCapturedAuditScope?,
) {
	if (page != page.sortedBy(ActivityCapturedWindowCursorEntity::logicalWindowId) ||
		page.distinctBy(ActivityCapturedWindowCursorEntity::logicalWindowId).size != page.size
	) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (page.any { cursor ->
			cursor.writerProjectionId != ACTIVITY_CAPTURED_WRITER_ID ||
				cursor.writerProjectionVersion != ACTIVITY_CAPTURED_WRITER_VERSION ||
				selectedScope?.containsExactly(cursor) == false
		}) {
		blockCapturedActivityRetention(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private const val CURSOR_PAGE_SIZE = 256
