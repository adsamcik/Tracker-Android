package com.adsamcik.tracker.stats.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * Caller owns the Room transaction. This reader has no collection or repair dependency.
 *
 * Saved canonical samples and their independently sealed historical purpose grants are the retained
 * product authority. Original WAL payloads and live provider registrations are not required here.
 * End or a later Off policy never grants or revokes an older purpose; its exact erase and retention
 * boundaries still apply. Older unsealed rows remain ordinary saved data without being upgraded to
 * native map-context authority by this read.
 */
internal class RetainedLocationContextReader @Inject constructor(private val database: AppDatabase) {
    suspend fun readInTransaction(radios: List<RadioContextInput>): RetainedLocationContextSnapshot {
        if (radios.isEmpty()) {
            return RetainedLocationContextSnapshot(emptyList())
        }
        val clocks = radios.groupBy { it.scope.clockIdentity() }
        if (clocks.size > MAX_CONTEXT_CLOCKS) {
            return limited()
        }
        val receipts = mutableListOf<LocationAcceptedReceiptEntity>()
        for ((clock, group) in clocks) {
            val from = maxOf(0L, group.minOf { it.observedFromElapsedNanos } - CONTEXT_MARGIN_NANOS)
            val last = group.maxOf { it.observedElapsedNanos }
            val to = if (last > Long.MAX_VALUE - CONTEXT_MARGIN_NANOS) {
                Long.MAX_VALUE
            } else {
                last + CONTEXT_MARGIN_NANOS
            }
            val page = database.locationPurposeDao().contextReceipts(clock.first, clock.second,
                from, to, MAX_CONTEXT_POINTS - receipts.size + 1)
            receipts += page
            if (receipts.size > MAX_CONTEXT_POINTS) {
                return limited()
            }
        }
        return qualifyInTransaction(receipts)
    }

    /** Radio enrichment requires accuracy; ordinary saved measurements do not. */
    suspend fun qualifyInTransaction(receipts: List<LocationAcceptedReceiptEntity>): RetainedLocationContextSnapshot {
        val saved = qualifySavedInTransaction(receipts)
        val points = saved.samples.mapNotNull { row ->
            val latitude = row.sample.latE7?.takeIf { it in -900_000_000..900_000_000 } ?: return@mapNotNull null
            val longitude = row.sample.lonE7?.takeIf { it in -1_800_000_000..1_800_000_000 } ?: return@mapNotNull null
            // An edited wall time has no new Android clock evidence for a radio join.
            if (row.sample.timeMs != row.receipt.observedWallTimeMs) {
                return@mapNotNull null
            }
            val accuracy = row.sample.hAccM?.takeIf { it.isFinite() && it >= 0f } ?: return@mapNotNull null
            val receipt = row.receipt
            LocationContextInput(receipt.sourceEventId, row.scope, receipt.observedElapsedRealtimeNanos,
                latitude, longitude, accuracy, true, receipt.observedWallTimeMs, receipt.wallTimeUncertaintyMs)
        }
        return RetainedLocationContextSnapshot(points, saved.gaps, saved.erasedNativeSampleIds, saved.historyProblem)
    }

    /** Shared source-owned lookup for range/history readers, using their already bounded candidates. */
    suspend fun qualifySavedInTransaction(
        receipts: List<LocationAcceptedReceiptEntity>
    ): RetainedLocationSavedSnapshot {
        if (receipts.size > MAX_CONTEXT_POINTS || receipts.map { it.sampleId }.distinct().size != receipts.size) {
            return limitedSaved()
        }
        val state = database.sourceEvidenceStateDao().get()
            ?: return unavailableSaved(RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE)
        val ids = receipts.map { it.sampleId }
        val samples = ids.chunked(QUERY_CHUNK).flatMap { database.locationSampleDao().byIds(it) }.associateBy { it.id }
        val effectiveSamples =
            ids.chunked(QUERY_CHUNK).flatMap { database.locationSampleDao().effectiveByIds(it) }.associateBy { it.id }
        // Erase authority depends on the exact receipt/sample and dataset, never optional map-context dependencies.
        val authenticatedSampleIds = receipts.filter { receipt ->
            receipt.authenticatesRetainedLocationSample(samples[receipt.sampleId])
        }.mapTo(linkedSetOf()) { it.sampleId }
        val erasedNativeSampleIds = receipts.filter { it.sampleId in authenticatedSampleIds &&
            (!state.acceptsRetainedEpoch(it.collectedDataEpoch) ||
                it.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal)
        }.mapTo(linkedSetOf()) { it.sampleId }
        return qualifyRetainedLocationPurposeCopies(ids, receipts, samples, effectiveSamples,
            authenticatedSampleIds, erasedNativeSampleIds, state)
    }

