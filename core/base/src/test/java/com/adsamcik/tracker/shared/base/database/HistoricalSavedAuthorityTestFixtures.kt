package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity

/** Explicit immutable lifecycle fixture; it makes no Android/coordinator acceptance claim. */
internal suspend fun AppDatabase.installOriginalStopFixture(
    session: LogicalTrackingSessionEntity,
    manifest: SessionManifestVersionEntity,
) {
    if (session.cutoffElapsedNanos == null || session.cutoffAtMs == null) {
        return
    }
    val dao = sourceSessionDao()
    if (dao.firstStopIntent(session.logicalTrackingId) != null) {
        return
    }
    val active = SessionLifecycleIntentVersionEntity(session.logicalTrackingId, manifest.manifestRevision,
        manifest.manifestRevision, "ACTIVE", manifest.startOrigin, manifest.effectiveBootId,
        manifest.effectiveElapsedRealtimeNanos, manifest.effectiveWallTimeMs, session.automationEpoch,
        null, null, null, null, null, null, null, null, null, "")
    val activeSealed = active.copy(intentChecksum = SessionLifecycleIntegrity.checksum(active.logicalTrackingId,
        active.intentRevision, active.manifestRevision, active.desiredState, active.startOrigin,
        active.requestBootId, active.requestedElapsedRealtimeNanos, active.requestedWallTimeMs,
        active.triggerId, active.automationEpoch, active.triggerCollectedDataEpoch, active.sourceCallerAuthorityReference))
    val stop = activeSealed.copy(intentRevision = active.intentRevision + 1L, desiredState = "FINALIZED",
        startOrigin = "POLICY_RECONCILIATION", requestedElapsedRealtimeNanos = session.cutoffElapsedNanos,
        requestedWallTimeMs = session.cutoffAtMs, stopReason = "USER_STOP", stopDeadlineBootId = active.requestBootId,
        stopDeadlineElapsedRealtimeNanos = session.cutoffElapsedNanos)
    val stopSealed = stop.copy(intentChecksum = SessionLifecycleIntegrity.checksum(stop.logicalTrackingId,
        stop.intentRevision, stop.manifestRevision, stop.desiredState, stop.stopReason, stop.requestBootId,
        stop.requestedElapsedRealtimeNanos, stop.stopDeadlineElapsedRealtimeNanos))
    dao.insertLifecycleIntent(activeSealed)
    dao.insertLifecycleIntent(stopSealed)
}
