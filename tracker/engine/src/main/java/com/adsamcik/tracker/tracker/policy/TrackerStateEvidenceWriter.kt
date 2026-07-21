package com.adsamcik.tracker.tracker.policy

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.TrackerStateEvent

/**
 * Persists canonical tracker-state evidence independently of the mutable legacy `tracker_run`.
 *
 * The revision bump and append happen in the same transaction. This is intentionally a small
 * abstraction because [TrackingPolicyManager] is manually constructed in many unit tests; the
 * production orchestrator supplies the Room-backed implementation while legacy test fixtures can
 * remain focused on tracker-run compatibility.
 */
interface TrackerStateEvidenceWriter {
	suspend fun append(
		clockDomainId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		state: String,
		policy: String,
		reason: String? = null,
		activeLeaseExpiresElapsedNanos: Long? = null,
	)
}

class RoomTrackerStateEvidenceWriter(
	private val database: AppDatabase,
) : TrackerStateEvidenceWriter {
	override suspend fun append(
		clockDomainId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		state: String,
		policy: String,
		reason: String?,
		activeLeaseExpiresElapsedNanos: Long?,
	) {
		database.withTransaction {
			val revisionDao = database.sourceEvidenceStateDao()
			revisionDao.ensure(SourceEvidenceState())
			check(revisionDao.incrementRevision(wallTimeMs) == 1) {
				"Unable to advance source-evidence revision for tracker state"
			}
			val revision = requireNotNull(revisionDao.get()).revision
			database.trackerStateEventDao().insert(
				TrackerStateEvent(
					clockDomainId = clockDomainId,
					elapsedRealtimeNanos = elapsedRealtimeNanos,
					wallTimeMs = wallTimeMs,
					state = state,
					policy = policy,
					reason = reason,
					activeLeaseExpiresElapsedNanos = activeLeaseExpiresElapsedNanos,
					createdAtMs = wallTimeMs,
					sourceRevision = revision,
				),
			)
		}
	}
}
