package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.*
import com.adsamcik.tracker.stats.api.repository.RadioContextReadGapReason

/** Portable data uses its import receipt and retained-data fences, never native provider consent. */
internal class RetainedImportedAmbientRadioReader(private val database: AppDatabase) {
    suspend fun wifiInTransaction(facts: List<ImportedAmbientWifiFactEntity>,
        history: List<ImportedAmbientWifiFactEntity>, gaps: List<ImportedAmbientWifiGapEntity>): RetainedImportedRadioRows<ImportedAmbientWifiFactEntity, ImportedAmbientWifiGapEntity> {
        val dao = database.ambientWifiFactDao()
        if (!retainedPortableLineageMatches(facts.map { it.authorityRecord() }, history.map { it.authorityRecord() })) return unavailable()
        val records = history.map { it.authorityRecord() } + gaps.map { it.authorityRecord() }
        val ids = records.map { it.archiveId }.distinct()
        if (records.size > MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES) return overflow()
        val receipts = mutableListOf<ImportedAmbientWifiReceiptEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            receipts += dao.retainedImportReceipts(chunk, MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES - receipts.size + 1)
            if (receipts.size > MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES) return overflow()
        }
        val tombstones = ids.chunked(QUERY_CHUNK).flatMap { dao.retainedImportTombstones(it, it.size + 1) }
        val revisions = records.map { it.retentionRevision }.distinct()
        val approvals = revisions.chunked(QUERY_CHUNK).flatMap {
            dao.retainedApprovals(AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT, it, it.size + 1)
        }
        val footprintIds = records.flatMap { listOf(it.archiveId, it.identity, it.effectChecksum) }.distinct()
        val footprints = mutableListOf<AmbientWifiReplayFootprintEntity>()
        for (chunk in footprintIds.chunked(QUERY_CHUNK)) {
            footprints += dao.replayFootprintsByIdentityDigests(chunk, MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES - footprints.size + 1)
            if (footprints.size > MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES) return overflow()
        }
        if (receipts.size > MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES) return overflow()
        if (tombstones.any { it.effectChecksum != AmbientWifiFactIntegrity.importTombstoneChecksum(it) } ||
            footprints.any { !AmbientWifiFactIntegrity.isAuthentic(it) }) return unavailable()
        val result = qualify(records, receipts.map { PortableRadioReceipt(it.archiveId, it.collectedDataEpoch, it.receivedAtMs) },
            approvals.map { PortableRadioRetention(it.approvalRevision, it.opaquePolicyId, it.collectedDataEpoch,
                it.retainedFromMs, it.isActive && AmbientWifiRetentionAuthorityIntegrity.isAuthentic(it)) },
            tombstones.map { it.archiveId }.toSet(), footprints.map { PortableRadioFootprint(it.footprintKind, it.identityDigest, it.semanticRevision) })
        return RetainedImportedRadioRows(facts.filter { it.factId to it.semanticRevision in result.allowed },
            gaps.filter { it.gapId to 0L in result.allowed }, result.gaps)
    }

    suspend fun cellInTransaction(facts: List<ImportedAmbientCellFactEntity>,
        history: List<ImportedAmbientCellFactEntity>, gaps: List<ImportedAmbientCellGapEntity>): RetainedImportedRadioRows<ImportedAmbientCellFactEntity, ImportedAmbientCellGapEntity> {
        val dao = database.ambientCellFactDao()
        if (!retainedPortableLineageMatches(facts.map { it.authorityRecord() }, history.map { it.authorityRecord() })) return unavailable()
        val records = history.map { it.authorityRecord() } + gaps.map { it.authorityRecord() }
        val ids = records.map { it.archiveId }.distinct()
        if (records.size > MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES) return overflow()
        val receipts = mutableListOf<ImportedAmbientCellReceiptEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            receipts += dao.retainedImportReceipts(chunk, MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES - receipts.size + 1)
            if (receipts.size > MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES) return overflow()
        }
        val tombstones = ids.chunked(QUERY_CHUNK).flatMap { dao.retainedImportTombstones(it, it.size + 1) }
        val revisions = records.map { it.retentionRevision }.distinct()
        val approvals = revisions.chunked(QUERY_CHUNK).flatMap {
            dao.retainedApprovals(AmbientCellRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT, it, it.size + 1)
        }
        val footprintIds = records.flatMap { listOf(it.archiveId, it.identity, it.effectChecksum) }.distinct()
        val footprints = mutableListOf<AmbientCellReplayFootprintEntity>()
        for (chunk in footprintIds.chunked(QUERY_CHUNK)) {
            footprints += dao.replayFootprintsByIdentityDigests(chunk, MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES - footprints.size + 1)
            if (footprints.size > MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES) return overflow()
        }
        if (receipts.size > MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES) return overflow()
        if (tombstones.any { it.effectChecksum != AmbientCellFactIntegrity.importTombstoneChecksum(it) } ||
            footprints.any { !AmbientCellFactIntegrity.isAuthentic(it) }) return unavailable()
        val result = qualify(records, receipts.map { PortableRadioReceipt(it.archiveId, it.collectedDataEpoch, it.receivedAtMs) },
            approvals.map { PortableRadioRetention(it.approvalRevision, it.opaquePolicyId, it.collectedDataEpoch,
                it.retainedFromMs, it.isActive && AmbientCellRetentionAuthorityIntegrity.isAuthentic(it)) },
            tombstones.map { it.archiveId }.toSet(), footprints.map { PortableRadioFootprint(it.footprintKind, it.identityDigest, it.semanticRevision) })
        return RetainedImportedRadioRows(facts.filter { it.factId to it.semanticRevision in result.allowed },
            gaps.filter { it.gapId to 0L in result.allowed }, result.gaps)
    }

    private suspend fun qualify(records: List<PortableRadioRecord>, receipts: List<PortableRadioReceipt>,
        approvals: List<PortableRadioRetention>, deletedArchives: Set<String>, footprints: List<PortableRadioFootprint>): PortableRadioQualification {
        if (records.isEmpty()) return PortableRadioQualification(emptySet())
        val state = database.sourceEvidenceStateDao().get()
            ?: return PortableRadioQualification(emptySet(), setOf(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE))
        if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
            return PortableRadioQualification(emptySet(), setOf(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE))
        }
        val receiptsByArchive = receipts.groupBy { it.archiveId }
        val approvalByRevision = approvals.associateBy { it.revision }
        val erased = footprints.map { Triple(it.kind, it.identity, it.revision) }.toSet()
        val invalidIdentities = mutableSetOf<String>()
        val readGaps = mutableSetOf<RadioContextReadGapReason>()
        // Retention expires the effective observation. Earlier correction revisions remain its
        // required lineage and still need authentic receipts, original policy bounds and no erase.
        val effective = records.groupBy { it.identity }.mapValues { (_, lineage) -> lineage.maxBy { it.revision } }
        for (record in records) {
            val isErased = record.archiveId in deletedArchives ||
                Triple(AmbientWifiReplayFootprintEntity.KIND_ARCHIVE_SCOPE, record.archiveId, 0L) in erased ||
                Triple(if (record.revision == 0L) AmbientWifiReplayFootprintEntity.KIND_GAP_IDENTITY else
                    AmbientWifiReplayFootprintEntity.KIND_FACT_IDENTITY, record.identity, record.revision) in erased ||
                Triple(if (record.revision == 0L) AmbientWifiReplayFootprintEntity.KIND_GAP_EFFECT else
                    AmbientWifiReplayFootprintEntity.KIND_FACT_EFFECT, record.effectChecksum, 0L) in erased
            if (isErased || record.epoch != state.collectedDataEpoch ||
                state.retainedFromMs?.let { effective.getValue(record.identity).fromMs < it } == true) {
                invalidIdentities += record.identity
                readGaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                continue
            }
            val approval = approvalByRevision[record.retentionRevision]
            if (!record.contentValid || approval == null || !approval.authenticActive ||
                approval.policyId != record.retentionId || approval.epoch != record.epoch ||
                approval.floor?.let { record.fromMs < it } == true ||
                receiptsByArchive[record.archiveId].orEmpty().none { receipt -> receipt.epoch == record.epoch &&
                    receipt.receivedAtMs == record.receivedAtMs && receipt.receivedAtMs >= record.untilMs }) {
                invalidIdentities += record.identity
                readGaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
            }
        }
        // A later correction cannot hide an invalid or erased ancestor of the same saved fact.
        return PortableRadioQualification(records.filter { it.identity !in invalidIdentities }
            .map { it.identity to it.revision }.toSet(), readGaps)
    }

    private fun <F, G> overflow() = RetainedImportedRadioRows<F, G>(emptyList(), emptyList(), setOf(RadioContextReadGapReason.DEPENDENCY_LIMIT))
    private fun <F, G> unavailable() = RetainedImportedRadioRows<F, G>(emptyList(), emptyList(), setOf(RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE))
}

