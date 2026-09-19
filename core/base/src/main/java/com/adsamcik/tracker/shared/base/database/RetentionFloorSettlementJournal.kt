package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.hasReachedRetentionPhase

data class RetentionFloorSettlementOperation(
	val operationId: String,
	val requestedRetainedFromMs: Long,
	val collectedDataEpoch: Long,
	val requestedAtMs: Long,
	val phase: String,
) {
	init {
		require(operationId.isNotBlank())
		require(requestedRetainedFromMs >= 0L)
		require(collectedDataEpoch >= 0L)
		require(requestedAtMs >= 0L)
		require(phase in CollectedDataDeletionOperationEntity.RETENTION_PHASES)
	}

	fun hasReached(expectedPhase: String): Boolean = entity().hasReachedRetentionPhase(expectedPhase)

	private fun entity() = CollectedDataDeletionOperationEntity(
		operationId = operationId,
		targetCollectedDataEpoch = collectedDataEpoch,
		retainedFromMs = requestedRetainedFromMs,
		deletedAtMs = requestedAtMs,
		phase = phase,
		updatedAtMs = requestedAtMs,
	)
}

suspend fun AppDatabase.activeRetentionFloorSettlement():
	RetentionFloorSettlementOperation? =
	collectedDataDeletionOperationDao().activeRetentionFloorSettlement()?.toRetentionOperation()

suspend fun AppDatabase.prepareOrResumeRetentionFloorSettlement(
	operationId: String,
	requestedRetainedFromMs: Long,
	collectedDataEpoch: Long,
	requestedAtMs: Long,
): RetentionFloorSettlementOperation = withTransaction {
	require(operationId.isNotBlank())
	require(requestedRetainedFromMs >= 0L)
	require(collectedDataEpoch >= 0L)
	require(requestedAtMs >= 0L)
	val dao = collectedDataDeletionOperationDao()
	dao.activeRetentionFloorSettlement()?.let { return@withTransaction it.toRetentionOperation() }
	dao.get(operationId)?.let { existing ->
		check(existing.retainedFromMs == requestedRetainedFromMs) {
			"Retention-floor operation identity was reused with another boundary"
		}
		check(existing.targetCollectedDataEpoch == collectedDataEpoch) {
			"Retention-floor operation identity was reused in another lifecycle epoch"
		}
		check(existing.phase in CollectedDataDeletionOperationEntity.RETENTION_PHASES) {
			"Retention-floor operation identity collides with another lifecycle operation"
		}
		return@withTransaction existing.toRetentionOperation()
	}
	dao.deleteFinalizedRetentionFloorSettlements()
	val prepared = CollectedDataDeletionOperationEntity(
		operationId = operationId,
		targetCollectedDataEpoch = collectedDataEpoch,
		retainedFromMs = requestedRetainedFromMs,
		deletedAtMs = requestedAtMs,
		phase = CollectedDataDeletionOperationEntity.PHASE_RETENTION_PREPARED,
		updatedAtMs = requestedAtMs,
	)
	dao.insert(prepared)
	prepared.toRetentionOperation()
}

suspend fun AppDatabase.advanceRetentionFloorSettlementPhase(
	operation: RetentionFloorSettlementOperation,
	expectedPhase: String,
	newPhase: String,
	updatedAtMs: Long,
): RetentionFloorSettlementOperation = withTransaction {
	require(updatedAtMs >= operation.requestedAtMs)
	val dao = collectedDataDeletionOperationDao()
	val current = requireNotNull(dao.get(operation.operationId)) {
		"Retention-floor settlement journal disappeared"
	}
	current.requireExact(operation)
	if (current.hasReachedRetentionPhase(newPhase)) {
		return@withTransaction current.toRetentionOperation()
	}
	check(current.phase == expectedPhase) {
		"Retention-floor settlement phase changed unexpectedly"
	}
	check(
		dao.compareAndSetRetentionPhase(
			operationId = operation.operationId,
			targetCollectedDataEpoch = operation.collectedDataEpoch,
			requestedRetainedFromMs = operation.requestedRetainedFromMs,
			expectedPhase = expectedPhase,
			newPhase = newPhase,
			updatedAtMs = updatedAtMs,
		) == 1,
	) { "Unable to advance retention-floor settlement journal" }
	requireNotNull(dao.get(operation.operationId)).toRetentionOperation()
}

