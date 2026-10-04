package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.SourceEventRetentionProofCandidateRow
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionScopeProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
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
 * Every source is drained in independently committed, bounded transactions. Before one WAL batch
 * is removed, its exact payload-free retention proofs are durably inserted in the same transaction.
 * Typed source debt rolls back only the blocked source batch, while already completed sources
 * remain committed and later sources are still attempted.
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

/**
 * Retires Activity rows with CONTROL authorization. The elapsed clock is the
 * authority on the current boot; an older or unprovable boot is expired conservatively. The
 * source/global projection checkpoints and durable receipt are preserved exactly as in ordinary
 * WAL maintenance. A shared captured carrier is selected only after its product projection
 * checkpoint is safe, and receives an exact retained capture proof before its raw row is removed.
 */
suspend fun AppDatabase.pruneExpiredControlActivityWalBatch(
	currentBootId: String,
	nowElapsedNanos: Long,
	nowWallMs: Long,
	limit: Int = 128,
	verifyCollectedDataAccess: suspend () -> Unit = {},
): Int {
	require(currentBootId.isNotBlank())
	require(nowElapsedNanos >= 0L && nowWallMs >= 0L)
	require(limit in 1..256)
	val cutoffElapsedNanos = if (nowElapsedNanos >= ACTIVITY_CONTROL_RETENTION_NANOS) {
		nowElapsedNanos - ACTIVITY_CONTROL_RETENTION_NANOS
	} else {
		-1L
	}
	return withTransaction {
		verifyCollectedDataAccess()
		try {
			val globalSafeOrdinal = sourceEventStorageGlobalSafeOrdinal()
			val safeOrdinal = globalSafeOrdinal
			if (safeOrdinal <= 0L) return@withTransaction 0
			val rows = sourceEventWalDao().expiredControlActivityRows(
				safeOrdinal = safeOrdinal,
				currentBootId = currentBootId,
				nowElapsedNanos = nowElapsedNanos,
				cutoffElapsedNanos = cutoffElapsedNanos,
				limit = limit,
			)
			if (rows.isEmpty()) return@withTransaction 0
			check(rows.all { it.hasQualifiedIntegrity() }) {
				"Expired Activity CONTROL carrier cannot be authenticated"
			}
			val proofs = rows.map { row -> SourceEventRetentionProofEntity.createFromWal(row) }
			val proofDao = sourceEventRetentionProofDao()
			val insertions = proofDao.insertAll(proofs)
			check(insertions.size == proofs.size)
			proofs.forEachIndexed { index, proof ->
				if (insertions[index] == -1L) {
					val stored = proofDao.get(proof.sourceKind, proof.eventId)
					check(stored != null && stored == proof && stored.hasValidStoredIntegrity()) {
						"Expired Activity CONTROL receipt collision"
					}
				}
			}
			val ordinals = rows.map(SourceEventWalEntity::admissionOrdinal)
			val outboxDao = sourceProjectionStateDao()
			outboxDao.terminalizeActivityControlOutboxForOrdinals(
				ordinals, "ACTIVITY_CONTROL_RETENTION_EXPIRED", nowWallMs,
			)
			outboxDao.deleteActivityControlOutboxForOrdinals(ordinals)
			check(sourceEventWalDao().deleteControlActivityRows(ordinals) == rows.size)
			proofDao.deleteProofsWithLiveWal(SourceDestinationOwnerEntity.SOURCE_ACTIVITY)
			proofDao.trimSourceToNewest(
				SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				MAX_SOURCE_EVENT_RETENTION_PROOFS_PER_SOURCE,
			)
			rows.size
		} finally {
			verifyCollectedDataAccess()
		}
	}
}