internal data class RetainedImportedRadioRows<F, G>(val facts: List<F>, val gaps: List<G>,
    val readGaps: Set<RadioContextReadGapReason> = emptySet())
private data class PortableRadioRecord(val archiveId: String, val identity: String, val effectChecksum: String, val revision: Long,
    val epoch: Long, val fromMs: Long, val untilMs: Long, val receivedAtMs: Long,
    val retentionId: String, val retentionRevision: Long, val contentValid: Boolean)
private data class PortableRadioReceipt(val archiveId: String, val epoch: Long, val receivedAtMs: Long)
private data class PortableRadioRetention(val revision: Long, val policyId: String, val epoch: Long, val floor: Long?, val authenticActive: Boolean)
private data class PortableRadioFootprint(val kind: String, val identity: String, val revision: Long)
private data class PortableRadioQualification(val allowed: Set<Pair<String, Long>>, val gaps: Set<RadioContextReadGapReason> = emptySet())

private fun retainedPortableLineageMatches(latest: List<PortableRadioRecord>, history: List<PortableRadioRecord>): Boolean {
    val byIdentity = history.groupBy { it.identity }
    return latest.all { fact ->
        val revisions = byIdentity[fact.identity].orEmpty().sortedBy { it.revision }
        revisions.size.toLong() == fact.revision && revisions.lastOrNull() == fact &&
            revisions.withIndex().all { (index, row) -> row.revision == index + 1L && row.contentValid }
    }
}

