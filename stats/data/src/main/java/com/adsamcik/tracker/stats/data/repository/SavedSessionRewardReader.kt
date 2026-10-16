package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.stats.api.repository.SavedSessionReward
import com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
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
                val state = database.sourceEvidenceStateDao().get() ?:
                    return@withTransaction SavedSessionRewardRead.Unavailable
                val revision = Math.addExact(state.revision, 1L)
                val segment = database.sessionSegmentDao().getById(segmentId)
                    ?: return@withTransaction SavedSessionRewardRead.Removed(state.collectedDataEpoch, revision)
                val logicalId = segment.logicalTrackingId ?: return@withTransaction SavedSessionRewardRead.Legacy
                val runId = segment.serviceRunId ?: return@withTransaction SavedSessionRewardRead.Legacy
                readRecordedReward(segmentId, segment, logicalId, runId, state, revision)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SavedSessionRewardRead.Unavailable
        }
    }

    // Ordered missing-row and invalid-summary exits preserve the first unavailable prerequisite.
    @Suppress("ReturnCount")
    private suspend fun readRecordedReward(
        segmentId: Long,
        segment: SessionSegment,
        logicalId: String,
        runId: String,
        state: SourceEvidenceState,
        revision: Long,
    ): SavedSessionRewardRead {
        val run = database.sourceSessionDao().serviceRun(runId) ?:
            return SavedSessionRewardRead.Unavailable
        val session = database.sourceSessionDao().session(logicalId) ?:
            return SavedSessionRewardRead.Unavailable
        val recordingEpoch = retainedRecordingEpoch(segmentId, logicalId, runId, state)
            ?: return SavedSessionRewardRead.Unavailable
        val end = segment.endTimeMs
        if (hasInvalidCompletion(run, segmentId, logicalId, end, session)) {
            return SavedSessionRewardRead.Unavailable
        }
        val summary = location.readSessionInTransaction(segmentId,
            TrackingHistoryReadSnapshot(state.collectedDataEpoch, state.revision))
        if (summary !is SavedLocationSummaryRead.Snapshot || summary.problem != null) {
            return SavedSessionRewardRead.Unavailable
        }
        return SavedSessionRewardRead.Ready(SavedSessionReward(segmentId, recordingEpoch,
            revision, end, end - run.startedAtMs, summary.summary.distanceMeters))
    }

    private suspend fun retainedRecordingEpoch(
        segmentId: Long,
        logicalId: String,
        runId: String,
        state: SourceEvidenceState,
    ): Long? {
        val receiptKey = database.xpLedgerDao().sessionEffect(segmentId)?.sourceKey
        val receiptEpoch = recordingEpochFromReceipt(receiptKey, segmentId)
        val epochs = database.sourceSessionDao().recordingEpochs(logicalId, runId)
        if (epochs.size > 1 || receiptKey != null && receiptEpoch == null) {
            return null
        }
        // Existing receipts own their identity even after all observations are removed.
        // A duration-only recording without a receipt creates its first receipt now.
        val recordingEpoch = receiptEpoch ?: epochs.singleOrNull() ?: state.collectedDataEpoch
        if (!state.acceptsRetainedEpoch(recordingEpoch)) {
            return null
        }
        return recordingEpoch
    }

    private fun recordingEpochFromReceipt(receiptKey: String?, segmentId: Long): Long? {
        val receiptParts = receiptKey?.split(':').orEmpty()
        return receiptParts.takeIf { it.size == RECORDING_RECEIPT_PART_COUNT && it[0] == "recording" &&
            it[2].toLongOrNull() == segmentId }?.get(1)?.toLongOrNull()
    }

    private fun hasInvalidCompletion(
        run: SourceServiceRunEntity,
        segmentId: Long,
        logicalId: String,
        end: Long,
        session: LogicalTrackingSessionEntity,
    ): Boolean = run.completedAtMs == null || run.sessionSegmentId != segmentId ||
        run.logicalTrackingId != logicalId || run.startedAtMs < 0 || end < run.startedAtMs ||
        session.startedAtMs < 0
}

private const val RECORDING_RECEIPT_PART_COUNT = 3
