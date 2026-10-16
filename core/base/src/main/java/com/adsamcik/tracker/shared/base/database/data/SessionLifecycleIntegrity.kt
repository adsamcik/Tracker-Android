package com.adsamcik.tracker.shared.base.database.data

import java.security.MessageDigest

/** The existing lifecycle byte contract, shared without changing command authority. */
object SessionLifecycleIntegrity {
    /**
     * Computes the deterministic stored digest from the supplied identity, revision and value
     * fields.
     */
    fun checksum(vararg parts: Any?): String {
        val canonical = parts.joinToString(separator = "\u001f") { part ->
            when (part) {
                is Iterable<*> -> part.joinToString(separator = "\u001e") { it.toString() }
                else -> part?.toString().orEmpty()
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** A retry has a FINALIZED predecessor and cannot supply the original cutoff clock. */
    // One immutable Stop/predecessor/session tuple; every identity and cutoff field stays explicit.
    @Suppress("ComplexCondition", "CyclomaticComplexMethod")
    fun authenticatesOriginalStop(
        stop: SessionLifecycleIntentVersionEntity,
        predecessor: SessionLifecycleIntentVersionEntity,
        session: LogicalTrackingSessionEntity,
    ): Boolean = stop.logicalTrackingId == session.logicalTrackingId &&
        stop.intentRevision > 1L && predecessor.logicalTrackingId == stop.logicalTrackingId &&
        predecessor.intentRevision == stop.intentRevision - 1L &&
        predecessor.manifestRevision == stop.manifestRevision && predecessor.desiredState == "ACTIVE" &&
        authenticatesActive(predecessor) && stop.desiredState == "FINALIZED" &&
        stop.startOrigin == "POLICY_RECONCILIATION" && stop.manifestRevision > 0L &&
        stop.requestBootId.isNotBlank() && !stop.stopReason.isNullOrBlank() &&
        stop.requestedElapsedRealtimeNanos == session.cutoffElapsedNanos &&
        stop.requestedWallTimeMs == session.cutoffAtMs && stop.requestedElapsedRealtimeNanos >= 0L &&
        stop.requestedWallTimeMs >= 0L && stop.stopDeadlineBootId == stop.requestBootId &&
        stop.stopDeadlineElapsedRealtimeNanos?.let { it >= stop.requestedElapsedRealtimeNanos } == true &&
        stop.triggerId == null && stop.triggerKind == null && stop.triggerBootId == null &&
        stop.triggerObservedElapsedRealtimeNanos == null && stop.triggerReceivedElapsedRealtimeNanos == null &&
        stop.triggerExpiresElapsedRealtimeNanos == null && stop.triggerCollectedDataEpoch == null &&
        stop.sourceCallerAuthorityReference == null && stop.automationEpoch == session.automationEpoch &&
        stop.intentChecksum == checksum(stop.logicalTrackingId, stop.intentRevision, stop.manifestRevision,
            stop.desiredState, stop.stopReason, stop.requestBootId, stop.requestedElapsedRealtimeNanos,
            stop.stopDeadlineElapsedRealtimeNanos)

    // The two persisted Active checksum shapes retain their exact field and null requirements.
    @Suppress("ComplexCondition", "CyclomaticComplexMethod")
    private fun authenticatesActive(intent: SessionLifecycleIntentVersionEntity): Boolean {
        if (intent.requestBootId.isBlank() || intent.intentRevision <= 0L || intent.manifestRevision <= 0L ||
            intent.requestedElapsedRealtimeNanos < 0L || intent.requestedWallTimeMs < 0L ||
            intent.stopDeadlineBootId != null || intent.stopDeadlineElapsedRealtimeNanos != null) {
            return false
        }
        return if (intent.stopReason == null) {
            intent.intentChecksum == checksum(intent.logicalTrackingId, intent.intentRevision,
                intent.manifestRevision, intent.desiredState, intent.startOrigin, intent.requestBootId,
                intent.requestedElapsedRealtimeNanos, intent.requestedWallTimeMs, intent.triggerId,
                intent.automationEpoch, intent.triggerCollectedDataEpoch, intent.sourceCallerAuthorityReference)
        } else {
            intent.startOrigin == "RECOVERY" && !intent.stopReason.isBlank() &&
                !intent.sourceCallerAuthorityReference.isNullOrBlank() &&
                intent.intentChecksum == checksum(intent.logicalTrackingId, intent.intentRevision,
                    intent.manifestRevision, intent.desiredState, intent.stopReason, intent.requestBootId,
                    intent.requestedElapsedRealtimeNanos, intent.sourceCallerAuthorityReference)
        }
    }
}
