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
        snapshot: TrackingHistoryReadSnapshot): SavedLocationSummaryRead {
        val segment = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
            ?: return SavedLocationSummaryRead.NotFound
        val logicalId = segment.logicalTrackingId
        val runId = segment.serviceRunId
        if (logicalId.isNullOrBlank() || runId.isNullOrBlank()) {
            // Older/imported ordinary history remains readable through its existing compatibility reader.
            return SavedLocationSummaryRead.Unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
        return readPages(SavedLocationSummarySelection.Session(segmentId), snapshot,
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
        nextPage: suspend (SavedLocationPosition?) -> List<SavedLocationCandidate>): SavedLocationSummaryRead {
        val accumulator = SavedTripDistanceAccumulator()
        var count = 0L
        var latest: SavedLocationFix? = null
        var after: SavedLocationPosition? = null
        var partial = false
        val gaps = linkedSetOf<RadioLocationContextGap>()
        fun result(problem: HistoryProblemReason? = null) = SavedLocationSummaryRead.Snapshot(selection, snapshot,
            SavedLocationSummary(count, latest, accumulator.measuredMeters?.takeIf { it.isFinite() },
                partial || accumulator.hasDisconnectedSections || gaps.isNotEmpty() || problem != null, gaps.toSet()), problem)
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
                val accepted = mutableMapOf<Long, QualifiedRetainedLocationSample>()
                var pageProblem: HistoryProblemReason? = null
                suspend fun qualify(receipts: List<LocationAcceptedReceiptEntity>) {
                    currentCoroutineContext().ensureActive()
                    val read = retained.qualifySavedInTransaction(receipts)
                    // A busy window can contain many independent runs. Split only the bounded batch,
                    // never discard the whole selection because optional dependencies exceed one call.
                    if (RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT in read.gaps && receipts.size > 1) {
                        val middle = receipts.size / 2
                        qualify(receipts.subList(0, middle))
                        qualify(receipts.subList(middle, receipts.size))
                    } else {
                        gaps += read.gaps
                        read.samples.filter { scopeMatches(it.scope) }.forEach { accepted.putIfAbsent(it.sample.id, it) }
                        if (read.historyProblem != null) pageProblem = read.historyProblem
                        if (RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT in read.gaps) {
                            pageProblem = HistoryProblemReason.DEPENDENCY_OVERFLOW
                        }
                    }
                }
                qualify(page.map { it.receipt })
                for (candidate in page) {
                    currentCoroutineContext().ensureActive()
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

    private fun QualifiedRetainedLocationSample.toSavedFix(): SavedLocationFix? {
        val latitude = sample.latE7?.takeIf { it in -900_000_000..900_000_000 } ?: return null
        val longitude = sample.lonE7?.takeIf { it in -1_800_000_000..1_800_000_000 } ?: return null
        return SavedLocationFix(sample.id, sample.timeMs, receipt.wallTimeUncertaintyMs, latitude, longitude,
            sample.speedMps.validNonNegative(), sample.speedAccuracyMps.validNonNegative(), sample.hAccM.validNonNegative(),
            sample.altitudeM?.takeIf { it.isFinite() }, sample.altitudeDatum, sample.altitudeSource, sample.vAccM.validNonNegative())
    }

    private fun Float?.validNonNegative(): Float? = this?.takeIf { it.isFinite() && it >= 0f }
    private class InvalidSavedLocationPage : IllegalStateException("Invalid saved Location page")
    private companion object { const val POINT_PAGE_SIZE = 256 }
}

private data class SavedLocationCandidate(val receipt: LocationAcceptedReceiptEntity, val timeMs: Long) {
    val position: SavedLocationPosition get() = SavedLocationPosition(timeMs, receipt.sampleId)
}
private data class SavedLocationPosition(val timeMs: Long, val sampleId: Long) : Comparable<SavedLocationPosition> {
    override fun compareTo(other: SavedLocationPosition): Int = timeMs.compareTo(other.timeMs).takeIf { it != 0 }
        ?: sampleId.compareTo(other.sampleId)
}
