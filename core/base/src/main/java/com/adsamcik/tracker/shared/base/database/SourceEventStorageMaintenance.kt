package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class SourceEventStoragePruneBlockedReason {
	STORED_EVIDENCE_UNVERIFIABLE,
	MAINTENANCE_OVERFLOW,
}

sealed interface SourceEventStorageSourcePruneOutcome {
	val sourceKind: Int
	val walEventsDeleted: Int
	val deliveredEffectsDeleted: Int

	data class Applied(
		override val sourceKind: Int,
		override val walEventsDeleted: Int,
		override val deliveredEffectsDeleted: Int,
	) : SourceEventStorageSourcePruneOutcome

	data class NoChange(
		override val sourceKind: Int,
	) : SourceEventStorageSourcePruneOutcome {
		override val walEventsDeleted: Int = 0
		override val deliveredEffectsDeleted: Int = 0
	}

	data class Blocked(
		override val sourceKind: Int,
		val reason: SourceEventStoragePruneBlockedReason,
		override val walEventsDeleted: Int,
		override val deliveredEffectsDeleted: Int,
	) : SourceEventStorageSourcePruneOutcome
}

sealed interface SourceEventStoragePruneResult {
	val walEventsDeleted: Int
	val deliveredEffectsDeleted: Int
	val orphanedDeliveredEffectsDeleted: Int
	val sourceOutcomes: List<SourceEventStorageSourcePruneOutcome>

	data class Complete(
		override val walEventsDeleted: Int,
		override val deliveredEffectsDeleted: Int,
		override val orphanedDeliveredEffectsDeleted: Int = 0,
		override val sourceOutcomes: List<SourceEventStorageSourcePruneOutcome> = emptyList(),
	) : SourceEventStoragePruneResult

	data class Deferred(
		override val walEventsDeleted: Int,
		override val deliveredEffectsDeleted: Int,
		override val orphanedDeliveredEffectsDeleted: Int,
		override val sourceOutcomes: List<SourceEventStorageSourcePruneOutcome>,
		val sourceDebt: List<SourceEventStorageSourcePruneOutcome.Blocked>,
	) : SourceEventStoragePruneResult {
		init {
			require(sourceDebt.isNotEmpty())
			require(sourceDebt == sourceOutcomes.filterIsInstance<SourceEventStorageSourcePruneOutcome.Blocked>())
		}
	}
}

internal sealed interface SourceEventStoragePruneBatchResult {
	data class Applied(
		val walEventsDeleted: Int,
		val deliveredEffectsDeleted: Int,
	) : SourceEventStoragePruneBatchResult

	data class Blocked(
		val reason: SourceEventStoragePruneBlockedReason,
	) : SourceEventStoragePruneBatchResult
}

internal typealias SourceEventStorageBatchPruner = suspend AppDatabase.(
	sourceKind: Int,
	globalSafeOrdinal: Long,
	createdBeforeMs: Long,
	batchSize: Int,
) -> SourceEventStoragePruneBatchResult

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
 * consumer, and retaining product lane for that row's source has advanced past them.
 *
 * Every source is drained in independently committed, bounded transactions. Typed source debt
 * rolls back only the blocked source batch, while already completed sources remain committed and
 * later sources are still attempted.
 */
suspend fun AppDatabase.pruneSourceEventStorageBefore(
	createdBeforeMs: Long,
	batchSize: Int = DEFAULT_SOURCE_EVENT_PRUNE_BATCH_SIZE,
	verifyCollectedDataAccess: suspend () -> Unit = {},
): SourceEventStoragePruneResult = pruneSourceEventStorageBeforeWithSourceOperations(
	createdBeforeMs = createdBeforeMs,
	batchSize = batchSize,
	verifyCollectedDataAccess = verifyCollectedDataAccess,
)

