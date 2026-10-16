package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientWifiFactDao
import com.adsamcik.tracker.shared.base.database.dao.AmbientCellFactDao
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiDeletionMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellDeletionMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.dao.AmbientWifiEffectiveLocalRow
import com.adsamcik.tracker.shared.base.database.dao.AmbientCellEffectiveLocalRow
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.measuredStartTimeMs
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.stats.api.repository.RadioContextReadGapReason

/** One historical native authority seam for ordinary history, portable export and optional maps. */
internal class RetainedAmbientRadioReader(private val database: AppDatabase,
    private val boot: BootClockDomainProvider? = null) {
    suspend fun qualifyWifiInTransaction(rows: List<AmbientWifiEffectiveLocalRow>,
        limit: Int): RetainedAmbientRadioRows<AmbientWifiEffectiveLocalRow> {
        if (rows.isEmpty()) {
            return RetainedAmbientRadioRows(emptyList())
        }
        val gaps = mutableSetOf<RadioContextReadGapReason>()
        val dao = database.ambientWifiFactDao()
        if (rows.size > limit) {
            return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        val state = database.sourceEvidenceStateDao().get() ?:
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val candidates = retainedWifiCandidates(rows, state, gaps)
        return if (candidates.isEmpty()) {
            RetainedAmbientRadioRows(emptyList(), gaps)
        } else {
            qualifyWifiCandidates(dao, candidates, state, gaps)
        }
    }

    private fun retainedWifiCandidates(
        rows: List<AmbientWifiEffectiveLocalRow>,
        state: SourceEvidenceState,
        gaps: MutableSet<RadioContextReadGapReason>,
    ): List<AmbientWifiEffectiveLocalRow> {
        return rows.filter { row ->
            if (!state.acceptsRetainedEpoch(row.fact.collectedDataEpoch) ||
                row.fact.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal) {
                gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                false
            } else {
                val start = row.fact.measuredStartTimeMs()
                if (start == null) {
                    gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                    false
                } else {
                    (state.retainedFromMs?.let { start >= it } != false).also { retained ->
                    if (!retained) {
                        gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                    }
                }
                }
            }
        }
    }

    // Keep lineage, cursor, approval, authority, deletion and replay-budget failures in read order.
    @Suppress("ReturnCount")
    private suspend fun qualifyWifiCandidates(
        dao: AmbientWifiFactDao,
        candidates: List<AmbientWifiEffectiveLocalRow>,
        state: SourceEvidenceState,
        gaps: MutableSet<RadioContextReadGapReason>,
    ): RetainedAmbientRadioRows<AmbientWifiEffectiveLocalRow> {
        val ids = candidates.flatMap { listOfNotNull(it.fact.logicalFactId,
            it.fact.aggregateOwnerLogicalFactId) }.distinct()
        val lineage = readRetainedWifiLineages(dao, ids)
            ?: return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        val cursors = ids.chunked(QUERY_CHUNK).flatMap { dao.retainedCursors(it,
            it.size + 1) }.associateBy { it.logicalFactId }
        if (cursors.size != ids.size) {
            return unavailable(RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
        }
        val approvals = lineage.map { it.retentionApprovalRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            dao.retainedApprovals(AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, it, it.size + 1)
        }
        if (approvals.size > MAX_CONTEXT_DEPENDENCIES) {
            return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        val authority = try { RetainedAmbientRadioAuthority.load(database, SourceDestinationOwnerEntity.SOURCE_WIFI,
            lineage.map { it.authorityInput() }, boot?.current().orEmpty())
        } catch (_: RetainedRadioDependencyLimitExceeded) {
            return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
            ?: return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val marker = dao.latestDeletionMarker(state.collectedDataEpoch)
        if (!marker.hasAuthenticRetainedRadioShape()) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val footprints = readRetainedWifiFootprints(dao, ids)
            ?: return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        if (footprints.any { !AmbientWifiFactIntegrity.isAuthentic(it) }) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val erased =
            footprints.filter { it.footprintKind == AmbientWifiReplayFootprintEntity.KIND_LOCAL_FACT }
                .map { it.identityDigest }
                .toSet()
        val byId = lineage.groupBy { it.logicalFactId }
            .mapValues { (_, revisions) -> revisions.sortedBy { it.semanticRevision } }
        val approvalsByRevision = approvals.groupBy { it.approvalRevision }
        return RetainedWifiFactSnapshot(byId, cursors, approvalsByRevision, authority, marker, erased)
            .qualify(candidates, state, gaps)
    }

    suspend fun qualifyCellInTransaction(rows: List<AmbientCellEffectiveLocalRow>,
        limit: Int): RetainedAmbientRadioRows<AmbientCellEffectiveLocalRow> {
        if (rows.isEmpty()) {
            return RetainedAmbientRadioRows(emptyList())
        }
        val gaps = mutableSetOf<RadioContextReadGapReason>()
        val dao = database.ambientCellFactDao()
        if (rows.size > limit) {
            return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        val state = database.sourceEvidenceStateDao().get() ?:
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val candidates = retainedCellCandidates(rows, state, gaps)
        return if (candidates.isEmpty()) {
            RetainedAmbientRadioRows(emptyList(), gaps)
        } else {
            qualifyCellCandidates(dao, candidates, state, gaps)
        }
    }

    private fun retainedCellCandidates(
        rows: List<AmbientCellEffectiveLocalRow>,
        state: SourceEvidenceState,
        gaps: MutableSet<RadioContextReadGapReason>,
    ): List<AmbientCellEffectiveLocalRow> {
        return rows.filter { row ->
            if (!state.acceptsRetainedEpoch(row.fact.collectedDataEpoch) ||
                row.fact.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal) {
                gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                false
            } else {
                val start = row.fact.measuredStartTimeMs()
                if (start == null) {
                    gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                    false
                } else {
                    (state.retainedFromMs?.let { start >= it } != false).also { retained ->
                    if (!retained) {
                        gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                    }
                }
                }
            }
        }
    }

    // Keep lineage, cursor, approval, authority, deletion and replay-budget failures in read order.
    @Suppress("ReturnCount")
    private suspend fun qualifyCellCandidates(
        dao: AmbientCellFactDao,
        candidates: List<AmbientCellEffectiveLocalRow>,
        state: SourceEvidenceState,
        gaps: MutableSet<RadioContextReadGapReason>,
    ): RetainedAmbientRadioRows<AmbientCellEffectiveLocalRow> {
        val ids = candidates.flatMap { listOfNotNull(it.fact.logicalFactId,
            it.fact.aggregateOwnerLogicalFactId) }.distinct()
        val lineage = readRetainedCellLineages(dao, ids)
            ?: return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        val cursors = ids.chunked(QUERY_CHUNK).flatMap { dao.retainedCursors(it,
            it.size + 1) }.associateBy { it.logicalFactId }
        if (cursors.size != ids.size) {
            return unavailable(RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
        }
        val approvals = lineage.map { it.retentionApprovalRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            dao.retainedApprovals(AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, it, it.size + 1)
        }
        if (approvals.size > MAX_CONTEXT_DEPENDENCIES) {
            return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        val authority = try { RetainedAmbientRadioAuthority.load(database, SourceDestinationOwnerEntity.SOURCE_CELL,
            lineage.map { it.authorityInput() }, boot?.current().orEmpty())
        } catch (_: RetainedRadioDependencyLimitExceeded) {
            return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
            ?: return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val marker = dao.latestDeletionMarker(state.collectedDataEpoch)
        if (!marker.hasAuthenticRetainedRadioShape()) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val footprints = readRetainedCellFootprints(dao, ids)
            ?: return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        if (footprints.any { !AmbientCellFactIntegrity.isAuthentic(it) }) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val erased =
            footprints.filter { it.footprintKind == AmbientCellReplayFootprintEntity.KIND_LOCAL_FACT }
                .map { it.identityDigest }
                .toSet()
        val byId = lineage.groupBy { it.logicalFactId }
            .mapValues { (_, revisions) -> revisions.sortedBy { it.semanticRevision } }
        val approvalsByRevision = approvals.groupBy { it.approvalRevision }
        return RetainedCellFactSnapshot(byId, cursors, approvalsByRevision, authority, marker, erased)
            .qualify(candidates, state, gaps)
    }
    /** Gaps carry their original purpose/retention tuple, but do not claim a provider observation. */
    // Keep empty, read-budget, source-authority and replay-footprint exits beside their reads.
    @Suppress("ReturnCount")
    suspend fun qualifyWifiGapsInTransaction(rows: List<AmbientWifiGapEntity>,
        limit: Int): RetainedAmbientRadioRows<AmbientWifiGapEntity> {
        if (rows.isEmpty()) {
            return RetainedAmbientRadioRows(emptyList())
        }
        if (rows.size > limit) {
            return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        val dao = database.ambientWifiFactDao()
        val state = database.sourceEvidenceStateDao().get() ?:
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val marker = dao.latestDeletionMarker(state.collectedDataEpoch)
        if (!marker.hasAuthenticRetainedRadioShape()) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val policies = rows.map { it.sourcePolicyRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            database.sourcePolicyDao().policiesAtRevisions(SourceDestinationOwnerEntity.SOURCE_WIFI, it)
        }.associateBy { it.policyRevision }
        val consents = rows.map { it.ambientConsentEpoch }.distinct().chunked(QUERY_CHUNK).flatMap {
            database.sourcePolicyDao().consentEpochs(SourceDestinationOwnerEntity.SOURCE_WIFI,
                SourceBrokerPurpose.AMBIENT_PRODUCT, it)
        }.associateBy { it.epoch }
        val approvals = rows.map { it.retentionApprovalRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            dao.retainedApprovals(AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, it, it.size + 1)
        }.associateBy { it.approvalRevision }
        val footprints = readRetainedWifiFootprints(dao, rows.map { it.gapId }.distinct())
            ?: return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        if (footprints.any { !AmbientWifiFactIntegrity.isAuthentic(it) }) {
            return unavailable(RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
        }
        val erased =
            footprints.filter { it.footprintKind == AmbientWifiReplayFootprintEntity.KIND_LOCAL_GAP }
                .map { it.identityDigest }
                .toSet()
        return rows.qualifyRetainedAmbientGaps(state, marker, policies, consents, approvals, erased)
    }

    // Keep empty, read-budget, source-authority and replay-footprint exits beside their reads.
    @Suppress("ReturnCount")
    suspend fun qualifyCellGapsInTransaction(rows: List<AmbientCellGapEntity>,
        limit: Int): RetainedAmbientRadioRows<AmbientCellGapEntity> {
        if (rows.isEmpty()) {
            return RetainedAmbientRadioRows(emptyList())
        }
        if (rows.size > limit) {
            return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        val dao = database.ambientCellFactDao()
        val state = database.sourceEvidenceStateDao().get() ?:
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val marker = dao.latestDeletionMarker(state.collectedDataEpoch)
        if (!marker.hasAuthenticRetainedRadioShape()) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val policies = rows.map { it.sourcePolicyRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            database.sourcePolicyDao().policiesAtRevisions(SourceDestinationOwnerEntity.SOURCE_CELL, it)
        }.associateBy { it.policyRevision }
        val consents = rows.map { it.ambientConsentEpoch }.distinct().chunked(QUERY_CHUNK).flatMap {
            database.sourcePolicyDao().consentEpochs(SourceDestinationOwnerEntity.SOURCE_CELL,
                SourceBrokerPurpose.AMBIENT_PRODUCT, it)
        }.associateBy { it.epoch }
        val approvals = rows.map { it.retentionApprovalRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            dao.retainedApprovals(AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, it, it.size + 1)
        }.associateBy { it.approvalRevision }
        val footprints = readRetainedCellFootprints(dao, rows.map { it.gapId }.distinct())
            ?: return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        if (footprints.any { !AmbientCellFactIntegrity.isAuthentic(it) }) {
            return unavailable(RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
        }
        val erased =
            footprints.filter { it.footprintKind == AmbientCellReplayFootprintEntity.KIND_LOCAL_GAP }
                .map { it.identityDigest }
                .toSet()
        return rows.qualifyRetainedAmbientGaps(state, marker, policies, consents, approvals, erased)
    }

    private suspend fun readRetainedWifiLineages(
        dao: AmbientWifiFactDao, ids: List<String>,
    ): List<AmbientWifiFactRevisionEntity>? {
        val lineage = mutableListOf<AmbientWifiFactRevisionEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            lineage += dao.localFactLineages(chunk, MAX_CONTEXT_DEPENDENCIES - lineage.size + 1)
            if (lineage.size > MAX_CONTEXT_DEPENDENCIES) {
                return null
            }
        }
        return lineage
    }

    private suspend fun readRetainedWifiFootprints(
        dao: AmbientWifiFactDao, ids: List<String>,
    ): List<AmbientWifiReplayFootprintEntity>? {
        val footprints = mutableListOf<AmbientWifiReplayFootprintEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            footprints += dao.replayFootprintsByIdentityDigests(chunk, MAX_CONTEXT_DEPENDENCIES - footprints.size + 1)
            if (footprints.size > MAX_CONTEXT_DEPENDENCIES) {
                return null
            }
        }
        return footprints
    }

    private suspend fun readRetainedCellLineages(
        dao: AmbientCellFactDao, ids: List<String>,
    ): List<AmbientCellFactRevisionEntity>? {
        val lineage = mutableListOf<AmbientCellFactRevisionEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            lineage += dao.localFactLineages(chunk, MAX_CONTEXT_DEPENDENCIES - lineage.size + 1)
            if (lineage.size > MAX_CONTEXT_DEPENDENCIES) {
                return null
            }
        }
        return lineage
    }

    private suspend fun readRetainedCellFootprints(
        dao: AmbientCellFactDao, ids: List<String>,
    ): List<AmbientCellReplayFootprintEntity>? {
        val footprints = mutableListOf<AmbientCellReplayFootprintEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            footprints += dao.replayFootprintsByIdentityDigests(chunk, MAX_CONTEXT_DEPENDENCIES - footprints.size + 1)
            if (footprints.size > MAX_CONTEXT_DEPENDENCIES) {
                return null
            }
        }
        return footprints
    }

    private fun <T> unavailable(reason: RadioContextReadGapReason) = RetainedAmbientRadioRows<T>(emptyList(),
        setOf(reason))
}

private class RetainedWifiFactSnapshot(
    private val byId: Map<String, List<AmbientWifiFactRevisionEntity>>,
    private val cursors: Map<String, AmbientWifiFactCursorEntity>,
    private val approvalsByRevision: Map<Long, List<AmbientWifiRetentionAuthorityEntity>>,
    private val authority: RetainedAmbientRadioAuthority,
    private val marker: AmbientWifiDeletionMarkerEntity?,
    private val erased: Set<String>,
) {
    fun qualify(
        candidates: List<AmbientWifiEffectiveLocalRow>,
        state: SourceEvidenceState,
        gaps: MutableSet<RadioContextReadGapReason>,
    ): RetainedAmbientRadioRows<AmbientWifiEffectiveLocalRow> {
        val qualified = mutableListOf<AmbientWifiEffectiveLocalRow>()
        for (row in candidates) {
            val fact = row.fact
            val revisions = byId[fact.logicalFactId].orEmpty()
            val owner = fact.aggregateOwnerLogicalFactId?.let { ownerId -> byId[ownerId]?.singleOrNull {
                it.semanticRevision == fact.aggregateOwnerSemanticRevision } }
            val ownerLineage = byId[fact.aggregateOwnerLogicalFactId].orEmpty()
            val cursor = cursors[fact.logicalFactId]
            val ownerCursor = owner?.let { cursors[it.logicalFactId] }
            val dependencies = revisions + ownerLineage
            // Expire the effective fact/owner, while retaining and authenticating correction history.
            if (!dependenciesAreRetained(owner, dependencies, state)) {
                gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                continue
            }
            if (!cursorsMatch(cursor, ownerCursor, fact, owner) ||
                !lineagesMatch(fact, revisions, owner, ownerLineage, dependencies)) {
                gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                continue
            }
            qualified += row
        }
        return RetainedAmbientRadioRows(qualified, gaps)
    }

    private fun dependenciesAreRetained(
        owner: AmbientWifiFactRevisionEntity?, dependencies: List<AmbientWifiFactRevisionEntity>,
        state: SourceEvidenceState,
    ): Boolean = !(
        owner?.let { aggregate ->
            state.retainedFromMs?.let { floor -> aggregate.measuredStartTimeMs()?.let { it < floor } != false }
        } == true || dependencies.any { revision ->
            revision.logicalFactId in erased ||
                marker?.let { revision.ambientConsentEpoch <= it.throughConsentEpoch } == true ||
                !state.acceptsRetainedEpoch(revision.collectedDataEpoch) ||
                revision.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal
        }
    )

    private fun cursorsMatch(
        cursor: AmbientWifiFactCursorEntity?, ownerCursor: AmbientWifiFactCursorEntity?,
        fact: AmbientWifiFactRevisionEntity, owner: AmbientWifiFactRevisionEntity?,
    ): Boolean = !(cursor == null || !retainedCursorMatches(cursor, fact) ||
                owner != null && (ownerCursor == null || !retainedCursorMatches(ownerCursor, owner)))

    private fun lineagesMatch(
        fact: AmbientWifiFactRevisionEntity, revisions: List<AmbientWifiFactRevisionEntity>,
        owner: AmbientWifiFactRevisionEntity?, ownerLineage: List<AmbientWifiFactRevisionEntity>,
        dependencies: List<AmbientWifiFactRevisionEntity>,
    ): Boolean {
        if (revisions.lastOrNull() != fact || fact.aggregateOwnerLogicalFactId != null && owner == null) {
            return false
        }
        if (!ownerLineageMatches(fact, owner, ownerLineage)) {
            return false
        }
        return !revisions.withIndex().any { (index, revision) -> revision.semanticRevision != index + 1L } &&
            !dependencies.any(::revisionIsUnverifiable)
    }

    private fun ownerLineageMatches(
        fact: AmbientWifiFactRevisionEntity, owner: AmbientWifiFactRevisionEntity?,
        ownerLineage: List<AmbientWifiFactRevisionEntity>,
    ): Boolean = owner == null || !(!retainedWifiOwnerCompatible(fact, owner) ||
        ownerLineage.lastOrNull() != owner ||
        ownerLineage.withIndex().any { (index, revision) -> revision.semanticRevision != index + 1L })

    private fun revisionIsUnverifiable(revision: AmbientWifiFactRevisionEntity): Boolean =
        revision.measuredStartTimeMs() == null ||
            AmbientWifiFactIntegrity.effectChecksum(revision) != revision.effectChecksum ||
            !authority.qualifies(revision.authorityInput()) ||
            approvalsByRevision[revision.retentionApprovalRevision].orEmpty().none {
                approvalAuthenticatesRevision(it, revision)
            }

    // One saved approval must match the complete original revision and retained floor.
    @Suppress("CyclomaticComplexMethod")
    private fun approvalAuthenticatesRevision(
        approval: AmbientWifiRetentionAuthorityEntity,
        revision: AmbientWifiFactRevisionEntity,
    ): Boolean =
        approval.isActive && AmbientWifiRetentionAuthorityIntegrity.isAuthentic(approval) &&
            approval.approvalRevision == revision.retentionApprovalRevision &&
            approval.opaquePolicyId == revision.retentionPolicyId &&
            approval.collectedDataEpoch == revision.collectedDataEpoch &&
            approval.sourcePolicyRevision == revision.sourcePolicyRevision &&
            approval.ambientConsentEpoch == revision.ambientConsentEpoch &&
            approval.retainedFromMs?.let { floor ->
                requireNotNull(revision.measuredStartTimeMs()) >= floor
            } != false
}

private class RetainedCellFactSnapshot(
    private val byId: Map<String, List<AmbientCellFactRevisionEntity>>,
    private val cursors: Map<String, AmbientCellFactCursorEntity>,
    private val approvalsByRevision: Map<Long, List<AmbientCellRetentionAuthorityEntity>>,
    private val authority: RetainedAmbientRadioAuthority,
    private val marker: AmbientCellDeletionMarkerEntity?,
    private val erased: Set<String>,
) {
    fun qualify(
        candidates: List<AmbientCellEffectiveLocalRow>,
        state: SourceEvidenceState,
        gaps: MutableSet<RadioContextReadGapReason>,
    ): RetainedAmbientRadioRows<AmbientCellEffectiveLocalRow> {
        val qualified = mutableListOf<AmbientCellEffectiveLocalRow>()
        for (row in candidates) {
            val fact = row.fact
            val revisions = byId[fact.logicalFactId].orEmpty()
            val owner = fact.aggregateOwnerLogicalFactId?.let { ownerId -> byId[ownerId]?.singleOrNull {
                it.semanticRevision == fact.aggregateOwnerSemanticRevision } }
            val ownerLineage = byId[fact.aggregateOwnerLogicalFactId].orEmpty()
            val cursor = cursors[fact.logicalFactId]
            val ownerCursor = owner?.let { cursors[it.logicalFactId] }
            val dependencies = revisions + ownerLineage
            if (!dependenciesAreRetained(owner, dependencies, state)) {
                gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                continue
            }
            if (!cursorsMatch(cursor, ownerCursor, fact, owner) ||
                !lineagesMatch(fact, revisions, owner, ownerLineage, dependencies)) {
                gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                continue
            }
            qualified += row
        }
        return RetainedAmbientRadioRows(qualified, gaps)
    }

    private fun dependenciesAreRetained(
        owner: AmbientCellFactRevisionEntity?, dependencies: List<AmbientCellFactRevisionEntity>,
        state: SourceEvidenceState,
    ): Boolean = !(
        owner?.let { aggregate ->
            state.retainedFromMs?.let { floor -> aggregate.measuredStartTimeMs()?.let { it < floor } != false }
        } == true || dependencies.any { revision ->
            revision.logicalFactId in erased ||
                marker?.let { revision.ambientConsentEpoch <= it.throughConsentEpoch } == true ||
                !state.acceptsRetainedEpoch(revision.collectedDataEpoch) ||
                revision.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal
        }
    )

    private fun cursorsMatch(
        cursor: AmbientCellFactCursorEntity?, ownerCursor: AmbientCellFactCursorEntity?,
        fact: AmbientCellFactRevisionEntity, owner: AmbientCellFactRevisionEntity?,
    ): Boolean = !(cursor == null || !retainedCursorMatches(cursor, fact) ||
                owner != null && (ownerCursor == null || !retainedCursorMatches(ownerCursor, owner)))

    private fun lineagesMatch(
        fact: AmbientCellFactRevisionEntity, revisions: List<AmbientCellFactRevisionEntity>,
        owner: AmbientCellFactRevisionEntity?, ownerLineage: List<AmbientCellFactRevisionEntity>,
        dependencies: List<AmbientCellFactRevisionEntity>,
    ): Boolean {
        if (revisions.lastOrNull() != fact || fact.aggregateOwnerLogicalFactId != null && owner == null) {
            return false
        }
        if (!ownerLineageMatches(fact, owner, ownerLineage)) {
            return false
        }
        return !revisions.withIndex().any { (index, revision) -> revision.semanticRevision != index + 1L } &&
            !dependencies.any(::revisionIsUnverifiable)
    }

    private fun ownerLineageMatches(
        fact: AmbientCellFactRevisionEntity, owner: AmbientCellFactRevisionEntity?,
        ownerLineage: List<AmbientCellFactRevisionEntity>,
    ): Boolean = owner == null || !(!retainedCellOwnerCompatible(fact, owner) ||
        ownerLineage.lastOrNull() != owner ||
        ownerLineage.withIndex().any { (index, revision) -> revision.semanticRevision != index + 1L })

    private fun revisionIsUnverifiable(revision: AmbientCellFactRevisionEntity): Boolean =
        revision.measuredStartTimeMs() == null ||
            AmbientCellFactIntegrity.effectChecksum(revision) != revision.effectChecksum ||
            !authority.qualifies(revision.authorityInput()) ||
            approvalsByRevision[revision.retentionApprovalRevision].orEmpty().none {
                approvalAuthenticatesRevision(it, revision)
            }

    // One saved approval must match the complete original revision and retained floor.
    @Suppress("CyclomaticComplexMethod")
    private fun approvalAuthenticatesRevision(
        approval: AmbientCellRetentionAuthorityEntity,
        revision: AmbientCellFactRevisionEntity,
    ): Boolean =
        approval.isActive && AmbientCellRetentionAuthorityIntegrity.isAuthentic(approval) &&
            approval.approvalRevision == revision.retentionApprovalRevision &&
            approval.opaquePolicyId == revision.retentionPolicyId &&
            approval.collectedDataEpoch == revision.collectedDataEpoch &&
            approval.sourcePolicyRevision == revision.sourcePolicyRevision &&
            approval.ambientConsentEpoch == revision.ambientConsentEpoch &&
            approval.retainedFromMs?.let { floor ->
                requireNotNull(revision.measuredStartTimeMs()) >= floor
            } != false
}

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
private fun retainedGapQualifies(
    row: AmbientWifiGapEntity,
    policy: SourcePolicyEntity?,
    consent: SourceConsentEpochEntity?,
    approval: AmbientWifiRetentionAuthorityEntity?,
): Boolean =
    AmbientWifiFactIntegrity.gapChecksum(row) == row.effectChecksum &&
    retainedGapPurposeQualifies(policy, consent, row.ambientConsentEpoch) &&
    approval != null && approval.isActive &&
        AmbientWifiRetentionAuthorityIntegrity.isAuthentic(approval) &&
    approval.opaquePolicyId == row.retentionPolicyId &&
        approval.collectedDataEpoch == row.collectedDataEpoch &&
    approval.sourcePolicyRevision == row.sourcePolicyRevision &&
        approval.ambientConsentEpoch == row.ambientConsentEpoch &&
    approval.retainedFromMs?.let { row.gapStartTimeMs >= it } != false

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
private fun retainedGapQualifies(
    row: AmbientCellGapEntity,
    policy: SourcePolicyEntity?,
    consent: SourceConsentEpochEntity?,
    approval: AmbientCellRetentionAuthorityEntity?,
): Boolean =
    AmbientCellFactIntegrity.gapChecksum(row) == row.effectChecksum &&
    retainedGapPurposeQualifies(policy, consent, row.ambientConsentEpoch) &&
    approval != null && approval.isActive &&
        AmbientCellRetentionAuthorityIntegrity.isAuthentic(approval) &&
    approval.opaquePolicyId == row.retentionPolicyId &&
        approval.collectedDataEpoch == row.collectedDataEpoch &&
    approval.sourcePolicyRevision == row.sourcePolicyRevision &&
        approval.ambientConsentEpoch == row.ambientConsentEpoch &&
    approval.retainedFromMs?.let { row.gapStartTimeMs >= it } != false

internal data class RetainedAmbientRadioRows<T>(val rows: List<T>, val gaps: Set<RadioContextReadGapReason> =
    emptySet())

/** The native rows already accepted for this exact read; paired consumers need no second raw query. */
internal data class RetainedAmbientRead<T, NativeRow>(
    val result: T,
    val readGaps: Set<RadioContextReadGapReason> = emptySet(),
    val nativeRows: List<NativeRow> = emptyList(),
)

private fun retainedGapPurposeQualifies(policy: SourcePolicyEntity?, consent: SourceConsentEpochEntity?, epoch: Long) =
    policy?.ambientPersistenceEligible == true && policy.ambientConsentEpoch == epoch &&
        consent?.eligible == true && consent.persistenceEligible

private fun retainedCursorMatches(cursor: AmbientWifiFactCursorEntity, fact: AmbientWifiFactRevisionEntity) =
    cursor.writerId == fact.writerId && cursor.writerVersion == fact.writerVersion &&
        cursor.logicalFactId == fact.logicalFactId &&
        cursor.latestSemanticRevision == fact.semanticRevision && cursor.latestMutationId == fact.mutationId &&
        cursor.latestEffectChecksum == fact.effectChecksum &&
            cursor.latestSourceAdmissionOrdinal == fact.sourceAdmissionOrdinal &&
        cursor.collectedDataEpoch == fact.collectedDataEpoch &&
            cursor.scopeDeletionGeneration == fact.scopeDeletionGeneration

private fun retainedCursorMatches(cursor: AmbientCellFactCursorEntity, fact: AmbientCellFactRevisionEntity) =
    cursor.writerId == fact.writerId && cursor.writerVersion == fact.writerVersion &&
        cursor.logicalFactId == fact.logicalFactId &&
        cursor.latestSemanticRevision == fact.semanticRevision && cursor.latestMutationId == fact.mutationId &&
        cursor.latestEffectChecksum == fact.effectChecksum &&
            cursor.latestSourceAdmissionOrdinal == fact.sourceAdmissionOrdinal &&
        cursor.collectedDataEpoch == fact.collectedDataEpoch &&
            cursor.scopeDeletionGeneration == fact.scopeDeletionGeneration

internal fun AmbientWifiFactRevisionEntity.authorityInput() = RetainedRadioAuthorityInput(registrationGeneration,
    sourceInstanceId,
    clockDomainId, physicalConfigurationFingerprint, collectedDataEpoch, authorizationRevision,
        authorizationFingerprint, purposeEligibilityMask,
    observedIntervalStartNanos, observedElapsedNanos, receivedElapsedNanos, observedWallTimeMs, sourcePolicyRevision,
    ambientConsentEpoch, purposeDemandId, retentionPolicyId, retentionApprovalRevision)
internal fun AmbientCellFactRevisionEntity.authorityInput() = RetainedRadioAuthorityInput(registrationGeneration,
    sourceInstanceId,
    clockDomainId, physicalConfigurationFingerprint, collectedDataEpoch, authorizationRevision,
        authorizationFingerprint, purposeEligibilityMask,
    observedIntervalStartNanos, observedElapsedNanos, receivedElapsedNanos, observedWallTimeMs, sourcePolicyRevision,
    ambientConsentEpoch, purposeDemandId, retentionPolicyId, retentionApprovalRevision)

internal fun radioChildFitsMeasuredInterval(wallMs: Long, uncertaintyMs: Long,
    fromMs: Long, untilMs: Long): Boolean =
    uncertaintyMs >= 0L && wallMs >= uncertaintyMs && wallMs - uncertaintyMs >= fromMs &&
        wallMs <= Long.MAX_VALUE - uncertaintyMs && wallMs + uncertaintyMs <= untilMs

private fun List<AmbientWifiGapEntity>.qualifyRetainedAmbientGaps(
    state: SourceEvidenceState, marker: AmbientWifiDeletionMarkerEntity?,
    policies: Map<Long, SourcePolicyEntity>, consents: Map<Long, SourceConsentEpochEntity>,
    approvals: Map<Long, AmbientWifiRetentionAuthorityEntity>, erased: Set<String>,
): RetainedAmbientRadioRows<AmbientWifiGapEntity> {
    val gaps = mutableSetOf<RadioContextReadGapReason>()
    val qualified = filter { row ->
        if (!state.acceptsRetainedEpoch(row.collectedDataEpoch) || row.gapId in erased ||
            marker?.let { row.ambientConsentEpoch <= it.throughConsentEpoch } == true ||
            state.retainedFromMs?.let { row.gapStartTimeMs < it } == true) {
            gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
            false
        } else {
            val policy = policies[row.sourcePolicyRevision]
            val consent = consents[row.ambientConsentEpoch]
            val approval = approvals[row.retentionApprovalRevision]
            retainedGapQualifies(row, policy, consent, approval).also { valid ->
                    if (!valid) {
                        gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                    }
                }
        }
    }
    return RetainedAmbientRadioRows(qualified, gaps)
}

private fun List<AmbientCellGapEntity>.qualifyRetainedAmbientGaps(
    state: SourceEvidenceState, marker: AmbientCellDeletionMarkerEntity?,
    policies: Map<Long, SourcePolicyEntity>, consents: Map<Long, SourceConsentEpochEntity>,
    approvals: Map<Long, AmbientCellRetentionAuthorityEntity>, erased: Set<String>,
): RetainedAmbientRadioRows<AmbientCellGapEntity> {
    val gaps = mutableSetOf<RadioContextReadGapReason>()
    val qualified = filter { row ->
        if (!state.acceptsRetainedEpoch(row.collectedDataEpoch) || row.gapId in erased ||
            marker?.let { row.ambientConsentEpoch <= it.throughConsentEpoch } == true ||
            state.retainedFromMs?.let { row.gapStartTimeMs < it } == true) {
            gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
            false
        } else {
            val policy = policies[row.sourcePolicyRevision]
            val consent = consents[row.ambientConsentEpoch]
            val approval = approvals[row.retentionApprovalRevision]
            retainedGapQualifies(row, policy, consent, approval).also { valid ->
                    if (!valid) {
                        gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                    }
                }
        }
    }
    return RetainedAmbientRadioRows(qualified, gaps)
}


private fun AmbientWifiDeletionMarkerEntity?.hasAuthenticRetainedRadioShape(): Boolean =
    this == null || AmbientWifiFactIntegrity.isAuthentic(this)

private fun AmbientCellDeletionMarkerEntity?.hasAuthenticRetainedRadioShape(): Boolean =
    this == null || AmbientCellFactIntegrity.isAuthentic(this)
