package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Read-only native saved measurements. One Room snapshot, keyset pages and constant-memory distance.
 * Qualification batches are finite; the total point count is not capped. This reader has no cache,
 * provider, projection, repair or correction writer. Original rows qualify native ownership;
 * the shared effective view supplies the user's current measurements.
 */
internal class SavedLocationSummaryReader @Inject constructor(
    private val database: AppDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val retained = RetainedLocationContextReader(database)

    suspend fun readSession(segmentId: Long): SavedLocationSummaryRead {
        require(segmentId > 0L)
        return read { readSessionInTransaction(segmentId, it) }
    }

    suspend fun readWindow(window: HistoryObservationWindow): SavedLocationSummaryRead =
        read { readWindowInTransaction(window, it) }

    suspend fun readSessionWindow(segmentId: Long, window: HistoryObservationWindow): SavedLocationSummaryRead =
        read { readSessionInTransaction(segmentId, it, window = window) }

    suspend fun readLogicalSession(logicalId: String): SavedLocationSummaryRead = read { snapshot ->
        val session = database.sourceSessionDao().session(logicalId) ?: return@read SavedLocationSummaryRead.NotFound
        val runs = mutableListOf<String>()
        var after: String? = null
        var afterStarted: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = database.trackingHistoryReadDao().logicalEntryServiceRunPage(listOf(logicalId),
                POINT_PAGE_SIZE, if (after == null) null else logicalId, afterStarted, after)
            runs += page.map { it.serviceRunId }
            if (page.size < POINT_PAGE_SIZE) break
            after = page.last().serviceRunId
            afterStarted = page.last().startedAtMs
        }
        if (runs.isEmpty()) return@read SavedLocationSummaryRead.Snapshot(
            SavedLocationSummarySelection.LogicalSession(logicalId), snapshot, SavedLocationSummary(0, null, null, false))
        val result = readPages(SavedLocationSummarySelection.LogicalSession(logicalId), snapshot,
            scopeMatches = { (it as? RadioContextScope.Session)?.owner?.logicalTrackingId == logicalId },
            nextPage = { cursor ->
                // Keep SQLite argument batches finite while merging each run's next ordered page.
                val rows = runs.chunked(64).flatMap { members ->
                    database.locationPurposeDao().savedLogicalTripPointPage(logicalId, members,
                        cursor?.timeMs, cursor?.sampleId, POINT_PAGE_SIZE)
                }.sortedWith(compareBy({ it.sample.timeMs }, { it.sample.id })).take(POINT_PAGE_SIZE)
                val ids = rows.map { it.sample.id }
                val receipts = database.locationPurposeDao().contextReceiptsForSamples(ids, ids.size + 1)
                    .associateBy { it.sampleId }
                rows.map { SavedLocationCandidate(requireNotNull(receipts[it.sample.id]), it.sample.timeMs) }
            })
        if (result is SavedLocationSummaryRead.Snapshot &&
            database.sourceEvidenceStateDao().get()?.retainedFromMs?.let { session.startedAtMs < it } == true) {
            result.copy(summary = result.summary.copy(partial = true))
        } else result
    }

    /** The caller must own the same transaction and source-evidence snapshot as its other products. */
    internal suspend fun readSessionInTransaction(segmentId: Long,
        snapshot: TrackingHistoryReadSnapshot,
        window: HistoryObservationWindow? = null,
        onState: ((SavedTripDistanceState, SavedLocationPosition?) -> Unit)? = null): SavedLocationSummaryRead {
        val segment = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
            ?: return SavedLocationSummaryRead.NotFound
        val logicalId = segment.logicalTrackingId
        val runId = segment.serviceRunId
        if (logicalId.isNullOrBlank() || runId.isNullOrBlank()) {
            return readPages(SavedLocationSummarySelection.Session(segmentId), snapshot,
                scopeMatches = { false }, onState = onState, nextPage = { after ->
                    database.locationPurposeDao().savedLegacyTripPointPage(maxOf(segment.startTimeMs,
                        window?.fromInclusiveMs ?: 0L), minOf(segment.endTimeMs,
                        window?.toExclusiveMs?.minus(1L) ?: Long.MAX_VALUE), after?.timeMs, after?.sampleId, POINT_PAGE_SIZE)
                        .map { SavedLocationCandidate(null, it.sample.timeMs, it) }
                })
        }
        val candidates = database.locationPurposeDao().savedSessionCandidateCount(logicalId, runId, segmentId)
        if (candidates < 0L) return SavedLocationSummaryRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        val missingPrefix = database.sourceEvidenceStateDao().get()?.retainedFromMs?.let {
            segment.startTimeMs < it
        } == true
        val read = readPages(SavedLocationSummarySelection.Session(segmentId), snapshot, onState = onState,
            scopeMatches = { scope -> (scope as? RadioContextScope.Session)?.owner?.let {
                it.sessionSegmentId == segmentId && it.logicalTrackingId == logicalId && it.serviceRunId == runId
            } == true }, nextPage = { after ->
                val rows = database.locationPurposeDao().savedLogicalTripPointPage(logicalId, listOf(runId),
                    after?.timeMs, after?.sampleId, POINT_PAGE_SIZE,
                    window?.fromInclusiveMs ?: 0L, window?.toExclusiveMs?.minus(1L) ?: Long.MAX_VALUE)
                val ids = rows.map { it.sample.id }
                val receipts = database.locationPurposeDao().contextReceiptsForSamples(ids, ids.size + 1)
                if (ids.distinct().size != ids.size || receipts.map { it.sampleId }.toSet() != ids.toSet() || receipts.size != ids.size) {
                    throw InvalidSavedLocationPage()
                }
                val byId = receipts.associateBy { it.sampleId }
                rows.map { row -> SavedLocationCandidate(requireNotNull(byId[row.sample.id]), row.sample.timeMs) }
            })
        return if (read is SavedLocationSummaryRead.Snapshot && (missingPrefix || candidates > read.summary.pointCount)) {
            read.copy(summary = read.summary.copy(partial = true))
        } else read
    }

    /** Native, imported and older saved data use the same current measured-date window. */
    internal suspend fun readWindowInTransaction(window: HistoryObservationWindow,
        snapshot: TrackingHistoryReadSnapshot,
        onGap: ((Long) -> Unit)? = null,
        onPoint: ((LocationPathSampleRow?) -> Unit)? = null): SavedLocationSummaryRead =
        readPages(SavedLocationSummarySelection.Window(window), snapshot, scopeMatches = { true },
            onPoint = onPoint,
            onGap = onGap,
            nextPage = { after ->
                val rows = database.locationPurposeDao().savedFileChunk(window.fromInclusiveMs,
                    window.toExclusiveMs - 1, after?.timeMs, after?.sampleId, POINT_PAGE_SIZE)
                val ids = rows.map { it.sample.id }
                val receipts = database.locationPurposeDao().contextReceiptsForSamples(ids, ids.size + 1)
                if (receipts.size > ids.size || receipts.map { it.sampleId }.distinct().size != receipts.size) {
                    throw InvalidSavedLocationPage()
                }
                val byId = receipts.associateBy { it.sampleId }
                rows.map { SavedLocationCandidate(byId[it.sample.id], it.sample.timeMs, it) }
            })

    private suspend fun read(block: suspend (TrackingHistoryReadSnapshot) -> SavedLocationSummaryRead): SavedLocationSummaryRead =
        withContext(io) {
            currentCoroutineContext().ensureActive()
            if (!database.isOpen) return@withContext SavedLocationSummaryRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
            try {
                database.withTransaction {
                    val state = database.sourceEvidenceStateDao().get()
                    if (state == null || !state.hasValidRangeShape()) {
                        SavedLocationSummaryRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                    } else block(TrackingHistoryReadSnapshot(state.collectedDataEpoch, state.revision))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: android.database.sqlite.SQLiteException) {
                SavedLocationSummaryRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
            } catch (_: RuntimeException) {
                SavedLocationSummaryRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            }
        }

    private suspend fun readPages(selection: SavedLocationSummarySelection, snapshot: TrackingHistoryReadSnapshot,
        scopeMatches: (RadioContextScope) -> Boolean,
        nextPage: suspend (SavedLocationPosition?) -> List<SavedLocationCandidate>,
        onPoint: ((LocationPathSampleRow?) -> Unit)? = null,
        onState: ((SavedTripDistanceState, SavedLocationPosition?) -> Unit)? = null,
        onGap: ((Long) -> Unit)? = null): SavedLocationSummaryRead {
        val accumulator = SavedTripDistanceAccumulator()
        var count = 0L
        var latest: SavedLocationFix? = null
        var after: SavedLocationPosition? = null
        var partial = false
        var processed: SavedLocationPosition? = null
        val gaps = linkedSetOf<RadioLocationContextGap>()
        fun result(problem: HistoryProblemReason? = null): SavedLocationSummaryRead.Snapshot {
            val measuredMeters = accumulator.measuredMeters
            val meters = measuredMeters?.takeIf { it.isFinite() }
            val read = SavedLocationSummaryRead.Snapshot(selection, snapshot,
                SavedLocationSummary(count, latest, meters,
                    partial || accumulator.hasDisconnectedSections || gaps.isNotEmpty() || problem != null, gaps.toSet(),
                    accumulator.movingAverageSpeedMetersPerSecond.takeIf { meters != null }), problem)
            // Preserve the saved-point snapshot, but never resume an invalid numerical prefix.
            if (measuredMeters?.isFinite() != false) onState?.invoke(accumulator.snapshotState(), processed)
            return read
        }
        try {
            val state = database.sourceEvidenceStateDao().get()
            if (state == null || !state.hasValidRangeShape() ||
                state.collectedDataEpoch != snapshot.collectedDataEpoch || state.revision != snapshot.sourceEvidenceRevision) {
                return SavedLocationSummaryRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = nextPage(after)
                if (page.size > POINT_PAGE_SIZE || page.zipWithNext().any { (left, right) -> left.position >= right.position } ||
                    after?.let { cursor -> page.firstOrNull()?.position?.let { it <= cursor } } == true) {
                    throw InvalidSavedLocationPage()
                }
                if (page.isEmpty()) return result()
                val qualified = qualifyCandidatesInTransaction(page.mapNotNull { it.receipt }, scopeMatches)
                val accepted = qualified.samples
                val pageProblem = qualified.problem
                gaps += qualified.gaps
                for (candidate in page) {
                    currentCoroutineContext().ensureActive()
                    processed = candidate.position
                    val row = accepted[candidate.position.sampleId]
                    val compatibility = candidate.compatibility?.takeIf { candidate.receipt == null }
                    if (row == null && compatibility == null) {
                        accumulator.breakContinuity()
                        onPoint?.invoke(null)
                        onGap?.invoke(candidate.timeMs)
                        partial = true
                        continue
                    }
                    val sample = row?.sample ?: requireNotNull(compatibility).sample
                    val path = if (row == null) requireNotNull(compatibility) else LocationPathSampleRow(sample,
                        if (sample.clockDomainId == null) null else row.receipt.sourceInstanceId +
                            ":" + row.receipt.registrationGeneration,
                        row.receipt.sourceSequence, row.scope is RadioContextScope.Ambient)
                    accumulator.add(path)
                    onPoint?.invoke(path)
                    val fix = sample.toSavedFix(row?.receipt?.wallTimeUncertaintyMs ?: 0L)
                    if (fix == null) {
                        onGap?.invoke(sample.timeMs)
                        partial = true
                    } else {
                        count = Math.addExact(count, 1L)
                        latest = fix
                    }
                }
                if (pageProblem != null) return result(pageProblem)
                if (accumulator.measuredMeters?.isFinite() == false) {
                    return result(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                }
                after = page.last().position
                if (page.size < POINT_PAGE_SIZE) return result()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: android.database.sqlite.SQLiteException) {
            return result(HistoryProblemReason.STORAGE_UNAVAILABLE)
        } catch (_: RuntimeException) {
            return result(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
    }

    /** Shared finite qualification for full reads and the temporary live continuation. */
    internal suspend fun qualifyCandidatesInTransaction(receipts: List<LocationAcceptedReceiptEntity>,
        scopeMatches: (RadioContextScope) -> Boolean): SavedLocationQualifiedPage {
        val accepted = linkedMapOf<Long, QualifiedRetainedLocationSample>()
        val gaps = linkedSetOf<RadioLocationContextGap>()
        var problem: HistoryProblemReason? = null
        suspend fun qualify(batch: List<LocationAcceptedReceiptEntity>) {
            currentCoroutineContext().ensureActive()
            val read = retained.qualifySavedInTransaction(batch)
            if (RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT in read.gaps && batch.size > 1) {
                val middle = batch.size / 2
                qualify(batch.subList(0, middle))
                qualify(batch.subList(middle, batch.size))
            } else {
                gaps += read.gaps
                read.samples.filter { scopeMatches(it.scope) }.forEach { accepted.putIfAbsent(it.sample.id, it) }
                if (read.historyProblem != null) problem = read.historyProblem
                if (RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT in read.gaps) problem = HistoryProblemReason.DEPENDENCY_OVERFLOW
            }
        }
        qualify(receipts)
        return SavedLocationQualifiedPage(accepted, gaps, problem)
    }

    private class InvalidSavedLocationPage : IllegalStateException("Invalid saved Location page")
    private companion object { const val POINT_PAGE_SIZE = 256 }
}

private data class SavedLocationCandidate(val receipt: LocationAcceptedReceiptEntity?, val timeMs: Long,
    val compatibility: LocationPathSampleRow? = null) {
    val position: SavedLocationPosition get() = SavedLocationPosition(timeMs,
        receipt?.sampleId ?: requireNotNull(compatibility).sample.id)
}
internal data class SavedLocationPosition(val timeMs: Long, val sampleId: Long) : Comparable<SavedLocationPosition> {
    override fun compareTo(other: SavedLocationPosition): Int = timeMs.compareTo(other.timeMs).takeIf { it != 0 }
        ?: sampleId.compareTo(other.sampleId)
}

internal fun QualifiedRetainedLocationSample.toSavedFix(): SavedLocationFix? = sample.toSavedFix(receipt.wallTimeUncertaintyMs)

private fun com.adsamcik.tracker.shared.base.database.data.LocationSample.toSavedFix(uncertainty: Long): SavedLocationFix? {
    val latitude = latE7?.takeIf { it in -900_000_000..900_000_000 } ?: return null
    val longitude = lonE7?.takeIf { it in -1_800_000_000..1_800_000_000 } ?: return null
    return SavedLocationFix(id, timeMs, uncertainty, latitude, longitude,
        speedMps.validNonNegative(), speedAccuracyMps.validNonNegative(), hAccM.validNonNegative(),
        altitudeM?.takeIf { it.isFinite() }, altitudeDatum, altitudeSource, vAccM.validNonNegative())
}

private fun Float?.validNonNegative(): Float? = this?.takeIf { it.isFinite() && it >= 0f }

internal data class SavedLocationQualifiedPage(val samples: Map<Long, QualifiedRetainedLocationSample>,
    val gaps: Set<RadioLocationContextGap>, val problem: HistoryProblemReason?)
