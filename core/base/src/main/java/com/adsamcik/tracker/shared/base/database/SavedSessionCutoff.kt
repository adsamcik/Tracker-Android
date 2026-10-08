package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntegrity

/** Read-only original cutoff; a missing clock never borrows a later operation's boot. */
data class SavedSessionCutoff(val bootId: String?, val elapsedNanos: Long?) {
    val clockUnknown: Boolean get() = elapsedNanos != null && bootId == null
    fun retainedEnd(measurementBootId: String, originalFactEnd: Long): Long =
        if (elapsedNanos != null && bootId != measurementBootId) originalFactEnd else endForBoot(measurementBootId)
    fun endForBoot(measurementBootId: String): Long =
        if (bootId == measurementBootId) elapsedNanos ?: Long.MAX_VALUE else Long.MAX_VALUE
}

/** Called inside the reader/maintenance's existing Room snapshot; at most three indexed reads. */
suspend fun AppDatabase.savedSessionCutoff(session: LogicalTrackingSessionEntity): SavedSessionCutoff {
    if (session.cutoffElapsedNanos == null) return SavedSessionCutoff(null, null)
    val dao = sourceSessionDao()
    val stop = dao.firstStopIntent(session.logicalTrackingId)
        ?: return SavedSessionCutoff(null, session.cutoffElapsedNanos)
    val predecessor = dao.lifecycleIntent(session.logicalTrackingId, stop.intentRevision - 1L)
        ?: return SavedSessionCutoff(null, session.cutoffElapsedNanos)
    val manifest = dao.manifest(session.logicalTrackingId, stop.manifestRevision)
    if (manifest == null || manifest.serviceRunId.isBlank() ||
        !SessionLifecycleIntegrity.authenticatesOriginalStop(stop, predecessor, session)) {
        return SavedSessionCutoff(null, session.cutoffElapsedNanos)
    }
    return SavedSessionCutoff(stop.requestBootId, session.cutoffElapsedNanos)
}