internal suspend fun AppDatabase.pruneSourceEventStorageBeforeWithSourceOperations(
	createdBeforeMs: Long,
	batchSize: Int,
	verifyCollectedDataAccess: suspend () -> Unit,
	sourceKinds: List<Int> = SOURCE_EVENT_STORAGE_SOURCE_KINDS,
	maximumRetentionProofsPerSource: Int = MAX_SOURCE_EVENT_RETENTION_PROOFS_PER_SOURCE,
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
	require(maximumRetentionProofsPerSource > 0)
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
							is SourceEventStoragePruneBatchResult.Applied -> {
								sourceEventRetentionProofDao().deleteProofsWithLiveWal(sourceKind)
								sourceEventRetentionProofDao().trimSourceToNewest(
									sourceKind,
									maximumRetentionProofsPerSource,
								)
								result
							}
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
		projectionDao.minimumRequiredGlobalCheckpoint(
			AmbientWifiFactRevisionEntity.WRITER_ID,
			AmbientWifiFactRevisionEntity.WRITER_VERSION,
			AmbientCellFactRevisionEntity.WRITER_ID,
			AmbientCellFactRevisionEntity.WRITER_VERSION,
		),
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
		when (sourceKind) {
			SourceDestinationOwnerEntity.SOURCE_WIFI -> projectionDao.requiredProjectionCheckpoint(
				AmbientWifiFactRevisionEntity.WRITER_ID,
				AmbientWifiFactRevisionEntity.WRITER_VERSION,
			)
			SourceDestinationOwnerEntity.SOURCE_CELL -> projectionDao.requiredProjectionCheckpoint(
				AmbientCellFactRevisionEntity.WRITER_ID,
				AmbientCellFactRevisionEntity.WRITER_VERSION,
			)
			else -> null
		} ?: globalSafeOrdinal,
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
		var candidateCount = 0
		when (
			val result = StepsCountDomainStore(this).pruneSessionWalForStorage(
				safeOrdinal = sourceSafeOrdinal,
				createdBeforeMs = createdBeforeMs,
				limit = batchSize,
				beforeWalDeletion = { totalCandidateCount, currentCandidates ->
					candidateCount = totalCandidateCount
					requireRetentionProofs(sourceKind, currentCandidates)
				},
			)
		) {
			is StepsCountDomainMaintenanceResult.Applied -> {
				if (result.walEventsDeleted != candidateCount) {
					return SourceEventStoragePruneBatchResult.Blocked(
						SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
					)
				}
				result.walEventsDeleted
			}
			StepsCountDomainMaintenanceResult.SchemaUnavailable -> {
				val candidates = walDao.retentionProofCandidatesForProjectedSourceBatch(
					sourceKind = sourceKind,
					safeOrdinal = sourceSafeOrdinal,
					createdBeforeMs = createdBeforeMs,
					limit = batchSize,
				)
				requireRetentionProofs(sourceKind, candidates)
				val deleted = walDao.deleteProjectedSourceBatch(
					sourceKind = sourceKind,
					safeOrdinal = sourceSafeOrdinal,
					createdBeforeMs = createdBeforeMs,
					limit = batchSize,
				)
				if (deleted != candidates.size) {
					return SourceEventStoragePruneBatchResult.Blocked(
						SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
					)
				}
				deleted
			}
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
		val candidates = walDao.retentionProofCandidatesForProjectedSourceBatch(
			sourceKind = sourceKind,
			safeOrdinal = sourceSafeOrdinal,
			createdBeforeMs = createdBeforeMs,
			limit = batchSize,
		)
		requireRetentionProofs(sourceKind, candidates)
		val deleted = walDao.deleteProjectedSourceBatch(
			sourceKind = sourceKind,
			safeOrdinal = sourceSafeOrdinal,
			createdBeforeMs = createdBeforeMs,
			limit = batchSize,
		)
		if (deleted != candidates.size) {
			return SourceEventStoragePruneBatchResult.Blocked(
				SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		deleted
	}
	return SourceEventStoragePruneBatchResult.Applied(
		walEventsDeleted = sourceWal,
		deliveredEffectsDeleted = sourceEffects,
	)
}

private suspend fun AppDatabase.requireRetentionProofs(
	expectedSourceKind: Int,
	candidates: List<SourceEventRetentionProofCandidateRow>,
) {
	if (candidates.isEmpty()) return
	if (candidates.any { it.sourceKind != expectedSourceKind }) {
		throw SourceEventStoragePruneBlockedException(
			SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
	}
	val receipts = try {
		candidates.map { candidate ->
			val wal = sourceEventWalDao().getByEventId(candidate.eventId)
			requireNotNull(wal)
			candidate.createAuthenticatedRetentionProof(wal)
		}
	} catch (_: IllegalArgumentException) {
		throw SourceEventStoragePruneBlockedException(
			SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
	}
	val insertions = sourceEventRetentionProofDao().insertAll(receipts)
	if (insertions.size != receipts.size) {
		throw SourceEventStoragePruneBlockedException(
			SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
	}
	insertions.forEachIndexed { index, insertion ->
		if (insertion == -1L) {
			val expected = receipts[index]
			val stored = sourceEventRetentionProofDao().get(expected.sourceKind, expected.eventId)
			if (stored != expected || !stored.hasValidStoredIntegrity()) {
				throw SourceEventStoragePruneBlockedException(
					SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
		}
	}
	try {
		receipts.forEach { receipt ->
			if (receipt.sourceKind in RETAINED_CAPTURE_SCOPE_SOURCE_KINDS &&
				receipt.purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
			) {
				persistSourceEventRetentionScopeProof(receipt)
			}
		}
	} catch (_: IllegalArgumentException) {
		throw SourceEventStoragePruneBlockedException(
			SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
	} catch (_: IllegalStateException) {
		throw SourceEventStoragePruneBlockedException(
			SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
	}
}

private suspend fun AppDatabase.persistSourceEventRetentionScopeProof(
	eventProof: SourceEventRetentionProofEntity,
) {
	val dao = sourceEventRetentionScopeProofDao()
	val candidate = SourceEventRetentionScopeProofEntity.create(eventProof)
	val stored = dao.get(candidate.sourceKind, candidate.logicalRunScopeDigest)
	if (stored == null) {
		if (dao.insertIfAbsent(candidate) == -1L) {
			val raced = dao.get(candidate.sourceKind, candidate.logicalRunScopeDigest)
			require(raced != null && raced.hasValidStoredIntegrity())
			val advanced = raced.advance(eventProof)
			if (advanced != raced) require(dao.update(advanced) == 1)
		}
	} else {
		require(stored.hasValidStoredIntegrity())
		val advanced = stored.advance(eventProof)
		if (advanced != stored) require(dao.update(advanced) == 1)
	}
	val persisted = requireNotNull(
		dao.get(candidate.sourceKind, candidate.logicalRunScopeDigest),
	)
	require(persisted.hasValidStoredIntegrity())
	require(persisted.advance(eventProof) == persisted)
}

internal fun SourceEventRetentionProofCandidateRow.authenticatesRetentionWal(
	wal: SourceEventWalEntity,
): Boolean = sourceKind == wal.sourceKind &&
	eventId == wal.eventId &&
	sourceAdmissionOrdinal == wal.admissionOrdinal &&
	purposeEligibilityMask == wal.authorizationPurposeEligibilityMask &&
	sourcePolicyRevision == wal.sourcePolicyRevision &&
	captureConsentEpoch == wal.captureConsentEpoch &&
	collectedDataEpoch == wal.capturedCollectedDataEpoch &&
	logicalTrackingId == wal.logicalTrackingId &&
	serviceRunId == wal.serviceRunId &&
	clockDomainId == wal.clockDomainId &&
	sessionManifestRevision == wal.sessionManifestRevision &&
	lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration &&
	sourceInstanceId == wal.sourceInstanceId &&
	registrationGeneration == wal.registrationGeneration &&
	physicalConfigurationFingerprint == wal.physicalConfigurationFingerprint &&
	authorizationRevision == wal.authorizationRevision &&
	authorizationFingerprint == wal.authorizationFingerprint &&
	configurationRevision == wal.configRevision &&
	walPayloadChecksum == wal.payloadChecksum &&
	walIntegrityIdentity == wal.integrityIdentity &&
	wal.hasQualifiedIntegrity()

internal fun SourceEventRetentionProofCandidateRow.createAuthenticatedRetentionProof(
	wal: SourceEventWalEntity,
): SourceEventRetentionProofEntity {
	require(authenticatesRetentionWal(wal))
	return SourceEventRetentionProofEntity.createFromWal(wal)
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
private val RETAINED_CAPTURE_SCOPE_SOURCE_KINDS = setOf(
	SourceDestinationOwnerEntity.SOURCE_WIFI,
	SourceDestinationOwnerEntity.SOURCE_CELL,
)

private const val DEFAULT_SOURCE_EVENT_PRUNE_BATCH_SIZE = 1_000
const val ACTIVITY_CONTROL_RETENTION_NANOS = 48L * 60L * 60L * 1_000_000_000L
internal const val MAX_SOURCE_EVENT_RETENTION_PROOFS_PER_SOURCE = 4_096
