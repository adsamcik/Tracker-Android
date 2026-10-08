package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.database.dao.LocationPurposeDao
import com.adsamcik.tracker.stats.api.repository.*
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
                if (notice.tables.any { it in POINT_TABLES }) pointVersion = Math.addExact(pointVersion, 1L)
                if (notice.tables.any { it in REBUILD_TABLES }) rebuildVersion = Math.addExact(rebuildVersion, 1L)
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

    private suspend fun refresh(segmentId: Long, old: Progress?, pointTablesChanged: Boolean, rebuild: Boolean): Next {
        val evidence = database.sourceEvidenceStateDao().get()
            ?.takeIf { it.revision >= 0L && it.collectedDataEpoch >= 0L &&
                it.deletedSourceEventHighWaterOrdinal >= 0L && (it.retainedFromMs == null || it.retainedFromMs >= 0L) }
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
        if (throughId < 0L) throw InvalidContinuation()
        if (old == null || old.scope != scope || old.guard != guard ||
            throughId < old.throughId || rebuild) {
            return seed(scope, guard, throughId, snapshot)
        }
        if (throughId == old.throughId && !pointTablesChanged) {
            return Next(old.read.copy(readSnapshot = snapshot), old.copy(read = old.read.copy(readSnapshot = snapshot)))
        }
        val candidateCount = purpose.savedSessionCandidateCount(logicalId, runId, segmentId)
        if (candidateCount < old.candidateCount) return seed(scope, guard, throughId, snapshot)
        if (throughId == old.throughId && pointTablesChanged && candidateCount == old.candidateCount &&
            snapshot.sourceEvidenceRevision == old.read.readSnapshot.sourceEvidenceRevision) {
            // The Room notice and post-commit hint can report the same committed snapshot twice.
            return Next(old.read, old)
        }
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
            val page = purpose.savedSessionReceiptPageAfterId(logicalId, runId, segmentId, afterId, throughId, PAGE_SIZE)
            if (page.size > PAGE_SIZE || page.any { it.sampleId <= afterId || it.sampleId > throughId } ||
                page.zipWithNext().any { (a, b) -> a.sampleId >= b.sampleId }) throw InvalidContinuation()
            if (page.isEmpty()) break
            addedCandidates = Math.addExact(addedCandidates, page.size.toLong())
            val ordered = page.sortedWith(compareBy({ it.observedWallTimeMs }, { it.sampleId }))
            if (latestPosition?.let { previous -> ordered.first().let {
                    SavedLocationPosition(it.observedWallTimeMs, it.sampleId) <= previous } } == true) {
                return seed(scope, guard, throughId, snapshot)
            }
            val qualified = summaries.qualifyCandidatesInTransaction(ordered, scope::matches)
            gaps += qualified.gaps
            for (receipt in ordered) {
                currentCoroutineContext().ensureActive()
                latestPosition = SavedLocationPosition(receipt.observedWallTimeMs, receipt.sampleId)
                val row = qualified.samples[receipt.sampleId]
                if (row == null) {
                    distance.breakContinuity()
                    partial = true
                    continue
                }
                distance.add(LocationPathSampleRow(row.sample,
                    row.receipt.sourceInstanceId + ":" + row.receipt.registrationGeneration,
                    row.receipt.sourceSequence, false))
                val fix = row.toSavedFix()
                if (fix == null) partial = true else {
                    count = Math.addExact(count, 1L)
                    latestFix = fix
                }
            }
            if (qualified.problem != null) problem = qualified.problem
            afterId = page.last().sampleId
            if (problem != null || page.size < PAGE_SIZE) break
        }
        // Native originals are immutable in the actual writers. Counts detect erased membership or
        // an older receipt gaining this scope; explicit edits use the separate correction companion.
        if (Math.addExact(old.candidateCount, addedCandidates) != candidateCount ||
            (throughId == old.throughId && pointTablesChanged)) return seed(scope, guard, throughId, snapshot)
        val meters = distance.measuredMeters
        if (meters?.isFinite() == false) throw InvalidContinuation()
        val read = SavedLocationSummaryRead.Snapshot(SavedLocationSummarySelection.Session(segmentId), snapshot,
            SavedLocationSummary(count, latestFix, meters,
                partial || distance.hasDisconnectedSections || gaps.isNotEmpty() || problem != null, gaps,
                distance.movingAverageSpeedMetersPerSecond), problem)
        // An incomplete qualification must not advance an incremental prefix as if it were complete.
        if (problem != null) return Next(read, null)
        return Next(read, Progress(scope, guard, throughId, candidateCount, latestPosition, distance.snapshotState(), read))
    }

    private suspend fun seed(scope: Scope, guard: Guard, throughId: Long,
        snapshot: TrackingHistoryReadSnapshot): Next {
        var distance: SavedTripDistanceState? = null
        var position: SavedLocationPosition? = null
        val read = summaries.readSessionInTransaction(scope.segmentId, snapshot) { state, last ->
            distance = state
            position = last
        }
        if (read !is SavedLocationSummaryRead.Snapshot || read.problem != null || distance == null) return Next(read, null)
        val count = purpose.savedSessionCandidateCount(scope.logicalId, scope.runId, scope.segmentId)
        if (count < 0L) throw InvalidContinuation()
        return Next(read, Progress(scope, guard, throughId, count, position, requireNotNull(distance), read))
    }

    private data class Notice(val tables: Set<String>)
    private data class Wakeup(val pointVersion: Long, val rebuildVersion: Long)
    private data class Scope(val segmentId: Long, val logicalId: String, val runId: String) {
        fun matches(scope: RadioContextScope): Boolean = (scope as? RadioContextScope.Session)?.owner?.let {
            it.sessionSegmentId == segmentId && it.logicalTrackingId == logicalId && it.serviceRunId == runId
        } == true
    }
    private data class Guard(val epoch: Long, val retainedFromMs: Long?, val deletedHighWater: Long, val exportStamp: String)
    private data class Progress(val scope: Scope, val guard: Guard, val throughId: Long,
        val candidateCount: Long, val latestPosition: SavedLocationPosition?, val distance: SavedTripDistanceState,
        val read: SavedLocationSummaryRead.Snapshot)
    private data class Next(val read: SavedLocationSummaryRead, val progress: Progress?)
    private class InvalidContinuation : IllegalStateException("Invalid saved Location continuation")
    private companion object {
        const val PAGE_SIZE = 256
        val POINT_TABLES = setOf("location_sample", "location_accepted_receipt", "location_sample_purpose_authority")
        val REBUILD_TABLES = setOf("source_deletion_fence", "collected_data_deletion_operation",
            "source_policy", "source_consent_epoch", "source_service_run", "session_manifest_version",
            "session_manifest_source", "ambient_location_retention_authority", "location_file_identity",
            "location_file_archive", "location_file_archive_point")
        val TABLES = (POINT_TABLES + REBUILD_TABLES + setOf("session_segment", "source_evidence_state")).toTypedArray()
    }
}
