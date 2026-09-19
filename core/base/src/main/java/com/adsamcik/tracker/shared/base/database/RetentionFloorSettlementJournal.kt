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
	val workExecutionId: String = operationId,
	val destructivePlan: RetentionFloorDestructivePlan =
		RetentionFloorDestructivePlan.legacy(
			requestedAtMs,
			requestedRetainedFromMs,
		),
	val settledRetainedFromMs: Long? = null,
) {
	init {
		require(operationId.isNotBlank())
		require(requestedRetainedFromMs >= 0L)
		require(collectedDataEpoch >= 0L)
		require(requestedAtMs >= 0L)
		require(phase in CollectedDataDeletionOperationEntity.RETENTION_PHASES)
		require(workExecutionId.isNotBlank())
		require(destructivePlan.requestedAtMs == requestedAtMs)
		require(destructivePlan.requestedRetainedFromMs == requestedRetainedFromMs)
		require(settledRetainedFromMs == null || settledRetainedFromMs >= requestedRetainedFromMs)
	}

	fun hasReached(expectedPhase: String): Boolean = entity().hasReachedRetentionPhase(expectedPhase)

	private fun entity() = CollectedDataDeletionOperationEntity(
		operationId = operationId,
		targetCollectedDataEpoch = collectedDataEpoch,
		retainedFromMs = requestedRetainedFromMs,
		deletedAtMs = requestedAtMs,
		phase = phase,
		updatedAtMs = requestedAtMs,
		retentionWorkExecutionId = workExecutionId,
		retentionDestructivePlan = destructivePlan.encode(),
		settledRetainedFromMs = settledRetainedFromMs,
	)
}

data class RetentionFloorDestructivePlan(
	val workerKind: String,
	val requestedAtMs: Long,
	val requestedRetainedFromMs: Long,
	val rawRetentionCutoffMs: Long?,
	val sourceEventRetentionCutoffMs: Long?,
	val wifiCellRetentionCutoffMs: Long?,
	val tripRetentionCutoffMs: Long?,
	val dailySummaryRetentionCutoffDay: Long?,
	val explorationRetentionCutoffMs: Long?,
	val operationalRetentionCutoffMs: Long?,
) {
	init {
		require(workerKind in WORKER_KINDS)
		require(requestedAtMs >= 0L)
		require(requestedRetainedFromMs >= 0L)
		listOfNotNull(
			rawRetentionCutoffMs,
			sourceEventRetentionCutoffMs,
			wifiCellRetentionCutoffMs,
			tripRetentionCutoffMs,
			dailySummaryRetentionCutoffDay,
			explorationRetentionCutoffMs,
			operationalRetentionCutoffMs,
		).forEach { require(it >= 0L) }
	}

	internal fun encode(): String = listOf(
		PLAN_VERSION,
		workerKind,
		requestedAtMs.toString(),
		requestedRetainedFromMs.toString(),
		encodeNullable(rawRetentionCutoffMs),
		encodeNullable(sourceEventRetentionCutoffMs),
		encodeNullable(wifiCellRetentionCutoffMs),
		encodeNullable(tripRetentionCutoffMs),
		encodeNullable(dailySummaryRetentionCutoffDay),
		encodeNullable(explorationRetentionCutoffMs),
		encodeNullable(operationalRetentionCutoffMs),
	).joinToString(PLAN_SEPARATOR)

	companion object {
		const val WORKER_RETENTION_PIPELINE = "RETENTION_PIPELINE"
		const val WORKER_DATA_RETENTION = "DATA_RETENTION"
		const val WORKER_LEGACY = "LEGACY"

		private const val PLAN_VERSION = "1"
		private const val PLAN_SEPARATOR = "|"
		private const val NULL_VALUE = "-"
		private val WORKER_KINDS = setOf(
			WORKER_RETENTION_PIPELINE,
			WORKER_DATA_RETENTION,
			WORKER_LEGACY,
		)

		internal fun decode(encoded: String): RetentionFloorDestructivePlan {
			val values = encoded.split(PLAN_SEPARATOR)
			require(values.size == 11 && values[0] == PLAN_VERSION) {
				"Unsupported retention destructive plan"
			}
			return RetentionFloorDestructivePlan(
				workerKind = values[1],
				requestedAtMs = values[2].toLong(),
				requestedRetainedFromMs = values[3].toLong(),
				rawRetentionCutoffMs = decodeNullable(values[4]),
				sourceEventRetentionCutoffMs = decodeNullable(values[5]),
				wifiCellRetentionCutoffMs = decodeNullable(values[6]),
				tripRetentionCutoffMs = decodeNullable(values[7]),
				dailySummaryRetentionCutoffDay = decodeNullable(values[8]),
				explorationRetentionCutoffMs = decodeNullable(values[9]),
				operationalRetentionCutoffMs = decodeNullable(values[10]),
			)
		}

		fun legacy(
			requestedAtMs: Long,
			requestedRetainedFromMs: Long,
		) = RetentionFloorDestructivePlan(
			workerKind = WORKER_LEGACY,
			requestedAtMs = requestedAtMs,
			requestedRetainedFromMs = requestedRetainedFromMs,
			rawRetentionCutoffMs = requestedRetainedFromMs,
			sourceEventRetentionCutoffMs = requestedRetainedFromMs,
			wifiCellRetentionCutoffMs = null,
			tripRetentionCutoffMs = null,
			dailySummaryRetentionCutoffDay = null,
			explorationRetentionCutoffMs = null,
			operationalRetentionCutoffMs = null,
		)

		private fun encodeNullable(value: Long?): String = value?.toString() ?: NULL_VALUE
		private fun decodeNullable(value: String): Long? =
			value.takeUnless { it == NULL_VALUE }?.toLong()
	}
}

