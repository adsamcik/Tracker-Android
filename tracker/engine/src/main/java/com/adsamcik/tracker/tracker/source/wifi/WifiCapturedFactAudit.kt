package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthenticator
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURE_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CURSOR_PAGE_SIZE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.DELETION_GENERATION_PAGE_SIZE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.DESTINATION
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.FIRST_DELETION_GENERATION
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.MAX_NATIVE_DETAIL_CHILDREN
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.OWNER
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_SOURCE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WRITER_ID
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WRITER_VERSION
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun WifiCapturedFactMaintenance.audit(
	evidence: SourceEvidenceState,
	limits: WifiCapturedMaintenanceLimits,
	checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
): WifiCapturedAudit {
	val (lineages, revisionCount) = loadAuthenticatedLineages(limits, checkpoint)
	val owner = database.sourceDestinationOwnerDao().get(WIFI_SOURCE, DESTINATION)
	if (lineages.isNotEmpty() &&
		(owner?.owner != OWNER || !owner.ownerGeneration.isKnownCapturedWriterOwnerGeneration())
	) {
		block(WifiCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED)
	}

	val cursors = loadCursors(limits)
	val generations = loadDeletionGenerations(limits)
	authenticateAuditCounts(revisionCount, lineages, cursors, generations)

	val wal = auditWal(evidence, limits, checkpoint)
	val lineagesById = lineages.associateBy(WifiCapturedLineage::logicalFactId)
	if (lineagesById.size != lineages.size) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val reuseAuthorityById = mutableMapOf<String, WifiAggregateReuseAuthority>()
	val aggregateById = mutableMapOf<String, WifiIdentityFreeAggregate>()
	for (lineage in lineages) {
		currentCoroutineContext().ensureActive()
		val latest = lineage.revisions.last()
		val walAuthority = authenticateCurrentLineage(latest, cursors[lineage.logicalFactId], evidence, wal)
		reuseAuthorityById[lineage.logicalFactId] = walAuthority.reuseAuthority
		aggregateById[lineage.logicalFactId] = requireNotNull(walAuthority.derivedAggregate).aggregate
		checkpoint(WifiCapturedMaintenanceCheckpoint.LINEAGE_AUTHENTICATED)
	}
	authenticateDependencies(lineages, lineagesById, reuseAuthorityById, aggregateById)
	authenticateDeletionGenerations(database, lineages, generations, evidence.collectedDataEpoch)

	return WifiCapturedAudit(
		lineages = lineages,
		lineagesById = lineagesById,
		cursorsById = cursors,
		aggregateById = aggregateById,
		generationByScope = generations.associateBy { generation ->
			WifiCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
		},
		wal = wal,
		// auditWal advances time only for fully authenticated current capture-bearing WAL.
		latestDurableTimeMs = latestWifiDurableTime(owner?.updatedAtMs, wal, lineages, cursors, generations),
	)
}


private suspend fun WifiCapturedFactMaintenance.requireSupportedWifiCapturedRevisions() {
	val dao = database.wifiCapturedFactDao()
	if (dao.unsupportedRevisionCount(WRITER_ID, WRITER_VERSION) != 0L ||
		dao.unsupportedCursorCount(WRITER_ID, WRITER_VERSION) != 0L
	) {
		block(WifiCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)
	}
}

private suspend fun WifiCapturedFactMaintenance.loadAuthenticatedLineages(
	limits: WifiCapturedMaintenanceLimits,
	checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
): Pair<List<WifiCapturedLineage>, Int> {
	requireSupportedWifiCapturedRevisions()
	val dao = database.wifiCapturedFactDao()

	val lineages = mutableListOf<WifiCapturedLineage>()
	var afterLogicalFactId: String? = null
	var afterSemanticRevision: Long? = null
	var revisionCount = 0
	var current = mutableListOf<WifiCapturedFactRevisionEntity>()
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
			throw WifiCapturedMaintenanceLimitExceeded()
		}
		checkpoint(WifiCapturedMaintenanceCheckpoint.REVISION_PAGE_LOADED)
		current = appendWifiLineagePage(page, current, lineages, limits)
		afterLogicalFactId = page.last().logicalFactId
		afterSemanticRevision = page.last().semanticRevision
		if (page.size < limits.revisionPageSize) {
			break
		}
	}
	if (current.isNotEmpty()) {
		lineages += authenticateLineage(current)
	}
	if (lineages.size > limits.maximumLogicalFacts) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}

	return lineages to revisionCount
}

