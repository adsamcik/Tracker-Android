package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction

/** Counts from bounded source-event maintenance work. */
data class SourceEventStoragePruneResult(
	val walEventsDeleted: Int,
	val deliveredEffectsDeleted: Int,
)

/**
 * Removes source-event rows only after every active projection and durable join has advanced past
 * them. Deletes are intentionally bounded so weekly maintenance cannot hold the SQLite writer for
 * an unbounded transaction after a long offline period.
 */
suspend fun AppDatabase.pruneSourceEventStorageBefore(
	createdBeforeMs: Long,
	batchSize: Int = DEFAULT_SOURCE_EVENT_PRUNE_BATCH_SIZE,
): SourceEventStoragePruneResult {
	require(createdBeforeMs >= 0L)
	require(batchSize > 0)
	var walDeleted = 0
	var effectsDeleted = 0
	while (true) {
		val batch = withTransaction {
			val projectionDao = sourceProjectionStateDao()
			val checkpoint = projectionDao.minimumRequiredCheckpoint()
				?: return@withTransaction SourceEventStoragePruneResult(0, 0)
			val joinBoundary = projectionDao.minimumJoinRequiredOrdinal()
			val safeOrdinal = joinBoundary?.let { minOf(checkpoint, it - 1L) } ?: checkpoint
			if (safeOrdinal <= 0L) {
				return@withTransaction SourceEventStoragePruneResult(0, 0)
			}
			SourceEventStoragePruneResult(
				walEventsDeleted = sourceEventWalDao().deleteProjectedBatch(
					safeOrdinal = safeOrdinal,
					createdBeforeMs = createdBeforeMs,
					limit = batchSize,
				),
				deliveredEffectsDeleted = projectionDao.deleteDeliveredOutboxBatch(
					safeOrdinal = safeOrdinal,
					deliveredBeforeMs = createdBeforeMs,
					limit = batchSize,
				),
			)
		}
		walDeleted += batch.walEventsDeleted
		effectsDeleted += batch.deliveredEffectsDeleted
		if (batch.walEventsDeleted == 0 && batch.deliveredEffectsDeleted == 0) break
	}
	return SourceEventStoragePruneResult(walDeleted, effectsDeleted)
}

private const val DEFAULT_SOURCE_EVENT_PRUNE_BATCH_SIZE = 1_000