internal suspend fun AppDatabase.pruneSourceEventStorageBeforeWithSourceOperations(
	createdBeforeMs: Long,
	batchSize: Int,
	verifyCollectedDataAccess: suspend () -> Unit,
	sourceKinds: List<Int> = SOURCE_EVENT_STORAGE_SOURCE_KINDS,
	pruneSourceBatch: SourceEventStorageBatchPruner = {
			sourceKind, globalSafeOrdinal, sourceCreatedBeforeMs, sourceBatchSize ->
		pruneSourceEventStorageBatch(
			sourceKind = sourceKind,
			globalSafeOrdinal = globalSafeOrdinal,
			createdBeforeMs = sourceCreatedBeforeMs,
			batchSize = sourceBatchSize,
		)
	},
	afterSource: suspend (SourceEventStorageSourcePruneOutcome) -> Unit = {},
): SourceEventStoragePruneResult {
	require(createdBeforeMs >= 0L)
	require(batchSize > 0)
	require(sourceKinds.distinct().size == sourceKinds.size)
	require(sourceKinds.all(SOURCE_EVENT_STORAGE_SOURCE_KINDS::contains))

	val outcomes = mutableListOf<SourceEventStorageSourcePruneOutcome>()
	for (sourceKind in sourceKinds) {
		currentCoroutineContext().ensureActive()
		var sourceWalDeleted = 0
		var sourceEffectsDeleted = 0
		var blockedReason: SourceEventStoragePruneBlockedReason? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val batch = try {
				withTransaction {
					verifyCollectedDataAccess()
					try {
						val result = pruneSourceBatch(
							sourceKind,
							sourceEventStorageGlobalSafeOrdinal(),
							createdBeforeMs,
							batchSize,
						)
						when (result) {
							is SourceEventStoragePruneBatchResult.Applied -> result
							is SourceEventStoragePruneBatchResult.Blocked ->
								throw SourceEventStoragePruneBlockedException(result.reason)
						}
					} finally {
						verifyCollectedDataAccess()
					}
				}
			} catch (blocked: SourceEventStoragePruneBlockedException) {
				blockedReason = blocked.reason
				break
			}
			sourceWalDeleted = Math.addExact(sourceWalDeleted, batch.walEventsDeleted)
			sourceEffectsDeleted =
				Math.addExact(sourceEffectsDeleted, batch.deliveredEffectsDeleted)
			if (batch.walEventsDeleted == 0 && batch.deliveredEffectsDeleted == 0) break
		}
		val outcome = blockedReason?.let { reason ->
			SourceEventStorageSourcePruneOutcome.Blocked(
				sourceKind = sourceKind,
				reason = reason,
				walEventsDeleted = sourceWalDeleted,
				deliveredEffectsDeleted = sourceEffectsDeleted,
			)
		} ?: if (sourceWalDeleted == 0 && sourceEffectsDeleted == 0) {
			SourceEventStorageSourcePruneOutcome.NoChange(sourceKind)
		} else {
			SourceEventStorageSourcePruneOutcome.Applied(
				sourceKind = sourceKind,
				walEventsDeleted = sourceWalDeleted,
				deliveredEffectsDeleted = sourceEffectsDeleted,
			)
		}
		outcomes += outcome
		afterSource(outcome)
	}

	var orphanedEffectsDeleted = 0
	while (true) {
		currentCoroutineContext().ensureActive()
		val deleted = withTransaction {
			verifyCollectedDataAccess()
			try {
				sourceProjectionStateDao().deleteOrphanedDeliveredOutboxBatch(
					safeOrdinal = sourceEventStorageGlobalSafeOrdinal(),
					deliveredBeforeMs = createdBeforeMs,
					limit = batchSize,
				)
			} finally {
				verifyCollectedDataAccess()
			}
		}
		orphanedEffectsDeleted = Math.addExact(orphanedEffectsDeleted, deleted)
		if (deleted == 0) break
	}

	val sourceOutcomes = outcomes.toList()
	val walDeleted = sourceOutcomes.fold(0) { total, outcome ->
		Math.addExact(total, outcome.walEventsDeleted)
	}
	val sourceEffectsDeleted = sourceOutcomes.fold(0) { total, outcome ->
		Math.addExact(total, outcome.deliveredEffectsDeleted)
	}
	val effectsDeleted = Math.addExact(sourceEffectsDeleted, orphanedEffectsDeleted)
	val sourceDebt =
		sourceOutcomes.filterIsInstance<SourceEventStorageSourcePruneOutcome.Blocked>()
	return if (sourceDebt.isEmpty()) {
		SourceEventStoragePruneResult.Complete(
			walEventsDeleted = walDeleted,
			deliveredEffectsDeleted = effectsDeleted,
			orphanedDeliveredEffectsDeleted = orphanedEffectsDeleted,
			sourceOutcomes = sourceOutcomes,
		)
	} else {
		SourceEventStoragePruneResult.Deferred(
			walEventsDeleted = walDeleted,
			deliveredEffectsDeleted = effectsDeleted,
			orphanedDeliveredEffectsDeleted = orphanedEffectsDeleted,
			sourceOutcomes = sourceOutcomes,
			sourceDebt = sourceDebt,
		)
	}
}

