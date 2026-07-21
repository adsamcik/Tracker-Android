package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao

/**
 * A coherent read of raw location evidence and the source revision that produced it.
 *
 * The revision is deliberately independent of table row IDs: accepted/rejected decisions,
 * tracker-state events, retention, and full deletion all advance the same value. Consumers that
 * prepare an in-memory derivative should verify [isSourceEvidenceSnapshotCurrent] immediately
 * before publishing it and discard the result when the source changed meanwhile.
 */
data class SourceEvidenceSnapshot<T>(
	val revision: Long,
	val value: T,
)

/**
 * Executes [read] against one Room snapshot and returns the evidence revision observed with it.
 *
 * This is intentionally a read contract: [read] must not mutate source evidence. Source writers
 * update the singleton state in the same transaction as their mutation, so this lets a future
 * raster renderer avoid treating a mixed query as one coherent frame.
 */
suspend fun <T> AppDatabase.readSourceEvidenceSnapshot(
	read: suspend AppDatabase.() -> T,
): SourceEvidenceSnapshot<T> = withTransaction {
	val stateDao = sourceEvidenceStateDao()
	stateDao.ensure()
	val before = requireNotNull(stateDao.get()) {
		"Source-evidence state disappeared before snapshot read"
	}
	val value = read()
	val after = requireNotNull(stateDao.get()) {
		"Source-evidence state disappeared after snapshot read"
	}
	check(before.revision == after.revision) {
		"Source evidence changed during a purported read-only snapshot"
	}
	SourceEvidenceSnapshot(revision = before.revision, value = value)
}

/** True only when no source-evidence writer has committed since [snapshot] was read. */
suspend fun AppDatabase.isSourceEvidenceSnapshotCurrent(
	snapshot: SourceEvidenceSnapshot<*>,
): Boolean = sourceEvidenceStateDao().currentRevision() == snapshot.revision

private suspend fun SourceEvidenceStateDao.currentRevision(): Long? = get()?.revision
