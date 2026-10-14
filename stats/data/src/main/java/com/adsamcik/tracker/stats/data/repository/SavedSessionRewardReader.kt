package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Reuses original recording bounds and the same current saved Location summary as the product. */
internal class SavedSessionRewardReader(
    private val database: AppDatabase,
    private val io: CoroutineDispatcher,
    private val location: SavedLocationSummaryReader,
) {
    suspend fun read(segmentId: Long): SavedSessionRewardRead = withContext(io) {
        try {
            database.withTransaction {
                val state = database.sourceEvidenceStateDao().get() ?: return@withTransaction SavedSessionRewardRead.Unavailable
                val revision = Math.addExact(state.revision, 1L)
                val segment = database.sessionSegmentDao().getById(segmentId)
                    ?: return@withTransaction SavedSessionRewardRead.Removed(state.collectedDataEpoch, revision)
                val logicalId = segment.logicalTrackingId ?: return@withTransaction SavedSessionRewardRead.Legacy
                val runId = segment.serviceRunId ?: return@withTransaction SavedSessionRewardRead.Legacy
                val run = database.sourceSessionDao().serviceRun(runId) ?: return@withTransaction SavedSessionRewardRead.Unavailable
                val session = database.sourceSessionDao().session(logicalId) ?: return@withTransaction SavedSessionRewardRead.Unavailable
                val receiptKey = database.xpLedgerDao().sessionEffect(segmentId)?.sourceKey
                val receiptParts = receiptKey?.split(':').orEmpty()
                val receiptEpoch = receiptParts.takeIf { it.size == 3 && it[0] == "recording" &&
                    it[2].toLongOrNull() == segmentId }?.get(1)?.toLongOrNull()
                val epochs = database.sourceSessionDao().recordingEpochs(logicalId, runId)
                if (epochs.size > 1 || receiptKey != null && receiptEpoch == null) {
                    return@withTransaction SavedSessionRewardRead.Unavailable
                }
                // Existing receipts own their identity even after all observations are removed.
                // A duration-only recording without a receipt creates its first receipt now.
                val recordingEpoch = receiptEpoch ?: epochs.singleOrNull() ?: state.collectedDataEpoch
                if (!state.acceptsRetainedEpoch(recordingEpoch)) return@withTransaction SavedSessionRewardRead.Unavailable
                val end = segment.endTimeMs
                if (run.completedAtMs == null || run.sessionSegmentId != segmentId ||
                    run.logicalTrackingId != logicalId || run.startedAtMs < 0 || end < run.startedAtMs ||
                    session.startedAtMs < 0) {
                    return@withTransaction SavedSessionRewardRead.Unavailable
                }
                val summary = location.readSessionInTransaction(segmentId,
                    TrackingHistoryReadSnapshot(state.collectedDataEpoch, state.revision))
                if (summary !is SavedLocationSummaryRead.Snapshot || summary.problem != null) {
                    return@withTransaction SavedSessionRewardRead.Unavailable
                }
                SavedSessionRewardRead.Ready(SavedSessionReward(segmentId, recordingEpoch,
                    revision, end, end - run.startedAtMs, summary.summary.distanceMeters))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SavedSessionRewardRead.Unavailable
        }
    }
}
