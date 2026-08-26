package com.adsamcik.tracker.tracker.resilience

import com.adsamcik.tracker.shared.base.database.dao.SourceSessionDao
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity

/**
 * Captures the durable service-run authority visible to a finalizer in its Room transaction.
 *
 * Callers deliberately choose whether multiple incomplete runs are corruption that must fail closed
 * or stale authority that must be terminalized. The session pointer alone is not proof that it is
 * safe to leave any other incomplete run behind.
 */
internal data class SourceSessionRunFinalizationAuthority(
	val currentRun: SourceServiceRunEntity?,
	val incompleteRuns: List<SourceServiceRunEntity>,
) {
	val exactSoleCurrentRun: SourceServiceRunEntity?
		get() = currentRun?.takeIf { current ->
			incompleteRuns.size == 1 && incompleteRuns.single().serviceRunId == current.serviceRunId
		}

	val orphanRuns: List<SourceServiceRunEntity>
		get() = incompleteRuns.filter { run -> run.serviceRunId != currentRun?.serviceRunId }
}

internal suspend fun SourceSessionDao.serviceRunAuthorityForFinalization(
	session: LogicalTrackingSessionEntity,
): SourceSessionRunFinalizationAuthority {
	val incomplete = incompleteServiceRuns(session.logicalTrackingId)
	val run = session.currentServiceRunId?.let { serviceRunId ->
		requireNotNull(serviceRun(serviceRunId)) { "Current service run is missing" }
	}
	if (run != null) {
		check(run.logicalTrackingId == session.logicalTrackingId) {
			"Current service run belongs to another logical session"
		}
	}
	check(incomplete.all { it.logicalTrackingId == session.logicalTrackingId }) {
		"Incomplete service run belongs to another logical session"
	}
	return SourceSessionRunFinalizationAuthority(
		currentRun = run,
		incompleteRuns = incomplete,
	)
}