private suspend fun AppDatabase.sourceEventStorageGlobalSafeOrdinal(): Long {
	val projectionDao = sourceProjectionStateDao()
	val globalRetentionBoundary = listOfNotNull(
		projectionDao.minimumRequiredCheckpoint(),
		projectionDao.minimumJoinRequiredOrdinal()?.minus(1L),
		legacyV27ProjectionDrainDao().minimumPendingOrdinal()?.minus(1L),
		legacyV27ProjectionDrainDao().minimumPendingOutboxOrdinal()?.minus(1L),
		legacyV27ProjectionDrainDao().minimumBlockedWalOrdinal()?.minus(1L),
	).minOrNull()
	// No retaining consumer means every admitted ordinal is eligible for the ordinary age fence.
	// The durable high-water prevents optional control projections from retaining raw WAL forever.
	return globalRetentionBoundary ?: (liveSourceProjectionActivationOrdinal() - 1L)
}

private suspend fun AppDatabase.pruneSourceEventStorageBatch(
	sourceKind: Int,
	globalSafeOrdinal: Long,
	createdBeforeMs: Long,
	batchSize: Int,
): SourceEventStoragePruneBatchResult {
	if (globalSafeOrdinal <= 0L) return SourceEventStoragePruneBatchResult.Applied(0, 0)
	val projectionDao = sourceProjectionStateDao()
	val sourceSafeOrdinal = minOf(
		globalSafeOrdinal,
		projectionDao.minimumRequiredProductLaneCheckpoint(sourceKind) ?: globalSafeOrdinal,
	)
	if (sourceSafeOrdinal <= 0L) return SourceEventStoragePruneBatchResult.Applied(0, 0)
	val sourceEffects = projectionDao.deleteDeliveredOutboxForSourceBatch(
		sourceKind = sourceKind,
		safeOrdinal = sourceSafeOrdinal,
		deliveredBeforeMs = createdBeforeMs,
		limit = batchSize,
	)
	val walDao = sourceEventWalDao()
	val sourceWal = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS) {
		when (
			val result = StepsCountDomainStore(this).pruneSessionWalForStorage(
				safeOrdinal = sourceSafeOrdinal,
				createdBeforeMs = createdBeforeMs,
				limit = batchSize,
			)
		) {
			is StepsCountDomainMaintenanceResult.Applied -> result.walEventsDeleted
			StepsCountDomainMaintenanceResult.SchemaUnavailable ->
				walDao.deleteProjectedSourceBatch(
					sourceKind = sourceKind,
					safeOrdinal = sourceSafeOrdinal,
					createdBeforeMs = createdBeforeMs,
					limit = batchSize,
				)
			StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable ->
				return SourceEventStoragePruneBatchResult.Blocked(
					SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			StepsCountDomainMaintenanceResult.Overflow ->
				return SourceEventStoragePruneBatchResult.Blocked(
					SourceEventStoragePruneBlockedReason.MAINTENANCE_OVERFLOW,
				)
		}
	} else {
		walDao.deleteProjectedSourceBatch(
			sourceKind = sourceKind,
			safeOrdinal = sourceSafeOrdinal,
			createdBeforeMs = createdBeforeMs,
			limit = batchSize,
		)
	}
	return SourceEventStoragePruneBatchResult.Applied(
		walEventsDeleted = sourceWal,
		deliveredEffectsDeleted = sourceEffects,
	)
}

private class SourceEventStoragePruneBlockedException(
	val reason: SourceEventStoragePruneBlockedReason,
) : RuntimeException()

private val SOURCE_EVENT_STORAGE_SOURCE_KINDS = listOf(
	SourceDestinationOwnerEntity.SOURCE_LOCATION,
	SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
	SourceDestinationOwnerEntity.SOURCE_STEPS,
	SourceDestinationOwnerEntity.SOURCE_PRESSURE,
	SourceDestinationOwnerEntity.SOURCE_WIFI,
	SourceDestinationOwnerEntity.SOURCE_CELL,
)

private const val DEFAULT_SOURCE_EVENT_PRUNE_BATCH_SIZE = 1_000