suspend fun AppDatabase.activeRetentionFloorSettlement():
	RetentionFloorSettlementOperation? =
	collectedDataDeletionOperationDao().activeRetentionFloorSettlement()?.toRetentionOperation()

suspend fun AppDatabase.retentionFloorSettlement(
	operationId: String,
): RetentionFloorSettlementOperation? {
	require(operationId.isNotBlank())
	return collectedDataDeletionOperationDao().get(operationId)?.let {
		if (it.phase in CollectedDataDeletionOperationEntity.RETENTION_PHASES) {
			it.toRetentionOperation()
		} else {
			null
		}
	}
}

suspend fun AppDatabase.retentionFloorSettlementForWorkExecution(
	workExecutionId: String,
	resumeCompletedExecution: Boolean,
): RetentionFloorSettlementOperation? = withTransaction {
	require(workExecutionId.isNotBlank())
	val dao = collectedDataDeletionOperationDao()
	dao.activeRetentionFloorSettlement()?.let {
		return@withTransaction it.toRetentionOperation()
	}
	if (!resumeCompletedExecution) {
		dao.acknowledgeFinalRetentionFloorSettlementsForExecution(workExecutionId)
		return@withTransaction null
	}
	dao
		.latestRetentionFloorSettlementForExecution(workExecutionId)
		?.toRetentionOperation()
}

suspend fun AppDatabase.prepareOrResumeRetentionFloorSettlement(
	operationId: String,
	requestedRetainedFromMs: Long,
	collectedDataEpoch: Long,
	requestedAtMs: Long,
	workExecutionId: String = operationId,
	destructivePlan: RetentionFloorDestructivePlan =
		RetentionFloorDestructivePlan.legacy(
			requestedAtMs,
			requestedRetainedFromMs,
		),
): RetentionFloorSettlementOperation = withTransaction {
	require(operationId.isNotBlank())
	require(requestedRetainedFromMs >= 0L)
	require(collectedDataEpoch >= 0L)
	require(requestedAtMs >= 0L)
	require(workExecutionId.isNotBlank())
	require(destructivePlan.requestedAtMs == requestedAtMs)
	require(destructivePlan.requestedRetainedFromMs == requestedRetainedFromMs)
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
		check(
			existing.retentionWorkExecutionId == null ||
				existing.retentionWorkExecutionId == workExecutionId,
		) {
			"Retention-floor operation identity was reused by another work execution"
		}
		check(
			existing.retentionDestructivePlan == null ||
				existing.retentionDestructivePlan == destructivePlan.encode(),
		) {
			"Retention-floor operation identity was reused with another destructive plan"
		}
		return@withTransaction existing.toRetentionOperation()
	}
	val prepared = CollectedDataDeletionOperationEntity(
		operationId = operationId,
		targetCollectedDataEpoch = collectedDataEpoch,
		retainedFromMs = requestedRetainedFromMs,
		deletedAtMs = requestedAtMs,
		phase = CollectedDataDeletionOperationEntity.PHASE_RETENTION_PREPARED,
		updatedAtMs = requestedAtMs,
		retentionWorkExecutionId = workExecutionId,
		retentionDestructivePlan = destructivePlan.encode(),
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
		check((current.settledRetainedFromMs ?: current.retainedFromMs) == settledRetainedFromMs)
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
		operationDao.compareAndSetRetentionRoomGuard(
			operationId = operation.operationId,
			targetCollectedDataEpoch = operation.collectedDataEpoch,
			requestedRetainedFromMs = operation.requestedRetainedFromMs,
			settledRetainedFromMs = settledRetainedFromMs,
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
	check(retentionWorkExecutionId == null || retentionWorkExecutionId == operation.workExecutionId)
	check(
		retentionDestructivePlan == null ||
			retentionDestructivePlan == operation.destructivePlan.encode(),
	)
	check(settledRetainedFromMs == null || settledRetainedFromMs == operation.settledRetainedFromMs)
}

private fun CollectedDataDeletionOperationEntity.toRetentionOperation():
	RetentionFloorSettlementOperation {
	val requestedFloor = requireNotNull(retainedFromMs)
	val legacy = RetentionFloorDestructivePlan.legacy(
		deletedAtMs,
		requestedFloor,
	)
	return RetentionFloorSettlementOperation(
		operationId = operationId,
		requestedRetainedFromMs = requestedFloor,
		collectedDataEpoch = targetCollectedDataEpoch,
		requestedAtMs = deletedAtMs,
		phase = phase,
		workExecutionId = retentionWorkExecutionId ?: operationId,
		destructivePlan = retentionDestructivePlan
			?.let { RetentionFloorDestructivePlan.decode(it) }
			?: legacy,
		settledRetainedFromMs = settledRetainedFromMs ?: requestedFloor.takeIf {
			hasReachedRetentionPhase(
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_ROOM_GUARD_COMMITTED,
			)
		},
	)
}
