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
 * provider, projection, repair or correction writer. The approved effective correction reader is a
 * separate assembly prerequisite; original rows must not be changed to bypass their receipt.
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

    /** The caller must own the same transaction and source-evidence snapshot as its other products. */
    internal suspend fun readSessionInTransaction(segmentId: Long,
        snapshot: TrackingHistoryReadSnapshot,
        onState: ((SavedTripDistanceState, SavedLocationPosition?) -> Unit)? = null): SavedLocationSummaryRead {
        val segment = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
            ?: return SavedLocationSummaryRead.NotFound
        val logicalId = segment.logicalTrackingId
        val runId = segment.serviceRunId
        if (logicalId.isNullOrBlank() || runId.isNullOrBlank()) {
            // Older/imported ordinary history remains readable through its existing compatibility reader.
            return SavedLocationSummaryRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
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
                    after?.timeMs, after?.sampleId, POINT_PAGE_SIZE)
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

    /** Any independently retained native purpose can contribute to the same measured-date window. */
    internal suspend fun readWindowInTransaction(window: HistoryObservationWindow,
        snapshot: TrackingHistoryReadSnapshot): SavedLocationSummaryRead =
        readPages(SavedLocationSummarySelection.Window(window), snapshot, scopeMatches = { true },
            nextPage = { after -> database.locationPurposeDao().savedReceiptPageOverWindow(window.fromInclusiveMs,
                window.toExclusiveMs, after?.timeMs, after?.sampleId, POINT_PAGE_SIZE)
                .map { SavedLocationCandidate(it, it.observedWallTimeMs) } })

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
        onState: ((SavedTripDistanceState, SavedLocationPosition?) -> Unit)? = null): SavedLocationSummaryRead {
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
                val qualified = qualifyCandidatesInTransaction(page.map { it.receipt }, scopeMatches)
                val accepted = qualified.samples
                val pageProblem = qualified.problem
                gaps += qualified.gaps
                for (candidate in page) {
                    currentCoroutineContext().ensureActive()
                    processed = candidate.position
                    val row = accepted[candidate.receipt.sampleId]
                    if (row == null) {
                        accumulator.breakContinuity()
                        partial = true
                        continue
                    }
                    val sample = row.sample
                    accumulator.add(LocationPathSampleRow(sample,
                        row.receipt.sourceInstanceId + ":" + row.receipt.registrationGeneration,
                        row.receipt.sourceSequence, row.scope is RadioContextScope.Ambient))
                    val fix = row.toSavedFix()
                    if (fix == null) {
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

private data class SavedLocationCandidate(val receipt: LocationAcceptedReceiptEntity, val timeMs: Long) {
    val position: SavedLocationPosition get() = SavedLocationPosition(timeMs, receipt.sampleId)
}
internal data class SavedLocationPosition(val timeMs: Long, val sampleId: Long) : Comparable<SavedLocationPosition> {
    override fun compareTo(other: SavedLocationPosition): Int = timeMs.compareTo(other.timeMs).takeIf { it != 0 }
        ?: sampleId.compareTo(other.sampleId)
}

internal fun QualifiedRetainedLocationSample.toSavedFix(): SavedLocationFix? {
        val latitude = sample.latE7?.takeIf { it in -900_000_000..900_000_000 } ?: return null
        val longitude = sample.lonE7?.takeIf { it in -1_800_000_000..1_800_000_000 } ?: return null
        return SavedLocationFix(sample.id, sample.timeMs, receipt.wallTimeUncertaintyMs, latitude, longitude,
            sample.speedMps.validNonNegative(), sample.speedAccuracyMps.validNonNegative(), sample.hAccM.validNonNegative(),
            sample.altitudeM?.takeIf { it.isFinite() }, sample.altitudeDatum, sample.altitudeSource, sample.vAccM.validNonNegative())
    }

private fun Float?.validNonNegative(): Float? = this?.takeIf { it.isFinite() && it >= 0f }

internal data class SavedLocationQualifiedPage(val samples: Map<Long, QualifiedRetainedLocationSample>,
    val gaps: Set<RadioLocationContextGap>, val problem: HistoryProblemReason?)
