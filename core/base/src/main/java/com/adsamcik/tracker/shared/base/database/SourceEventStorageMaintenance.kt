package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction

/** Counts from bounded source-event maintenance work. */
data class SourceEventStoragePruneResult(
	val walEventsDeleted: Int,
	val deliveredEffectsDeleted: Int,
)

/**
 * First global WAL ordinal a newly registered live projection may consume.
 *
 * A full deletion clears projection registrations and WAL rows but deliberately leaves SQLite's
 * AUTOINCREMENT sequence alone. The surviving evidence-state fence therefore participates in the
 * same activation calculation as the immutable v27 migration cutoff.
 */
suspend fun AppDatabase.liveSourceProjectionActivationOrdinal(): Long {
	val legacyActivationOrdinal = legacyV27ProjectionDrainDao().liveActivationOrdinal() ?: 1L
	val deletedHighWaterOrdinal = sourceEvidenceStateDao().get()
		?.deletedSourceEventHighWaterOrdinal ?: 0L
	val admittedHighWaterOrdinal = openHelper.writableDatabase.query(
		"SELECT MAX(" +
			"COALESCE((SELECT seq FROM sqlite_sequence WHERE name = 'source_event_wal'), 0), " +
			"COALESCE((SELECT MAX(admission_ordinal) FROM source_event_wal), 0))",
	).use { cursor ->
		check(cursor.moveToFirst()) { "Unable to read source-event WAL high-water" }
		cursor.getLong(0)
	}
	val durableHighWaterOrdinal = maxOf(deletedHighWaterOrdinal, admittedHighWaterOrdinal)
	check(durableHighWaterOrdinal < Long.MAX_VALUE) {
		"Source-event WAL exhausted its admission ordinal range"
	}
	return maxOf(legacyActivationOrdinal, durableHighWaterOrdinal + 1L)
}

/**
 * Removes source-event rows only after every active projection and durable join has advanced past
 * them. Deletes are intentionally bounded so weekly maintenance cannot hold the SQLite writer for
 * an unbounded transaction after a long offline period.
 */
suspend fun AppDatabase.pruneSourceEventStorageBefore(
	createdBeforeMs: Long,
	batchSize: Int = DEFAULT_SOURCE_EVENT_PRUNE_BATCH_SIZE,
	verifyCollectedDataAccess: () -> Unit = {},
): SourceEventStoragePruneResult {
	require(createdBeforeMs >= 0L)
	require(batchSize > 0)
	var walDeleted = 0
	var effectsDeleted = 0
	while (true) {
		val batch = withTransaction {
			verifyCollectedDataAccess()
			try {
				val projectionDao = sourceProjectionStateDao()
				val checkpoint = projectionDao.minimumRequiredCheckpoint()
				val productLaneCheckpoint = projectionDao.minimumRequiredProductLaneCheckpoint()
				val joinBoundary = projectionDao.minimumJoinRequiredOrdinal()
				val legacyDao = legacyV27ProjectionDrainDao()
				val safeOrdinal = listOfNotNull(
					checkpoint,
					productLaneCheckpoint,
					joinBoundary?.minus(1L),
					legacyDao.minimumPendingOrdinal()?.minus(1L),
					legacyDao.minimumPendingOutboxOrdinal()?.minus(1L),
					legacyDao.minimumBlockedWalOrdinal()?.minus(1L),
				).minOrNull() ?: return@withTransaction SourceEventStoragePruneResult(0, 0)
				if (safeOrdinal <= 0L) {
					return@withTransaction SourceEventStoragePruneResult(0, 0)
				}
				val walDao = sourceEventWalDao()
				val firstNonPrunable = walDao.firstNonPrunableOrdinal(safeOrdinal, createdBeforeMs)
				val pruneThroughOrdinal = firstNonPrunable?.minus(1L) ?: safeOrdinal
				if (pruneThroughOrdinal <= 0L) {
					return@withTransaction SourceEventStoragePruneResult(0, 0)
				}
				val deleted = walDao.deleteContiguousPrefixBatch(
					pruneThroughOrdinal = pruneThroughOrdinal,
					limit = batchSize,
				)
				SourceEventStoragePruneResult(
					walEventsDeleted = deleted,
					deliveredEffectsDeleted = projectionDao.deleteDeliveredOutboxBatch(
						safeOrdinal = safeOrdinal,
						deliveredBeforeMs = createdBeforeMs,
						limit = batchSize,
					),
				)
			} finally {
				// Throwing here aborts the Room transaction instead of committing work
				// that crossed a tracking-startup generation boundary.
				verifyCollectedDataAccess()
			}
		}
		walDeleted += batch.walEventsDeleted
		effectsDeleted += batch.deliveredEffectsDeleted
		if (batch.walEventsDeleted == 0 && batch.deliveredEffectsDeleted == 0) break
	}
	return SourceEventStoragePruneResult(walDeleted, effectsDeleted)
}

private const val DEFAULT_SOURCE_EVENT_PRUNE_BATCH_SIZE = 1_000