private fun ImportedAmbientWifiFactEntity.authorityRecord() = PortableRadioRecord(archiveId, factId, portableEffectChecksum,
    semanticRevision, collectedDataEpoch, coverageStartTimeMs, latestPossibleTimeMs, receivedAtMs,
    retentionPolicyId, retentionApprovalRevision, toApiFact() != null)
private fun ImportedAmbientCellFactEntity.authorityRecord() = PortableRadioRecord(archiveId, factId, portableEffectChecksum,
    semanticRevision, collectedDataEpoch, coverageStartTimeMs, latestPossibleTimeMs, receivedAtMs,
    retentionPolicyId, retentionApprovalRevision, toApiFact() != null)
private fun ImportedAmbientWifiGapEntity.authorityRecord() = PortableRadioRecord(archiveId, gapId, portableEffectChecksum,
    0L, collectedDataEpoch, startTimeMs, endTimeMs, receivedAtMs, retentionPolicyId, retentionApprovalRevision, toApiGap() != null)
private fun ImportedAmbientCellGapEntity.authorityRecord() = PortableRadioRecord(archiveId, gapId, portableEffectChecksum,
    0L, collectedDataEpoch, startTimeMs, endTimeMs, receivedAtMs, retentionPolicyId, retentionApprovalRevision, toApiGap() != null)

private const val MAX_RETAINED_IMPORTED_RADIO_DEPENDENCIES = 65_536
