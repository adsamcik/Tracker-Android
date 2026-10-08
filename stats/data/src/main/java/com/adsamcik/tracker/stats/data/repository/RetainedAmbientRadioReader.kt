package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientWifiEffectiveLocalRow
import com.adsamcik.tracker.shared.base.database.dao.AmbientCellEffectiveLocalRow
import com.adsamcik.tracker.shared.base.database.data.*
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.stats.api.repository.RadioContextReadGapReason

/** One historical native authority seam for ordinary history, portable export and optional maps. */
internal class RetainedAmbientRadioReader(private val database: AppDatabase,
    private val boot: BootClockDomainProvider? = null) {
    suspend fun qualifyWifiInTransaction(rows: List<AmbientWifiEffectiveLocalRow>, limit: Int): RetainedAmbientRadioRows<AmbientWifiEffectiveLocalRow> {
        if (rows.isEmpty()) return RetainedAmbientRadioRows(emptyList())
        val gaps = mutableSetOf<RadioContextReadGapReason>()
        val dao = database.ambientWifiFactDao()
        if (rows.size > limit) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        val state = database.sourceEvidenceStateDao().get() ?: return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val candidates = rows.filter { row ->
            if (row.fact.collectedDataEpoch != state.collectedDataEpoch ||
                row.fact.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal) {
                gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                false
            } else {
                val start = row.fact.measuredStartTimeMs()
                if (start == null) {
                    gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                    false
                } else (state.retainedFromMs?.let { start >= it } != false).also { retained ->
                    if (!retained) gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                }
            }
        }
        if (candidates.isEmpty()) return RetainedAmbientRadioRows(emptyList(), gaps)
        val ids = candidates.flatMap { listOfNotNull(it.fact.logicalFactId, it.fact.aggregateOwnerLogicalFactId) }.distinct()
        val lineage = mutableListOf<AmbientWifiFactRevisionEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            lineage += dao.localFactLineages(chunk, MAX_CONTEXT_DEPENDENCIES - lineage.size + 1)
            if (lineage.size > MAX_CONTEXT_DEPENDENCIES) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        val cursors = ids.chunked(QUERY_CHUNK).flatMap { dao.retainedCursors(it, it.size + 1) }.associateBy { it.logicalFactId }
        if (cursors.size != ids.size) return unavailable(RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
        val approvals = lineage.map { it.retentionApprovalRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            dao.retainedApprovals(AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, it, it.size + 1)
        }
        if (approvals.size > MAX_CONTEXT_DEPENDENCIES) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
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
        if (marker != null && !AmbientWifiFactIntegrity.isAuthentic(marker)) return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val footprints = mutableListOf<AmbientWifiReplayFootprintEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            footprints += dao.replayFootprintsByIdentityDigests(chunk, MAX_CONTEXT_DEPENDENCIES - footprints.size + 1)
            if (footprints.size > MAX_CONTEXT_DEPENDENCIES) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        if (footprints.any { !AmbientWifiFactIntegrity.isAuthentic(it) }) return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val erased = footprints.filter { it.footprintKind == AmbientWifiReplayFootprintEntity.KIND_LOCAL_FACT }.map { it.identityDigest }.toSet()
        val byId = lineage.groupBy { it.logicalFactId }
        val qualified = mutableListOf<AmbientWifiEffectiveLocalRow>()
        for (row in candidates) {
            val fact = row.fact
            val revisions = byId[fact.logicalFactId].orEmpty().sortedBy { it.semanticRevision }
            val owner = fact.aggregateOwnerLogicalFactId?.let { ownerId -> byId[ownerId]?.singleOrNull {
                it.semanticRevision == fact.aggregateOwnerSemanticRevision } }
            val ownerLineage = byId[fact.aggregateOwnerLogicalFactId].orEmpty().sortedBy { it.semanticRevision }
            val cursor = cursors[fact.logicalFactId]
            val ownerCursor = owner?.let { cursors[it.logicalFactId] }
            val dependencies = revisions + ownerLineage
            // Expire the effective fact/owner, while retaining and authenticating correction history.
            if (owner?.let { aggregate -> state.retainedFromMs?.let { floor ->
                    aggregate.measuredStartTimeMs()?.let { it < floor } != false } } == true ||
                dependencies.any { revision -> revision.logicalFactId in erased ||
                    marker?.let { revision.ambientConsentEpoch <= it.throughConsentEpoch } == true ||
                    revision.collectedDataEpoch != state.collectedDataEpoch ||
                    revision.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal }) {
                gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                continue
            }
            if (cursor == null || !retainedCursorMatches(cursor, fact) ||
                owner != null && (ownerCursor == null || !retainedCursorMatches(ownerCursor, owner)) ||
                revisions.lastOrNull() != fact || fact.aggregateOwnerLogicalFactId != null && owner == null ||
                owner != null && (!retainedWifiOwnerCompatible(fact, owner) || ownerLineage.lastOrNull() != owner ||
                    ownerLineage.withIndex().any { (index, revision) -> revision.semanticRevision != index + 1L }) ||
                revisions.withIndex().any { (index, revision) -> revision.semanticRevision != index + 1L } ||
                dependencies.any { revision -> revision.measuredStartTimeMs() == null ||
                    AmbientWifiFactIntegrity.effectChecksum(revision) != revision.effectChecksum ||
                    !authority.qualifies(revision.authorityInput()) || approvals.none { it.isActive && AmbientWifiRetentionAuthorityIntegrity.isAuthentic(it) &&
                    it.approvalRevision == revision.retentionApprovalRevision && it.opaquePolicyId == revision.retentionPolicyId &&
                    it.collectedDataEpoch == revision.collectedDataEpoch && it.sourcePolicyRevision == revision.sourcePolicyRevision &&
                    it.ambientConsentEpoch == revision.ambientConsentEpoch &&
                    it.retainedFromMs?.let { floor -> requireNotNull(revision.measuredStartTimeMs()) >= floor } != false } }) {
                gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                continue
            }
            qualified += row
        }
        return RetainedAmbientRadioRows(qualified, gaps)
    }

    suspend fun qualifyCellInTransaction(rows: List<AmbientCellEffectiveLocalRow>, limit: Int): RetainedAmbientRadioRows<AmbientCellEffectiveLocalRow> {
        if (rows.isEmpty()) return RetainedAmbientRadioRows(emptyList())
        val gaps = mutableSetOf<RadioContextReadGapReason>()
        val dao = database.ambientCellFactDao()
        if (rows.size > limit) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        val state = database.sourceEvidenceStateDao().get() ?: return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val candidates = rows.filter { row ->
            if (row.fact.collectedDataEpoch != state.collectedDataEpoch ||
                row.fact.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal) {
                gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                false
            } else {
                val start = row.fact.measuredStartTimeMs()
                if (start == null) {
                    gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                    false
                } else (state.retainedFromMs?.let { start >= it } != false).also { retained ->
                    if (!retained) gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                }
            }
        }
        if (candidates.isEmpty()) return RetainedAmbientRadioRows(emptyList(), gaps)
        val ids = candidates.flatMap { listOfNotNull(it.fact.logicalFactId, it.fact.aggregateOwnerLogicalFactId) }.distinct()
        val lineage = mutableListOf<AmbientCellFactRevisionEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            lineage += dao.localFactLineages(chunk, MAX_CONTEXT_DEPENDENCIES - lineage.size + 1)
            if (lineage.size > MAX_CONTEXT_DEPENDENCIES) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        val cursors = ids.chunked(QUERY_CHUNK).flatMap { dao.retainedCursors(it, it.size + 1) }.associateBy { it.logicalFactId }
        if (cursors.size != ids.size) return unavailable(RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
        val approvals = lineage.map { it.retentionApprovalRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            dao.retainedApprovals(AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, it, it.size + 1)
        }
        if (approvals.size > MAX_CONTEXT_DEPENDENCIES) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
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
        if (marker != null && !AmbientCellFactIntegrity.isAuthentic(marker)) return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val footprints = mutableListOf<AmbientCellReplayFootprintEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            footprints += dao.replayFootprintsByIdentityDigests(chunk, MAX_CONTEXT_DEPENDENCIES - footprints.size + 1)
            if (footprints.size > MAX_CONTEXT_DEPENDENCIES) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        if (footprints.any { !AmbientCellFactIntegrity.isAuthentic(it) }) return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val erased = footprints.filter { it.footprintKind == AmbientCellReplayFootprintEntity.KIND_LOCAL_FACT }.map { it.identityDigest }.toSet()
        val byId = lineage.groupBy { it.logicalFactId }
        val qualified = mutableListOf<AmbientCellEffectiveLocalRow>()
        for (row in candidates) {
            val fact = row.fact
            val revisions = byId[fact.logicalFactId].orEmpty().sortedBy { it.semanticRevision }
            val owner = fact.aggregateOwnerLogicalFactId?.let { ownerId -> byId[ownerId]?.singleOrNull {
                it.semanticRevision == fact.aggregateOwnerSemanticRevision } }
            val ownerLineage = byId[fact.aggregateOwnerLogicalFactId].orEmpty().sortedBy { it.semanticRevision }
            val cursor = cursors[fact.logicalFactId]
            val ownerCursor = owner?.let { cursors[it.logicalFactId] }
            val dependencies = revisions + ownerLineage
            if (owner?.let { aggregate -> state.retainedFromMs?.let { floor ->
                    aggregate.measuredStartTimeMs()?.let { it < floor } != false } } == true ||
                dependencies.any { revision -> revision.logicalFactId in erased ||
                    marker?.let { revision.ambientConsentEpoch <= it.throughConsentEpoch } == true ||
                    revision.collectedDataEpoch != state.collectedDataEpoch ||
                    revision.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal }) {
                gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                continue
            }
            if (cursor == null || !retainedCursorMatches(cursor, fact) ||
                owner != null && (ownerCursor == null || !retainedCursorMatches(ownerCursor, owner)) ||
                revisions.lastOrNull() != fact || fact.aggregateOwnerLogicalFactId != null && owner == null ||
                owner != null && (!retainedCellOwnerCompatible(fact, owner) || ownerLineage.lastOrNull() != owner ||
                    ownerLineage.withIndex().any { (index, revision) -> revision.semanticRevision != index + 1L }) ||
                revisions.withIndex().any { (index, revision) -> revision.semanticRevision != index + 1L } ||
                dependencies.any { revision -> revision.measuredStartTimeMs() == null ||
                    AmbientCellFactIntegrity.effectChecksum(revision) != revision.effectChecksum ||
                    !authority.qualifies(revision.authorityInput()) || approvals.none { it.isActive && AmbientCellRetentionAuthorityIntegrity.isAuthentic(it) &&
                    it.approvalRevision == revision.retentionApprovalRevision && it.opaquePolicyId == revision.retentionPolicyId &&
                    it.collectedDataEpoch == revision.collectedDataEpoch && it.sourcePolicyRevision == revision.sourcePolicyRevision &&
                    it.ambientConsentEpoch == revision.ambientConsentEpoch &&
                    it.retainedFromMs?.let { floor -> requireNotNull(revision.measuredStartTimeMs()) >= floor } != false } }) {
                gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                continue
            }
            qualified += row
        }
        return RetainedAmbientRadioRows(qualified, gaps)
    }
    /** Gaps carry their original purpose/retention tuple, but do not claim a provider observation. */
    suspend fun qualifyWifiGapsInTransaction(rows: List<AmbientWifiGapEntity>, limit: Int): RetainedAmbientRadioRows<AmbientWifiGapEntity> {
        if (rows.isEmpty()) return RetainedAmbientRadioRows(emptyList())
        if (rows.size > limit) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        val dao = database.ambientWifiFactDao()
        val state = database.sourceEvidenceStateDao().get() ?: return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val marker = dao.latestDeletionMarker(state.collectedDataEpoch)
        if (marker != null && !AmbientWifiFactIntegrity.isAuthentic(marker)) return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val policies = rows.map { it.sourcePolicyRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            database.sourcePolicyDao().policiesAtRevisions(SourceDestinationOwnerEntity.SOURCE_WIFI, it)
        }.associateBy { it.policyRevision }
        val consents = rows.map { it.ambientConsentEpoch }.distinct().chunked(QUERY_CHUNK).flatMap {
            database.sourcePolicyDao().consentEpochs(SourceDestinationOwnerEntity.SOURCE_WIFI, SourceBrokerPurpose.AMBIENT_PRODUCT, it)
        }.associateBy { it.epoch }
        val approvals = rows.map { it.retentionApprovalRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            dao.retainedApprovals(AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, it, it.size + 1)
        }.associateBy { it.approvalRevision }
        val footprints = mutableListOf<AmbientWifiReplayFootprintEntity>()
        for (chunk in rows.map { it.gapId }.distinct().chunked(QUERY_CHUNK)) {
            footprints += dao.replayFootprintsByIdentityDigests(chunk, MAX_CONTEXT_DEPENDENCIES - footprints.size + 1)
            if (footprints.size > MAX_CONTEXT_DEPENDENCIES) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        if (footprints.any { !AmbientWifiFactIntegrity.isAuthentic(it) }) return unavailable(RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
        val erased = footprints.filter { it.footprintKind == AmbientWifiReplayFootprintEntity.KIND_LOCAL_GAP }.map { it.identityDigest }.toSet()
        val gaps = mutableSetOf<RadioContextReadGapReason>()
        val qualified = rows.filter { row ->
            if (row.collectedDataEpoch != state.collectedDataEpoch || row.gapId in erased ||
                marker?.let { row.ambientConsentEpoch <= it.throughConsentEpoch } == true ||
                state.retainedFromMs?.let { row.gapStartTimeMs < it } == true) {
                gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                false
            } else {
                val policy = policies[row.sourcePolicyRevision]
                val consent = consents[row.ambientConsentEpoch]
                val approval = approvals[row.retentionApprovalRevision]
                (AmbientWifiFactIntegrity.gapChecksum(row) == row.effectChecksum &&
                    retainedGapPurposeQualifies(policy, consent, row.ambientConsentEpoch) &&
                    approval != null && approval.isActive && AmbientWifiRetentionAuthorityIntegrity.isAuthentic(approval) &&
                    approval.opaquePolicyId == row.retentionPolicyId && approval.collectedDataEpoch == row.collectedDataEpoch &&
                    approval.sourcePolicyRevision == row.sourcePolicyRevision && approval.ambientConsentEpoch == row.ambientConsentEpoch &&
                    approval.retainedFromMs?.let { row.gapStartTimeMs >= it } != false).also { valid ->
                        if (!valid) gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                    }
            }
        }
        return RetainedAmbientRadioRows(qualified, gaps)
    }

    suspend fun qualifyCellGapsInTransaction(rows: List<AmbientCellGapEntity>, limit: Int): RetainedAmbientRadioRows<AmbientCellGapEntity> {
        if (rows.isEmpty()) return RetainedAmbientRadioRows(emptyList())
        if (rows.size > limit) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        val dao = database.ambientCellFactDao()
        val state = database.sourceEvidenceStateDao().get() ?: return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
            return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        }
        val marker = dao.latestDeletionMarker(state.collectedDataEpoch)
        if (marker != null && !AmbientCellFactIntegrity.isAuthentic(marker)) return unavailable(RadioContextReadGapReason.SOURCE_AUTHORITY_UNAVAILABLE)
        val policies = rows.map { it.sourcePolicyRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            database.sourcePolicyDao().policiesAtRevisions(SourceDestinationOwnerEntity.SOURCE_CELL, it)
        }.associateBy { it.policyRevision }
        val consents = rows.map { it.ambientConsentEpoch }.distinct().chunked(QUERY_CHUNK).flatMap {
            database.sourcePolicyDao().consentEpochs(SourceDestinationOwnerEntity.SOURCE_CELL, SourceBrokerPurpose.AMBIENT_PRODUCT, it)
        }.associateBy { it.epoch }
        val approvals = rows.map { it.retentionApprovalRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
            dao.retainedApprovals(AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, it, it.size + 1)
        }.associateBy { it.approvalRevision }
        val footprints = mutableListOf<AmbientCellReplayFootprintEntity>()
        for (chunk in rows.map { it.gapId }.distinct().chunked(QUERY_CHUNK)) {
            footprints += dao.replayFootprintsByIdentityDigests(chunk, MAX_CONTEXT_DEPENDENCIES - footprints.size + 1)
            if (footprints.size > MAX_CONTEXT_DEPENDENCIES) return unavailable(RadioContextReadGapReason.DEPENDENCY_LIMIT)
        }
        if (footprints.any { !AmbientCellFactIntegrity.isAuthentic(it) }) return unavailable(RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE)
        val erased = footprints.filter { it.footprintKind == AmbientCellReplayFootprintEntity.KIND_LOCAL_GAP }.map { it.identityDigest }.toSet()
        val gaps = mutableSetOf<RadioContextReadGapReason>()
        val qualified = rows.filter { row ->
            if (row.collectedDataEpoch != state.collectedDataEpoch || row.gapId in erased ||
                marker?.let { row.ambientConsentEpoch <= it.throughConsentEpoch } == true ||
                state.retainedFromMs?.let { row.gapStartTimeMs < it } == true) {
                gaps += RadioContextReadGapReason.RETENTION_BOUNDARY
                false
            } else {
                val policy = policies[row.sourcePolicyRevision]
                val consent = consents[row.ambientConsentEpoch]
                val approval = approvals[row.retentionApprovalRevision]
                (AmbientCellFactIntegrity.gapChecksum(row) == row.effectChecksum &&
                    retainedGapPurposeQualifies(policy, consent, row.ambientConsentEpoch) &&
                    approval != null && approval.isActive && AmbientCellRetentionAuthorityIntegrity.isAuthentic(approval) &&
                    approval.opaquePolicyId == row.retentionPolicyId && approval.collectedDataEpoch == row.collectedDataEpoch &&
                    approval.sourcePolicyRevision == row.sourcePolicyRevision && approval.ambientConsentEpoch == row.ambientConsentEpoch &&
                    approval.retainedFromMs?.let { row.gapStartTimeMs >= it } != false).also { valid ->
                        if (!valid) gaps += RadioContextReadGapReason.SOURCE_PROVENANCE_UNVERIFIABLE
                    }
            }
        }
        return RetainedAmbientRadioRows(qualified, gaps)
    }

    private fun <T> unavailable(reason: RadioContextReadGapReason) = RetainedAmbientRadioRows<T>(emptyList(), setOf(reason))
}

internal data class RetainedAmbientRadioRows<T>(val rows: List<T>, val gaps: Set<RadioContextReadGapReason> = emptySet())

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
    cursor.writerId == fact.writerId && cursor.writerVersion == fact.writerVersion && cursor.logicalFactId == fact.logicalFactId &&
        cursor.latestSemanticRevision == fact.semanticRevision && cursor.latestMutationId == fact.mutationId &&
        cursor.latestEffectChecksum == fact.effectChecksum && cursor.latestSourceAdmissionOrdinal == fact.sourceAdmissionOrdinal &&
        cursor.collectedDataEpoch == fact.collectedDataEpoch && cursor.scopeDeletionGeneration == fact.scopeDeletionGeneration

private fun retainedCursorMatches(cursor: AmbientCellFactCursorEntity, fact: AmbientCellFactRevisionEntity) =
    cursor.writerId == fact.writerId && cursor.writerVersion == fact.writerVersion && cursor.logicalFactId == fact.logicalFactId &&
        cursor.latestSemanticRevision == fact.semanticRevision && cursor.latestMutationId == fact.mutationId &&
        cursor.latestEffectChecksum == fact.effectChecksum && cursor.latestSourceAdmissionOrdinal == fact.sourceAdmissionOrdinal &&
        cursor.collectedDataEpoch == fact.collectedDataEpoch && cursor.scopeDeletionGeneration == fact.scopeDeletionGeneration

internal fun AmbientWifiFactRevisionEntity.authorityInput() = RetainedRadioAuthorityInput(registrationGeneration, sourceInstanceId,
    clockDomainId, physicalConfigurationFingerprint, collectedDataEpoch, authorizationRevision, authorizationFingerprint, purposeEligibilityMask,
    observedIntervalStartNanos, observedElapsedNanos, receivedElapsedNanos, observedWallTimeMs, sourcePolicyRevision,
    ambientConsentEpoch, purposeDemandId, retentionPolicyId, retentionApprovalRevision)
internal fun AmbientCellFactRevisionEntity.authorityInput() = RetainedRadioAuthorityInput(registrationGeneration, sourceInstanceId,
    clockDomainId, physicalConfigurationFingerprint, collectedDataEpoch, authorizationRevision, authorizationFingerprint, purposeEligibilityMask,
    observedIntervalStartNanos, observedElapsedNanos, receivedElapsedNanos, observedWallTimeMs, sourcePolicyRevision,
    ambientConsentEpoch, purposeDemandId, retentionPolicyId, retentionApprovalRevision)

internal fun radioChildFitsMeasuredInterval(wallMs: Long, uncertaintyMs: Long,
    fromMs: Long, untilMs: Long): Boolean =
    uncertaintyMs >= 0L && wallMs >= uncertaintyMs && wallMs - uncertaintyMs >= fromMs &&
        wallMs <= Long.MAX_VALUE - uncertaintyMs && wallMs + uncertaintyMs <= untilMs
