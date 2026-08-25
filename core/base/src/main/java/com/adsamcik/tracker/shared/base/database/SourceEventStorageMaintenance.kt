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
 * Removes source-event rows only after every global projection, durable join, legacy recovery
 * consumer, and retaining product lane for that row's source has advanced past them. Product-lane
 * retention is source-local: a stalled Steps lane must not pin Location, Wi-Fi, or another source.
 * Deletes are intentionally bounded so weekly maintenance cannot hold the SQLite writer for an
 * unbounded transaction after a long offline period.
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
				val joinBoundary = projectionDao.minimumJoinRequiredOrdinal()
				val legacyDao = legacyV27ProjectionDrainDao()
				val globalRetentionBoundary = listOfNotNull(
					checkpoint,
					joinBoundary?.minus(1L),
					legacyDao.minimumPendingOrdinal()?.minus(1L),
					legacyDao.minimumPendingOutboxOrdinal()?.minus(1L),
					legacyDao.minimumBlockedWalOrdinal()?.minus(1L),
				).minOrNull()
				// No retaining consumer means every admitted ordinal is eligible for the ordinary
				// age fence. Use the durable high-water instead of returning early so optional,
				// non-retaining control projections cannot turn the raw WAL into permanent storage.
				val globalSafeOrdinal = globalRetentionBoundary
					?: (liveSourceProjectionActivationOrdinal() - 1L)
				if (globalSafeOrdinal <= 0L) {
					return@withTransaction SourceEventStoragePruneResult(0, 0)
				}
				val walDao = sourceEventWalDao()
				var remainingWalLimit = batchSize
				var remainingEffectLimit = batchSize
				var deletedWal = 0
				var deletedEffects = 0
				walDao.sourceKindsThrough(globalSafeOrdinal).forEach { sourceKind ->
					val productLaneCheckpoint =
						projectionDao.minimumRequiredProductLaneCheckpoint(sourceKind)
					val sourceSafeOrdinal = minOf(
						globalSafeOrdinal,
						productLaneCheckpoint ?: globalSafeOrdinal,
					)
					if (sourceSafeOrdinal <= 0L) return@forEach
					if (remainingEffectLimit > 0) {
						val sourceEffects = projectionDao.deleteDeliveredOutboxForSourceBatch(
							sourceKind = sourceKind,
							safeOrdinal = sourceSafeOrdinal,
							deliveredBeforeMs = createdBeforeMs,
							limit = remainingEffectLimit,
						)
						deletedEffects += sourceEffects
						remainingEffectLimit -= sourceEffects
					}
					if (remainingWalLimit > 0) {
						val sourceWal = walDao.deleteProjectedSourceBatch(
							sourceKind = sourceKind,
							safeOrdinal = sourceSafeOrdinal,
							createdBeforeMs = createdBeforeMs,
							limit = remainingWalLimit,
						)
						deletedWal += sourceWal
						remainingWalLimit -= sourceWal
					}
				}
				if (remainingEffectLimit > 0) {
					deletedEffects += projectionDao.deleteOrphanedDeliveredOutboxBatch(
						safeOrdinal = globalSafeOrdinal,
						deliveredBeforeMs = createdBeforeMs,
						limit = remainingEffectLimit,
					)
				}
				SourceEventStoragePruneResult(
					walEventsDeleted = deletedWal,
					deliveredEffectsDeleted = deletedEffects,
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
