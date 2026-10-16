package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.dao.WifiCapturedFactDao
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.persistCapturedRadioFactRetentionProofs
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WRITER_ID
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WRITER_VERSION
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun WifiCapturedFactMaintenance.persistRetentionProofs(
	selected: List<WifiCapturedLineage>,
	audit: WifiCapturedAudit,
	beforeMs: Long,
	markedAtMs: Long,
	directlyExpiredIds: Set<String>,
) {
	val retentionProofs = selected.map { lineage ->
		val fact = lineage.revisions.last()
		val owner = lineage.aggregateOwnerLogicalFactId?.let { ownerId ->
			audit.lineagesById[ownerId]?.revisions?.last()
		}
		val ownerCursor = owner?.let { audit.cursorsById[it.logicalFactId] }
		CapturedRadioFactRetentionProofEntity.createWifi(
			fact = fact,
			cursor = audit.cursorsById.getValue(fact.logicalFactId),
			aggregateOwner = owner,
			aggregateOwnerCursor = ownerCursor,
			retainedFromMs = beforeMs,
			retainedAtMs = markedAtMs,
			reason = if (fact.logicalFactId in directlyExpiredIds) {
				CapturedRadioFactRetentionProofEntity.REASON_DIRECT_FLOOR
			} else {
				CapturedRadioFactRetentionProofEntity.REASON_DEPENDENCY_CLOSURE
			},
		)
	}
	try {
		database.persistCapturedRadioFactRetentionProofs(retentionProofs)
	} catch (_: IllegalStateException) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

/** Both cleanup paths remove the same authenticated child/cursor/revision batch atomically. */
internal suspend fun WifiCapturedFactMaintenance.deleteExactLineageBatch(
	dao: WifiCapturedFactDao,
	batch: List<WifiCapturedLineage>,
	onMismatch: () -> Nothing,
): Int {
	currentCoroutineContext().ensureActive()
	val ids = batch.map(WifiCapturedLineage::logicalFactId)
	ids.forEach { id -> database.radioObservationChildDao().deleteWifiSessionFact(
		WRITER_ID, WRITER_VERSION, id,
	) }
	if (dao.deleteExactCursors(WRITER_ID, WRITER_VERSION, ids) != batch.size) {
		onMismatch()
	}
	val expected = batch.sumOf { lineage -> lineage.revisions.size }
	val removed = dao.deleteExactRevisionLineages(WRITER_ID, WRITER_VERSION, ids)
	if (removed != expected) {
		onMismatch()
	}
	return removed
}

internal fun affectedDependencyClosure(
	lineages: List<WifiCapturedLineage>,
	beforeMs: Long,
): Set<String> {
	val selected = lineages.filter { lineage ->
		lineage.earliestPossibleWallTimeMs < beforeMs
	}.mapTo(linkedSetOf(), WifiCapturedLineage::logicalFactId)
	if (selected.isEmpty()) {
		return emptySet()
	}
	val ownerByDependent = lineages.associate { lineage ->
		lineage.logicalFactId to lineage.aggregateOwnerLogicalFactId
	}
	val dependentsByOwner = lineages.filter { lineage ->
		lineage.aggregateOwnerLogicalFactId != null
	}.groupBy(WifiCapturedLineage::aggregateOwnerLogicalFactId)
	var changed: Boolean
	do {
		changed = false
		for (dependentId in selected.toList()) {
			val ownerId = ownerByDependent[dependentId]
			if (ownerId != null && selected.add(ownerId)) {
				changed = true
			}
		}
		for (ownerId in selected.toList()) {
			for (dependent in dependentsByOwner[ownerId].orEmpty()) {
				if (selected.add(dependent.logicalFactId)) {
					changed = true
				}
			}
		}
	} while (changed)
	return selected
}

/** Keep dependent and owner revisions in separate DELETE statements for SQLite's RESTRICT key. */
internal fun List<WifiCapturedLineage>.dependencySafeDeletionBatches(batchSize: Int): List<List<WifiCapturedLineage>> {
	val (dependents, owners) = partition { it.aggregateOwnerLogicalFactId != null }
	return dependents.sortedBy(WifiCapturedLineage::logicalFactId).chunked(batchSize) +
		owners.sortedBy(WifiCapturedLineage::logicalFactId).chunked(batchSize)
}

internal fun requireWifiRetentionEvidence(
	evidence: SourceEvidenceState?,
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	beforeMs: Long,
): SourceEvidenceState {
	if (evidence == null || evidence.collectedDataEpoch != expectedCollectedDataEpoch ||
		evidence.deletedSourceEventHighWaterOrdinal != expectedDeletedSourceEventHighWaterOrdinal ||
		evidence.retainedFromMs != beforeMs
	) {
		block(WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
	}
	return evidence
}