    private fun LocationAcceptedReceiptEntity.authenticatesRetainedLocationSample(sample: LocationSample?): Boolean =
        sample != null && LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(this) &&
            LocationAcceptedReceiptIntegrity.matchesCanonicalSample(this, sample) &&
            writerId == SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID &&
            writerVersion == SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION

    // The original bounded snapshot inputs remain explicit; this phase owns only optional purpose reads and failures.
    @Suppress("LongParameterList")
    private suspend fun qualifyRetainedLocationPurposeCopies(
        ids: List<Long>,
        receipts: List<LocationAcceptedReceiptEntity>,
        samples: Map<Long, LocationSample>,
        effectiveSamples: Map<Long, LocationSample>,
        authenticatedSampleIds: Set<Long>,
        erasedNativeSampleIds: Set<Long>,
        state: SourceEvidenceState,
    ): RetainedLocationSavedSnapshot {
        var erasedNativeScopeSampleIds: Set<Long>? = null
        return try {
            if (database.locationPurposeDao().savedFileExportAuthorityStamp().endsWith("|1")) {
                return unavailableSaved(RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE, erasedNativeSampleIds)
            }
            val purposes = loadRetainedLocationPurposes(ids) ?: return limitedSaved(erasedNativeSampleIds)
            // Exact erase is independent of optional historical proof. Missing proof cannot revive a fenced copy.
            val digests = purposes.filter { it.purpose == CAPTURE }.map { it.retainedLocationScopeDigest() }.distinct()
            val fences = digests.chunked(QUERY_CHUNK).flatMap {
                database.sourceDeletionFenceDao().contextFences(LOCATION, CAPTURE, it, MAX_CONTEXT_DEPENDENCIES + 1)
            }
            if (fences.size > MAX_CONTEXT_DEPENDENCIES) {
                return limitedSaved(erasedNativeSampleIds).copy(erasedNativeScopeSampleIds = erasedNativeScopeSampleIds)
            }
            val fenced = fences.map { it.scopeIdentityDigest }.toSet()
            // An unverified sibling remains saved content. Erasing one copy must not erase that sibling.
            erasedNativeScopeSampleIds = purposes.groupBy { it.sampleId }.filterValues { original ->
                original.all { it.purpose == CAPTURE && it.retainedLocationScopeDigest() in fenced }
            }.keys
            qualifyRetainedLocationPurposes(receipts, samples, effectiveSamples, authenticatedSampleIds,
                erasedNativeSampleIds, state, purposes, fenced)
                .copy(erasedNativeScopeSampleIds = erasedNativeScopeSampleIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: android.database.sqlite.SQLiteException) {
            unavailableSaved(RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE, erasedNativeSampleIds,
                HistoryProblemReason.STORAGE_UNAVAILABLE).copy(erasedNativeScopeSampleIds = erasedNativeScopeSampleIds)
        } catch (_: RuntimeException) {
            unavailableSaved(RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE, erasedNativeSampleIds,
                HistoryProblemReason.INTEGRITY_UNAVAILABLE).copy(erasedNativeScopeSampleIds =
                    erasedNativeScopeSampleIds)
        }
    }

    private suspend fun loadRetainedLocationPurposes(ids: List<Long>): List<LocationSamplePurposeAuthorityEntity>? {
        val purposes = mutableListOf<LocationSamplePurposeAuthorityEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            purposes += database.locationPurposeDao().contextPurposes(chunk,
                MAX_CONTEXT_DEPENDENCIES - purposes.size + 1)
            if (purposes.size > MAX_CONTEXT_DEPENDENCIES) {
                return null
            }
        }
        return purposes
    }

