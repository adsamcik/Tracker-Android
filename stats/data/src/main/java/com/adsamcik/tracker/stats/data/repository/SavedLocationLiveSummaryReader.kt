package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.database.dao.LocationPurposeDao
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap
import com.adsamcik.tracker.stats.api.repository.SavedLocationFix
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummarySelection
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Temporary source-local total, seeded once and extended with newly saved native points.
 * The controller owns the sole active observation and shares its value with product consumers.
 * A conflated hint cannot lose a point: the receipt PK cursor owns continuation. No data is written.
 */
internal class SavedLocationLiveSummaryReader(
    private val database: AppDatabase,
    private val io: CoroutineDispatcher,
    private val updates: SavedLocationLiveUpdateSignal,
    private val summaries: SavedLocationSummaryReader = SavedLocationSummaryReader(database, io),
    private val purpose: LocationPurposeDao = database.locationPurposeDao(),
    private val invalidations: Flow<Set<String>>? = null,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSession(segmentId: Long): Flow<SavedLocationSummaryRead> {
        require(segmentId > 0L)
        return flow {
            var progress: Progress? = null
            var pointVersion = 0L
            var rebuildVersion = 0L
            var handledPointVersion = 0L
            var handledRebuildVersion = 0L
            val room = (invalidations ?: database.invalidationTracker.createFlow(*TABLES, emitInitialState = true))
                .map { Notice(it) }
            val hints = updates.hints.map { Notice(emptySet()) }
            // Finish each finite snapshot; cancelling it on every fix could starve a long recovery read.
            merge(room, hints).map { notice ->
                // Fold before the lossy buffer: a later hint cannot discard a dependency/purpose notice.
                if (notice.tables.any { it in POINT_TABLES }) {
                    pointVersion = Math.addExact(pointVersion, 1L)
                }
                if (notice.tables.any { it in REBUILD_TABLES }) {
                    rebuildVersion = Math.addExact(rebuildVersion, 1L)
                }
                Wakeup(pointVersion, rebuildVersion)
            }.conflate().collect { wakeup ->
                currentCoroutineContext().ensureActive()
                val next = try {
                    database.withTransaction { refresh(segmentId, progress,
                        wakeup.pointVersion != handledPointVersion, wakeup.rebuildVersion != handledRebuildVersion) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: android.database.sqlite.SQLiteException) {
                    Next(SavedLocationSummaryRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE), null)
                } catch (_: RuntimeException) {
                    Next(SavedLocationSummaryRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE), null)
                }
                progress = next.progress
                handledPointVersion = wakeup.pointVersion
                handledRebuildVersion = wakeup.rebuildVersion
                emit(next.read)
            }
        }.distinctUntilChanged().flowOn(io)
    }

    // Ordered missing-scope, rebuild and unchanged-prefix exits precede any saved-prefix mutation.
    @Suppress("ReturnCount")
    private suspend fun refresh(segmentId: Long, old: Progress?, pointTablesChanged: Boolean, rebuild: Boolean): Next {
        val evidence = database.sourceEvidenceStateDao().get()
            ?.takeIf { it.hasValidLiveSummaryShape() }
            ?: return Next(SavedLocationSummaryRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE), null)
        val snapshot = TrackingHistoryReadSnapshot(evidence.collectedDataEpoch, evidence.revision)
        val segment = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
            ?: return Next(SavedLocationSummaryRead.NotFound, null)
        val logicalId = segment.logicalTrackingId?.takeIf { it.isNotBlank() }
            ?: return Next(SavedLocationSummaryRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE), null)
        val runId = segment.serviceRunId?.takeIf { it.isNotBlank() }
            ?: return Next(SavedLocationSummaryRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE), null)
        val scope = Scope(segmentId, logicalId, runId)
        val guard = Guard(evidence.collectedDataEpoch, evidence.retainedFromMs,
            evidence.deletedSourceEventHighWaterOrdinal, purpose.savedFileExportAuthorityStamp())
        val throughId = purpose.latestSavedReceiptSampleId()
        if (throughId < 0L) {
            throw InvalidContinuation()
        }
        if (old == null || needsSeed(old, scope, guard, throughId, rebuild)) {
            return seed(scope, guard, throughId, snapshot)
        }
        if (throughId == old.throughId && !pointTablesChanged) {
            return Next(old.read.copy(readSnapshot = snapshot), old.copy(read = old.read.copy(readSnapshot = snapshot)))
        }
        return continueSavedPrefix(scope, guard, old, throughId, snapshot, pointTablesChanged)
    }

    private suspend fun continueSavedPrefix(
        scope: Scope, guard: Guard, old: Progress, throughId: Long,
        snapshot: TrackingHistoryReadSnapshot, pointTablesChanged: Boolean,
    ): Next {
        val candidateCount = purpose.savedSessionCandidateCount(scope.logicalId, scope.runId, scope.segmentId)
        if (candidateCount < old.candidateCount) {
            return seed(scope, guard, throughId, snapshot)
        }
        if (throughId == old.throughId && pointTablesChanged && candidateCount == old.candidateCount &&
            snapshot.sourceEvidenceRevision == old.read.readSnapshot.sourceEvidenceRevision) {
            // The Room notice and post-commit hint can report the same committed snapshot twice.
            return Next(old.read, old)
        }
        return appendSavedPrefix(old, throughId, snapshot, pointTablesChanged, candidateCount)
    }

    private fun SourceEvidenceState.hasValidLiveSummaryShape(): Boolean {
        val retainedFrom = retainedFromMs
        return revision >= 0L && collectedDataEpoch >= 0L &&
            deletedSourceEventHighWaterOrdinal >= 0L && (retainedFrom == null || retainedFrom >= 0L)
    }

    private fun needsSeed(old: Progress, scope: Scope, guard: Guard, throughId: Long, rebuild: Boolean): Boolean =
        old.scope != scope || old.guard != guard || throughId < old.throughId || rebuild

    private suspend fun appendSavedPrefix(
        old: Progress,
        throughId: Long,
        snapshot: TrackingHistoryReadSnapshot,
        pointTablesChanged: Boolean,
        candidateCount: Long,
    ): Next {
        // refresh established exact Scope/Guard equality before reaching this existing prefix.
        val scope = old.scope
        val guard = old.guard
        var afterId = old.throughId
        var addedCandidates = 0L
        var latestPosition = old.latestPosition
        var count = old.read.summary.pointCount
        var latestFix = old.read.summary.latestFix
        val distance = SavedTripDistanceAccumulator(old.distance)
        val gaps = old.read.summary.qualificationGaps.toMutableSet()
        var partial = old.read.summary.partial
        var problem: HistoryProblemReason? = null
        while (afterId < throughId) {
            currentCoroutineContext().ensureActive()
            val page = continuationReceiptPage(scope, afterId, throughId)
            if (page.isEmpty()) {
                break
            }
            addedCandidates = Math.addExact(addedCandidates, page.size.toLong())
            val ordered = page.sortedWith(compareBy({ it.observedWallTimeMs }, { it.sampleId }))
            if (ordered.beginsBeforeSavedPosition(latestPosition)) {
                return seed(scope, guard, throughId, snapshot)
            }
            val qualified = summaries.qualifyCandidatesInTransaction(ordered, scope::matches)
            gaps += qualified.gaps
            val added = appendQualifiedPoints(ordered, qualified, distance, count, latestFix)
            partial = partial || added.first - count != ordered.size.toLong()
            count = added.first
            latestFix = added.second
            val last = ordered.last()
            latestPosition = SavedLocationPosition(last.observedWallTimeMs, last.sampleId)
            // A problem ends this loop below, so every entered page starts without one.
            problem = qualified.problem
            afterId = page.last().sampleId
            if (problem != null || page.size < PAGE_SIZE) {
                break
            }
        }
        // Native originals are immutable in the actual writers. Counts detect erased membership or
        // an older receipt gaining this scope; explicit edits use the separate correction companion.
        if (needsSeedAfterAppend(old, addedCandidates, candidateCount, throughId, pointTablesChanged)) {
            return seed(scope, guard, throughId, snapshot)
        }
        return completeSavedPrefix(old, throughId, candidateCount, snapshot, distance,
            count, latestFix, partial, gaps, problem, latestPosition)
    }

    // Carry the existing measured prefix into one summary/cache publication without another state model.
    @Suppress("LongParameterList")
    private fun completeSavedPrefix(
        old: Progress,
        throughId: Long,
        candidateCount: Long,
        snapshot: TrackingHistoryReadSnapshot,
        distance: SavedTripDistanceAccumulator,
        count: Long,
        latestFix: SavedLocationFix?,
        partial: Boolean,
        gaps: Set<RadioLocationContextGap>,
        problem: HistoryProblemReason?,
        latestPosition: SavedLocationPosition?,
    ): Next {
        val meters = distance.measuredMeters
        if (meters?.isFinite() == false) {
            throw InvalidContinuation()
        }
        val read = savedLocationSummary(SavedLocationSummarySelection.Session(old.scope.segmentId), snapshot,
            distance, count, latestFix, partial, gaps, problem)
        // An incomplete qualification must not advance an incremental prefix as if it were complete.
        if (problem != null) {
            return Next(read, null)
        }
        return Next(read, Progress(old.scope, old.guard, throughId, candidateCount, latestPosition,
            distance.snapshotState(), read))
    }

    private fun needsSeedAfterAppend(
        old: Progress, addedCandidates: Long, candidateCount: Long, throughId: Long, pointTablesChanged: Boolean,
    ): Boolean = Math.addExact(old.candidateCount, addedCandidates) != candidateCount ||
        (throughId == old.throughId && pointTablesChanged)

    private suspend fun appendQualifiedPoints(
        ordered: List<LocationAcceptedReceiptEntity>, qualified: SavedLocationQualifiedPage,
        distance: SavedTripDistanceAccumulator, initialCount: Long,
        initialFix: SavedLocationFix?,
    ): Pair<Long, SavedLocationFix?> {
        var count = initialCount
        var latestFix = initialFix
        for (receipt in ordered) {
            currentCoroutineContext().ensureActive()
            val row = qualified.samples[receipt.sampleId]
            if (row == null) {
                distance.breakContinuity()
                continue
            }
            val fix = row.toSavedFix()
            distance.add(LocationPathSampleRow(row.sample,
                row.receipt.sourceInstanceId + ":" + row.receipt.registrationGeneration,
                row.receipt.sourceSequence, false), fix)
            if (fix != null) {
                count = Math.addExact(count, 1L)
                latestFix = fix
            }
        }
        return count to latestFix
    }

    private suspend fun continuationReceiptPage(
        scope: Scope, afterId: Long, throughId: Long,
    ): List<LocationAcceptedReceiptEntity> {
        val page = purpose.savedSessionReceiptPageAfterId(scope.logicalId, scope.runId, scope.segmentId, afterId,
            throughId, PAGE_SIZE)
        if (page.size > PAGE_SIZE || page.any { it.sampleId <= afterId || it.sampleId > throughId } ||
            page.zipWithNext().any { (a, b) -> a.sampleId >= b.sampleId }) {
            throw InvalidContinuation()
        }
        return page
    }

    private suspend fun seed(scope: Scope, guard: Guard, throughId: Long,
        snapshot: TrackingHistoryReadSnapshot): Next {
        var distance: SavedTripDistanceState? = null
        var position: SavedLocationPosition? = null
        val read = summaries.readSessionInTransaction(
            scope.segmentId, snapshot, includeRoutePreview = true,
        ) { state, last ->
            distance = state
            position = last
        }
        if (read !is SavedLocationSummaryRead.Snapshot || read.problem != null || distance == null) {
            return Next(read, null)
        }
        val count = purpose.savedSessionCandidateCount(scope.logicalId, scope.runId, scope.segmentId)
        if (count < 0L) {
            throw InvalidContinuation()
        }
        return Next(read, Progress(scope, guard, throughId, count, position, requireNotNull(distance), read))
    }

    private data class Notice(val tables: Set<String>)
    private data class Wakeup(val pointVersion: Long, val rebuildVersion: Long)
    private data class Scope(val segmentId: Long, val logicalId: String, val runId: String) {
        fun matches(scope: RadioContextScope): Boolean = (scope as? RadioContextScope.Session)?.owner?.let {
            it.sessionSegmentId == segmentId && it.logicalTrackingId == logicalId && it.serviceRunId == runId
        } == true
    }
    private data class Guard(val epoch: Long, val retainedFromMs: Long?, val deletedHighWater: Long,
        val exportStamp: String)
    private data class Progress(val scope: Scope, val guard: Guard, val throughId: Long,
        val candidateCount: Long, val latestPosition: SavedLocationPosition?, val distance: SavedTripDistanceState,
        val read: SavedLocationSummaryRead.Snapshot)
    private data class Next(val read: SavedLocationSummaryRead, val progress: Progress?)
    private class InvalidContinuation : IllegalStateException("Invalid saved Location continuation")
    private companion object {
        const val PAGE_SIZE = 256
        val POINT_TABLES = setOf("location_sample", "location_accepted_receipt", "location_sample_purpose_authority")
        val REBUILD_TABLES = setOf("source_deletion_fence", "collected_data_deletion_operation",
            "location_point_correction",
            "source_policy", "source_consent_epoch", "source_service_run", "session_manifest_version",
            "session_manifest_source", "ambient_location_retention_authority", "location_file_identity",
            "location_file_archive", "location_file_archive_point")
        val TABLES = (POINT_TABLES + REBUILD_TABLES + setOf("session_segment", "source_evidence_state")).toTypedArray()
    }
}

private fun List<LocationAcceptedReceiptEntity>.beginsBeforeSavedPosition(previous: SavedLocationPosition?): Boolean {
    if (previous == null) {
        return false
    }
    val first = first()
    return SavedLocationPosition(first.observedWallTimeMs, first.sampleId) <= previous
}