private suspend fun WifiCapturedFactMaintenance.authenticateAuditCounts(
	revisionCount: Int,
	lineages: List<WifiCapturedLineage>,
	cursors: Map<String, WifiCapturedFactCursorEntity>,
	generations: List<WifiCaptureDeletionGenerationEntity>,
) {
	val dao = database.wifiCapturedFactDao()
	if (dao.revisionCount() != revisionCount.toLong() ||
		dao.cursorCount() != cursors.size.toLong() ||
		dao.deletionGenerationCount() != generations.size.toLong()
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (cursors.size != lineages.size ||
		cursors.keys != lineages.mapTo(mutableSetOf(), WifiCapturedLineage::logicalFactId)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun WifiCapturedFactMaintenance.authenticateCurrentLineage(
	latest: WifiCapturedFactRevisionEntity,
	cursor: WifiCapturedFactCursorEntity?,
	evidence: SourceEvidenceState,
	wal: WifiCapturedWalAudit,
): AuthenticatedWifiWal {
	if (cursor == null || !cursor.matchesCurrent(latest)) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (latest.collectedDataEpoch != evidence.collectedDataEpoch ||
		latest.sourceAdmissionOrdinal <= evidence.deletedSourceEventHighWaterOrdinal
	) {
		block(WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
	}
	return authenticateLineageWal(latest, wal)
}
/** Portable reads also admit explicitly restored data epochs; ordinary maintenance remains current-epoch only. */
internal suspend fun WifiCapturedFactMaintenance.authenticatePortableCurrentLineage(
	latest: WifiCapturedFactRevisionEntity,
	cursor: WifiCapturedFactCursorEntity?,
	evidence: SourceEvidenceState,
	wal: WifiCapturedWalAudit,
): AuthenticatedWifiWal {
	if (cursor?.matchesCurrent(latest) != true) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!evidence.acceptsRetainedEpoch(latest.collectedDataEpoch) ||
		latest.sourceAdmissionOrdinal <= evidence.deletedSourceEventHighWaterOrdinal
	) {
		block(WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
	}
	return authenticateLineageWal(latest, wal)
}

/** Bind one current fact to its exact authenticated WAL group and saved child details. */
private suspend fun WifiCapturedFactMaintenance.authenticateLineageWal(
	latest: WifiCapturedFactRevisionEntity,
	wal: WifiCapturedWalAudit,
): AuthenticatedWifiWal {
	val walAuthority = wal.captureByEventId[latest.sourceEventId]?.singleOrNull {
		it.authority.logicalTrackingId.value == latest.logicalTrackingId &&
			it.authority.serviceRunId.value == latest.serviceRunId &&
			it.authority.sessionManifestRevision == latest.manifestRevision &&
			it.authority.sourcePolicyRevision == latest.sourcePolicyRevision &&
			it.authority.captureConsentEpoch == latest.captureConsentEpoch
	}
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (!latest.matchesAuthenticatedWal(walAuthority)) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	requireAuthenticDetails(latest, walAuthority)
	return walAuthority
}

/** Same bounded child predicate used by writing and history, including missing-all v6 groups. */
private suspend fun WifiCapturedFactMaintenance.requireAuthenticDetails(
	fact: WifiCapturedFactRevisionEntity,
	wal: AuthenticatedWifiWal,
) {
	if (wal.evidence.payloadVersion != WIFI_WAL_PAYLOAD_VERSION) {
		return
	}
	val children = database.radioObservationChildDao().wifiSessionChildren(
		WRITER_ID, WRITER_VERSION, fact.logicalFactId, fact.semanticRevision,
		MAX_NATIVE_DETAIL_CHILDREN + 1,
	)
	if (!WifiWalCarrierAuthenticator.authenticates(fact, wal.evidence.asWalEntity(), children)) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

internal fun authenticateLineage(
	revisions: List<WifiCapturedFactRevisionEntity>,
): WifiCapturedLineage {
	if (revisions.isEmpty()) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val first = revisions.first()
	if (first.supersedesSemanticRevision != null ||
		!WifiCapturedFactRevisionIntegrity.hasValidRetainedLineage(revisions) ||
		revisions.any { !it.hasExactRevisionIdentity() }
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return WifiCapturedLineage(
		logicalFactId = first.logicalFactId,
		revisions = revisions,
		earliestPossibleWallTimeMs = first.earliestCoveredWallTimeMs()
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE),
		aggregateOwnerLogicalFactId = first.aggregateOwnerLogicalFactId,
		aggregateOwnerSemanticRevision = first.aggregateOwnerSemanticRevision,
		aggregateOwnerCursorRevision = first.aggregateOwnerCursorRevision,
	)
}

private suspend fun WifiCapturedFactMaintenance.loadCursors(
	limits: WifiCapturedMaintenanceLimits,
): Map<String, WifiCapturedFactCursorEntity> {
	val rows = mutableListOf<WifiCapturedFactCursorEntity>()
	var after: String? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumCursors - rows.size
		if (remaining <= 0) {
			requireNoFurtherWifiCursor(after)
			break
		}
		val pageSize = minOf(remaining, CURSOR_PAGE_SIZE)
		val page = database.wifiCapturedFactDao().maintenanceCursorPage(
			WRITER_ID,
			WRITER_VERSION,
			after,
			pageSize,
		)
		if (page.isEmpty()) {
			break
		}
		requireOrderedWifiCursorPage(page)
		rows += page
		after = page.last().logicalFactId
		if (page.size < pageSize) {
			break
		}
	}
	return rows.associateBy(WifiCapturedFactCursorEntity::logicalFactId).also { result ->
		if (result.size != rows.size) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
}

private suspend fun WifiCapturedFactMaintenance.loadDeletionGenerations(
	limits: WifiCapturedMaintenanceLimits,
): List<WifiCaptureDeletionGenerationEntity> = loadBoundedMaintenanceRows(
	maximumRows = limits.maximumDeletionGenerations,
	pageSizeLimit = DELETION_GENERATION_PAGE_SIZE,
	nextCursor = { it.logicalTrackingId to it.serviceRunId },
) { after, limit ->
	database.wifiCapturedFactDao().maintenanceDeletionGenerationPage(after?.first, after?.second, limit)
}

/** Shared historical boundary; the first authorization member retains the existing shape check. */
internal fun authenticateDependencies(
	lineages: List<WifiCapturedLineage>,
	lineagesById: Map<String, WifiCapturedLineage>,
	reuseAuthorityById: Map<String, WifiAggregateReuseAuthority>,
	aggregateById: Map<String, WifiIdentityFreeAggregate>,
) {
	for (lineage in lineages) {
		val ownerId = lineage.aggregateOwnerLogicalFactId ?: continue
		val ownerRevision = lineage.aggregateOwnerSemanticRevision
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val ownerCursorRevision = lineage.aggregateOwnerCursorRevision
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val owner = lineagesById[ownerId]
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val referenced = owner.revisions.singleOrNull { revision ->
			revision.semanticRevision == ownerRevision
		}
		val dependent = lineage.revisions.last()
		val ownerAuthority = reuseAuthorityById[owner.logicalFactId]
		val dependentAuthority = reuseAuthorityById[lineage.logicalFactId]
		val dependentAggregate = aggregateById[lineage.logicalFactId]
		if (!owner.authenticatesWifiAggregateReference(referenced, ownerCursorRevision)) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		requireWifiAggregateDependency(
			requireNotNull(referenced), dependent, ownerAuthority, dependentAuthority, dependentAggregate,
		)
	}
}

internal fun authenticatePortableDeletionGenerations(
	lineages: List<WifiCapturedLineage>,
	generations: List<WifiCaptureDeletionGenerationEntity>,
	evidence: SourceEvidenceState,
) {
	val scopesWithFacts = lineages.mapTo(mutableSetOf(), WifiCapturedLineage::scope)
	val generationByScope = generations.associateBy { generation ->
		WifiCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
	}
	if (generationByScope.size != generations.size) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (lineages.any { it.revisions.first().scopeDeletionGeneration != 0L || it.scope in generationByScope }) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (generations.any { generation ->
		!evidence.acceptsRetainedEpoch(generation.collectedDataEpoch) || generation.generation != FIRST_DELETION_GENERATION ||
			WifiCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId) in scopesWithFacts
	}) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun authenticateDeletionGenerations(
	database: AppDatabase,
	lineages: List<WifiCapturedLineage>,
	generations: List<WifiCaptureDeletionGenerationEntity>,
	collectedDataEpoch: Long,
) {
	val scopesWithFacts = lineages.groupBy(WifiCapturedLineage::scope)
	val generationByScope = generations.associateBy { generation ->
		WifiCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
	}
	if (generationByScope.size != generations.size) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	for ((scope, scopedLineages) in scopesWithFacts) {
		val declared = scopedLineages.map { lineage ->
			lineage.revisions.first().scopeDeletionGeneration
		}.distinct()
		if (declared != listOf(0L) || generationByScope[scope] != null) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			scope.logicalTrackingId,
			scope.serviceRunId,
		)
		if (database.sourceDeletionFenceDao().contains(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			digest,
		)) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
	for (generation in generations) {
		val scope = WifiCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
		requireWifiDeletionGeneration(generation, collectedDataEpoch, scope, scopesWithFacts)
		val expectedFence = SourceDeletionFenceEntity.createLogicalServiceRun(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			generation.logicalTrackingId,
			generation.serviceRunId,
			generation.generation,
			generation.collectedDataEpoch,
			generation.updatedAtMs,
		)
		val retained = database.sourceDeletionFenceDao().get(
			expectedFence.sourceKind,
			expectedFence.purpose,
			expectedFence.scopeKind,
			expectedFence.scopeIdentityDigest,
		)
		if (retained != expectedFence) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
}

private fun appendWifiLineagePage(
	page: List<WifiCapturedFactRevisionEntity>,
	initial: MutableList<WifiCapturedFactRevisionEntity>,
	lineages: MutableList<WifiCapturedLineage>,
	limits: WifiCapturedMaintenanceLimits,
): MutableList<WifiCapturedFactRevisionEntity> {
	var current = initial
	for (revision in page) {
		if (current.isNotEmpty() && current.last().logicalFactId != revision.logicalFactId) {
			lineages += authenticateLineage(current)
			current = mutableListOf()
			if (lineages.size > limits.maximumLogicalFacts) {
				throw WifiCapturedMaintenanceLimitExceeded()
			}
		}
		current += revision
	}
	return current
}

private suspend fun WifiCapturedFactMaintenance.requireNoFurtherWifiCursor(after: String?) {
	if (database.wifiCapturedFactDao().maintenanceCursorPage(
			WRITER_ID,
			WRITER_VERSION,
			after,
			1,
		).isNotEmpty()
	) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
}

internal suspend fun WifiCapturedFactMaintenance.authenticatePortableWifiLineages(
	lineages: List<WifiCapturedLineage>,
	cursors: Map<String, WifiCapturedFactCursorEntity>,
	evidence: SourceEvidenceState,
	wal: WifiCapturedWalAudit,
): Pair<Map<String, WifiAggregateReuseAuthority>, Map<String, WifiIdentityFreeAggregate>> {
	val reuseAuthorityById = mutableMapOf<String, WifiAggregateReuseAuthority>()
	val aggregateById = mutableMapOf<String, WifiIdentityFreeAggregate>()
	for (lineage in lineages) {
		currentCoroutineContext().ensureActive()
		val latest = lineage.revisions.last()
		val walAuthority = authenticatePortableCurrentLineage(
			latest, cursors[lineage.logicalFactId], evidence, wal,
		)
		reuseAuthorityById[lineage.logicalFactId] = walAuthority.reuseAuthority
		aggregateById[lineage.logicalFactId] =
			requireNotNull(walAuthority.derivedAggregate).aggregate
	}
	return reuseAuthorityById to aggregateById
}
