package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity

internal fun LogicalTrackingSessionEntity.hasInterruptedWifiWalEnd(): Boolean {
	val completed = completedAtMs ?: return false
	val cutoff = cutoffAtMs ?: return false
	return state in WIFI_WAL_TERMINAL_SESSION_STATES && failureCode in WIFI_INTERRUPTED_END_REASONS &&
		cutoff >= startedAtMs && completed >= cutoff && cutoffElapsedNanos == null
}

internal fun SourceServiceRunEntity.hasInterruptedWifiWalEnd(): Boolean =
	state in WIFI_WAL_TERMINAL_SESSION_STATES && completionReason in WIFI_INTERRUPTED_END_REASONS && completedAtMs != null

/** Retained interrupted facts keep their original provider interval and the known wall-time stop. */
internal suspend fun AppDatabase.hasRetainedWifiInterruptedTail(
	wal: SourceEventWalEntity,
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	qualifiedEndNanos: Long,
): Boolean {
	if (!session.hasInterruptedWifiWalEnd()) {
		return true
	}
	if (!run.hasInterruptedWifiWalEnd() || qualifiedEndNanos !in 0L..wal.observedElapsedNanos) {
		return false
	}
	val registration = sourceBrokerDao().registration(WIFI_QUAL_SOURCE, wal.registrationGeneration) ?: return false
	return registration.containsInterruptedWifiObservation(wal, qualifiedEndNanos) &&
		wal.latestQualifiedWifiWallTime(qualifiedEndNanos)?.let { it <= requireNotNull(session.cutoffAtMs) } == true
}

private fun ProviderRegistrationGenerationEntity.containsInterruptedWifiObservation(
	wal: SourceEventWalEntity,
	qualifiedEndNanos: Long,
): Boolean = status == ProviderRegistrationGenerationEntity.STATUS_RETIRED &&
	sourceInstanceId == wal.sourceInstanceId && clockDomainId == wal.clockDomainId && retiredAtMs != null &&
	retiredElapsedRealtimeNanos?.let { qualifiedEndNanos < it } == true

private fun SourceEventWalEntity.latestQualifiedWifiWallTime(qualifiedEndNanos: Long): Long? = runCatching {
	Math.subtractExact(Math.addExact(requireNotNull(wallTimeMs), requireNotNull(wallTimeUncertaintyMs)),
		(observedElapsedNanos - qualifiedEndNanos) / NANOS_PER_MILLISECOND)
}.getOrNull()

private val WIFI_INTERRUPTED_END_REASONS = setOf("PREVIOUS_PROCESS_INTERRUPTED", "FORCE_STOP_SUPPRESSED")
private const val NANOS_PER_MILLISECOND = 1_000_000L