    // These values were captured once by the original bounded transaction, not re-read by a second owner.
    @Suppress("LongParameterList")
    private suspend fun qualifyRetainedLocationPurposes(
        receipts: List<LocationAcceptedReceiptEntity>,
        samples: Map<Long, LocationSample>,
        effectiveSamples: Map<Long, LocationSample>,
        authenticatedSampleIds: Set<Long>,
        erasedNativeSampleIds: Set<Long>,
        state: SourceEvidenceState,
        purposes: List<LocationSamplePurposeAuthorityEntity>,
        fenced: Set<String>,
    ): RetainedLocationSavedSnapshot {
        val policyIds = purposes.map { it.sourcePolicyRevision }.distinct()
        val policies = policyIds.chunked(QUERY_CHUNK).flatMap {
            database.sourcePolicyDao().policiesAtRevisions(LOCATION, it)
        }.associateBy { it.policyRevision }
        val consents = purposes.groupBy { it.purpose }.flatMap { (purpose, group) ->
            group.map { it.consentEpoch }.distinct().chunked(QUERY_CHUNK).flatMap {
                database.sourcePolicyDao().consentEpochs(LOCATION, purpose, it)
            }
        }.associateBy { it.purpose to it.epoch }
        val runs = purposes.mapNotNull { it.serviceRunId }.distinct()
        if (runs.size > MAX_CONTEXT_CLOCKS) {
            return limitedSaved(erasedNativeSampleIds)
        }
        val runRows = if (runs.isEmpty()) {
            emptyList()
        } else {
            database.trackingHistoryReadDao().serviceRuns(runs)
        }
        // Retained grants name exact immutable revisions. Other quality changes in the same run
        // are not dependencies of these points and must not make a long run disappear.
        val keys = purposes.filter { it.purpose == CAPTURE }.map { purpose ->
            RetainedLocationManifestKey(requireNotNull(purpose.logicalTrackingId),
                requireNotNull(purpose.serviceRunId), requireNotNull(purpose.sessionManifestRevision))
        }.distinct()
        val (manifests, sources) = loadRetainedLocationManifestDependencies(keys)
            ?: return limitedSaved(erasedNativeSampleIds)
        val runById = runRows.associateBy { it.serviceRunId }
        val sourcesById = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
        // One immutable manifest can authorize many points in this bounded Room snapshot.
        // Invalid manifests are omitted locally; independently valid sibling points remain usable.
        val manifestById = manifests.associateBy { it.serviceRunId to it.manifestRevision }
            .filterValues { manifest ->
                SessionManifestIntegrity.verify(manifest,
                    sourcesById[manifest.logicalTrackingId to manifest.manifestRevision].orEmpty())
            }
        val revisions = purposes.mapNotNull { it.retentionApprovalRevision }.distinct()
        val approvals = revisions.chunked(QUERY_CHUNK).flatMap {
            database.locationPurposeDao().contextRetentionApprovals(it, MAX_CONTEXT_DEPENDENCIES + 1)
        }.associateBy { it.approvalRevision }
        return qualifyRetainedLocationReceipts(receipts, samples, effectiveSamples, authenticatedSampleIds,
            erasedNativeSampleIds, state, purposes, policies, consents, runById, manifestById,
            sourcesById, approvals, fenced)
    }

    private suspend fun loadRetainedLocationManifestDependencies(
        keys: List<RetainedLocationManifestKey>,
    ): Pair<List<SessionManifestVersionEntity>, List<SessionManifestSourceEntity>>? {
        val manifests = mutableListOf<SessionManifestVersionEntity>()
        val sources = mutableListOf<SessionManifestSourceEntity>()
        for (chunk in keys.chunked(MANIFEST_QUERY_CHUNK)) {
            manifests += database.trackingHistoryReadDao().retainedLocationManifests(manifestQuery(chunk,
                MAX_CONTEXT_DEPENDENCIES - manifests.size + 1))
            if (manifests.size > MAX_CONTEXT_DEPENDENCIES) {
                return null
            }
        }
        // A bad run reference must not load the same member twice and poison a valid sibling.
        for (chunk in keys.distinctBy { it.logicalId to it.revision }.chunked(MANIFEST_QUERY_CHUNK)) {
            sources += database.trackingHistoryReadDao().retainedLocationManifestSources(manifestSourceQuery(chunk,
                MAX_CONTEXT_DEPENDENCIES - sources.size + 1))
            if (sources.size > MAX_CONTEXT_DEPENDENCIES) {
                return null
            }
        }
        return manifests to sources
    }

