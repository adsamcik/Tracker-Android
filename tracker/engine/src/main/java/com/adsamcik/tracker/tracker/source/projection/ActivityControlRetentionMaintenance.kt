package com.adsamcik.tracker.tracker.source.projection

import android.os.SystemClock
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomaticStartActionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.pruneExpiredControlActivityWalBatch
import com.adsamcik.tracker.shared.base.database.ACTIVITY_CONTROL_RETENTION_NANOS
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import javax.inject.Inject
import javax.inject.Singleton

internal data class ActivityControlRetentionPruneResult(
    val outboxDeleted: Int,
    val walDeleted: Int,
    val outboxScanIncomplete: Boolean,
    val morePending: Boolean,
)

/** Bounded cleanup of Activity control effects, then their CONTROL-only raw carriers. */
@Singleton
class ActivityControlRetentionMaintenance @Inject constructor(
    private val database: AppDatabase,
    private val bootClockDomainProvider: BootClockDomainProvider,
    private val startupGate: TrackingStartupGate,
) {
    /** True when another bounded pass should run before ordinary control delivery. */
    suspend fun pruneDue(): Boolean = pruneBatch().morePending

    internal suspend fun pruneBatch(
        currentBootId: String = bootClockDomainProvider.current(),
        nowElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
        nowWallMs: Long = System.currentTimeMillis(),
        limit: Int = 128,
    ): ActivityControlRetentionPruneResult {
        require(currentBootId.isNotBlank())
        require(nowElapsedNanos >= 0L && nowWallMs >= 0L)
        require(limit in 1..256)
        val startupGeneration = startupGate.currentGeneration
        fun requireReadyGeneration() {
            check(startupGate.isReady && startupGate.currentGeneration == startupGeneration) {
                "Activity CONTROL cleanup lost its startup generation"
            }
        }
        val outboxPass = database.withTransaction {
            requireReadyGeneration()
            val actionDao = database.activityAutomaticStartActionDao()
            val action = actionDao.current()
            if (action != null && controlEvidenceExpired(
                action.bootId, action.receivedElapsedRealtimeNanos,
                currentBootId, nowElapsedNanos,
            ) && action.status in PENDING_ACTION_STATUSES) {
                check(actionDao.markTerminal(
                    action.triggerId, action.collectedDataEpoch, nowWallMs,
                    "ACTIVITY_CONTROL_RETENTION_EXPIRED",
                ) == 1)
            }
            val projectionDao = database.sourceProjectionStateDao()
            val savedCursor = projectionDao.checkpoint(CURSOR_PROJECTION_ID, CURSOR_VERSION)
                ?.contiguousAdmissionOrdinal ?: 0L
            require(savedCursor >= 0L)
            var removed = 0
            val page = projectionDao.activityAutomationOutboxPage(savedCursor, limit)
            for (effect in page) {
                val stamp = effect.retentionStampOrNull()
                if (stamp == null || controlEvidenceExpired(
                    stamp.bootId, stamp.receivedElapsedNanos, currentBootId, nowElapsedNanos,
                )) {
                    projectionDao.markOutboxTerminal(
                        effect.stableId,
                        if (stamp == null) "INVALID_AUTOMATION_EFFECT_PAYLOAD"
                        else "ACTIVITY_CONTROL_RETENTION_EXPIRED",
                        nowWallMs,
                    )
                    check(projectionDao.deleteActivityAutomationOutbox(effect.stableId) == 1)
                    removed++
                }
            }
            val scanIncomplete = page.size == limit
            val nextCursor = if (scanIncomplete) page.last().admissionOrdinal else 0L
            if (nextCursor != savedCursor) {
                projectionDao.saveCheckpoint(SourceProjectionCheckpointEntity(
                    projectionId = CURSOR_PROJECTION_ID,
                    projectionVersion = CURSOR_VERSION,
                    contiguousAdmissionOrdinal = nextCursor,
                    stateVersion = CURSOR_VERSION,
                    updatedAtMs = nowWallMs,
                ))
            }
            val terminal = actionDao.current()
            if (terminal != null && terminal.status == ActivityAutomaticStartActionEntity.STATUS_TERMINAL &&
                controlEvidenceExpired(
                    terminal.bootId, terminal.receivedElapsedRealtimeNanos,
                    currentBootId, nowElapsedNanos,
                )
            ) {
                check(actionDao.clearCompletedSlot(
                    terminal.triggerId, terminal.collectedDataEpoch,
                ) == 1)
            }
            requireReadyGeneration()
            removed to scanIncomplete
        }
        val walDeleted = database.pruneExpiredControlActivityWalBatch(
            currentBootId, nowElapsedNanos, nowWallMs, limit,
            verifyCollectedDataAccess = { requireReadyGeneration() },
        )
        return ActivityControlRetentionPruneResult(
            outboxDeleted = outboxPass.first,
            walDeleted = walDeleted,
            outboxScanIncomplete = outboxPass.second,
            morePending = outboxPass.second || walDeleted == limit,
        )
    }
}

internal fun controlEvidenceExpired(
    evidenceBootId: String,
    receivedElapsedNanos: Long,
    currentBootId: String,
    nowElapsedNanos: Long,
): Boolean = evidenceBootId != currentBootId || receivedElapsedNanos < 0L ||
    receivedElapsedNanos > nowElapsedNanos ||
    nowElapsedNanos - receivedElapsedNanos >= ACTIVITY_CONTROL_RETENTION_NANOS

private data class ControlRetentionStamp(val bootId: String, val receivedElapsedNanos: Long)

private fun SourceProjectionOutboxEntity.retentionStampOrNull(): ControlRetentionStamp? =
    runCatching {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val kind = input.readInt()
            require(kind == ActivityAutomationProjection.KIND_RECOGNITION ||
                kind == ActivityAutomationProjection.KIND_TRANSITION)
            repeat(3) { input.readInt() }
            val bootId = input.readUTF()
            input.readLong() // Observed elapsed time; retention starts at receipt, not observation.
            val received = input.readLong()
            input.readLong() // Registration generation.
            input.readLong() // Authorization revision.
            input.readUTF() // Authorization fingerprint.
            input.readLong() // Collected-data epoch.
            input.readLong() // Automation epoch.
            require(input.available() == 0)
            ControlRetentionStamp(bootId, received).takeIf { it.bootId.isNotBlank() }
        }
    }.getOrNull()

private val PENDING_ACTION_STATUSES = setOf(
    ActivityAutomaticStartActionEntity.STATUS_RESERVED,
    ActivityAutomaticStartActionEntity.STATUS_RETRYABLE,
    ActivityAutomaticStartActionEntity.STATUS_START_REQUESTED,
)

private const val CURSOR_PROJECTION_ID = "activity-control-expiry"
private const val CURSOR_VERSION = 1