suspend fun AppDatabase.commitRetentionFloorRoomGuard(
	operation: RetentionFloorSettlementOperation,
	settledRetainedFromMs: Long,
	updatedAtMs: Long,
): RetentionFloorSettlementOperation = withTransaction {
	require(settledRetainedFromMs >= operation.requestedRetainedFromMs)
	require(updatedAtMs >= operation.requestedAtMs)
	val operationDao = collectedDataDeletionOperationDao()
	val current = requireNotNull(operationDao.get(operation.operationId)) {
		"Retention-floor settlement journal disappeared"
	}
	current.requireExact(operation)
	if (
		current.hasReachedRetentionPhase(
			CollectedDataDeletionOperationEntity.PHASE_RETENTION_ROOM_GUARD_COMMITTED,
		)
	) {
		val evidence = requireNotNull(sourceEvidenceStateDao().get()) {
			"Settled retention floor lost source-evidence authority"
		}
		check(evidence.collectedDataEpoch == operation.collectedDataEpoch)
		check(evidence.retainedFromMs == settledRetainedFromMs)
		return@withTransaction current.toRetentionOperation()
	}
	check(
		current.phase ==
			CollectedDataDeletionOperationEntity.PHASE_RETENTION_DATASTORE_ACKNOWLEDGED,
	) { "Retention-floor Room guard was attempted before DataStore acknowledgement" }
	val evidenceDao = sourceEvidenceStateDao()
	val lifecycleChanged = evidenceDao.synchronizeLifecycle(
		epoch = operation.collectedDataEpoch,
		retainedFromMs = settledRetainedFromMs,
		updatedAtMs = updatedAtMs,
	)
	if (!lifecycleChanged) {
		val evidence = requireNotNull(evidenceDao.get())
		check(
			evidenceDao.incrementRevisionForExactLifecycle(
				expectedRevision = evidence.revision,
				expectedCollectedDataEpoch = operation.collectedDataEpoch,
				expectedRetainedFromMs = settledRetainedFromMs,
				updatedAtMs = updatedAtMs,
			) == 1,
		) { "Unable to advance source-evidence revision for retention floor" }
	}
	check(
		operationDao.compareAndSetRetentionPhase(
			operationId = operation.operationId,
			targetCollectedDataEpoch = operation.collectedDataEpoch,
			requestedRetainedFromMs = operation.requestedRetainedFromMs,
			expectedPhase =
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_DATASTORE_ACKNOWLEDGED,
			newPhase =
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_ROOM_GUARD_COMMITTED,
			updatedAtMs = updatedAtMs,
		) == 1,
	) { "Unable to commit retention-floor Room guard journal phase" }
	requireNotNull(operationDao.get(operation.operationId)).toRetentionOperation()
}

private fun CollectedDataDeletionOperationEntity.requireExact(
	operation: RetentionFloorSettlementOperation,
) {
	check(operationId == operation.operationId)
	check(targetCollectedDataEpoch == operation.collectedDataEpoch)
	check(retainedFromMs == operation.requestedRetainedFromMs)
	check(deletedAtMs == operation.requestedAtMs)
	check(phase in CollectedDataDeletionOperationEntity.RETENTION_PHASES)
}

private fun CollectedDataDeletionOperationEntity.toRetentionOperation() =
	RetentionFloorSettlementOperation(
		operationId = operationId,
		requestedRetainedFromMs = requireNotNull(retainedFromMs),
		collectedDataEpoch = targetCollectedDataEpoch,
		requestedAtMs = deletedAtMs,
		phase = phase,
	)