    private fun manifestQuery(keys: List<RetainedLocationManifestKey>, limit: Int): SimpleSQLiteQuery {
        val arguments = keys.flatMap { listOf<Any>(it.logicalId, it.runId, it.revision) } + limit
        val where =
            keys.joinToString(" OR ") { "(logical_tracking_id = ? AND service_run_id = ? AND manifest_revision = ?)" }
        return SimpleSQLiteQuery("SELECT * FROM session_manifest_version WHERE $where " +
            "ORDER BY service_run_id, manifest_revision LIMIT ?", arguments.toTypedArray())
    }
    private fun manifestSourceQuery(keys: List<RetainedLocationManifestKey>, limit: Int): SimpleSQLiteQuery {
        val selected = keys.map { it.logicalId to it.revision }.distinct()
        val arguments = selected.flatMap { listOf<Any>(it.first, it.second) } + limit
        val where = selected.joinToString(" OR ") { "(logical_tracking_id = ? AND manifest_revision = ?)" }
        return SimpleSQLiteQuery("SELECT * FROM session_manifest_source WHERE $where " +
            "ORDER BY logical_tracking_id, manifest_revision, purpose, source_kind LIMIT ?", arguments.toTypedArray())
    }

    private fun limitedSaved(erasedNativeSampleIds: Set<Long> = emptySet()) =
        unavailableSaved(RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT, erasedNativeSampleIds)
    private fun unavailableSaved(gap: RadioLocationContextGap, erasedNativeSampleIds: Set<Long> = emptySet(),
        historyProblem: HistoryProblemReason? = null) =
        RetainedLocationSavedSnapshot(emptyList(), setOf(gap), erasedNativeSampleIds, historyProblem)

    private fun limited(erasedNativeSampleIds: Set<Long> = emptySet()) =
        unavailable(RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT, erasedNativeSampleIds)
    private fun unavailable(gap: RadioLocationContextGap, erasedNativeSampleIds: Set<Long> = emptySet(),
        historyProblem: HistoryProblemReason? = null) =
        RetainedLocationContextSnapshot(emptyList(), setOf(gap), erasedNativeSampleIds, historyProblem)
}

/** Original saved content and one independently validated historical purpose; no acquisition permission. */
internal data class QualifiedRetainedLocationSample(val sample: LocationSample,
    val receipt: LocationAcceptedReceiptEntity, val scope: RadioContextScope)
private data class RetainedLocationManifestKey(val logicalId: String, val runId: String, val revision: Long)

internal data class RetainedLocationSavedSnapshot(val samples: List<QualifiedRetainedLocationSample>,
    val gaps: Set<RadioLocationContextGap> = emptySet(), val erasedNativeSampleIds: Set<Long> = emptySet(),
    val historyProblem: HistoryProblemReason? = null,
    /** null: erase lookup unfinished; otherwise samples with every original native copy explicitly erased. */
    val erasedNativeScopeSampleIds: Set<Long>? = null)

internal data class RetainedLocationContextSnapshot(val points: List<LocationContextInput>,
    val gaps: Set<RadioLocationContextGap> = emptySet(),
    /** Exact canonical native samples covered by authenticated dataset erase authority; no imported authority. */
    val erasedNativeSampleIds: Set<Long> = emptySet(),
    /** Actual read failure, distinct from expected missing retained proof; History preserves saved points. */
    val historyProblem: HistoryProblemReason? = null)

internal fun RadioContextScope.clockIdentity(): Pair<String, String> = when (this) {
    is RadioContextScope.Session -> owner.bootId to owner.clockDomainId
    is RadioContextScope.Ambient -> owner.bootId to owner.clockDomainId
}

internal fun LocationSamplePurposeAuthorityEntity.retainedLocationScopeDigest() =
    SourceDeletionFenceEntity.logicalServiceRunIdentity(
        LOCATION, CAPTURE, requireNotNull(logicalTrackingId), requireNotNull(serviceRunId))
internal const val MAX_CONTEXT_POINTS = 4_096
internal const val MAX_CONTEXT_DEPENDENCIES = 16_384
internal const val QUERY_CHUNK = 400
private const val MANIFEST_QUERY_CHUNK = 100 // At most 301 bind values, below Android's 999-variable floor.
private const val MAX_CONTEXT_CLOCKS = 100
private const val CONTEXT_MARGIN_NANOS = 60_000_000_000L
private const val LOCATION = SourceDestinationOwnerEntity.SOURCE_LOCATION
private const val CAPTURE = SourceBrokerPurpose.SESSION_CAPTURE
