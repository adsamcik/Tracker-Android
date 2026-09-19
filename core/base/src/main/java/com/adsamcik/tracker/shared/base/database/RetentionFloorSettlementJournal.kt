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
	val sourceMaintenanceAtMs: Long? = null,
	val activeSourceKeys: Set<String> = emptySet(),
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
		require(sourceMaintenanceAtMs == null || sourceMaintenanceAtMs >= requestedAtMs)
		activeSourceKeys.forEach { require(it.isNotBlank() && SOURCE_KEY.matches(it)) }
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
		sourceMaintenanceAtMs = sourceMaintenanceAtMs,
		retentionActiveSources = activeSourceKeys.sorted().joinToString(",")
			.takeIf { sourceMaintenanceAtMs != null },
	)

	private companion object {
		val SOURCE_KEY = Regex("[A-Z_]+")
	}
}

data class RetentionFloorDestructivePlan(
	val workerKind: String,
	val requestedAtMs: Long,
	val requestedRetainedFromMs: Long?,
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
		require(requestedRetainedFromMs == null || requestedRetainedFromMs >= 0L)
		listOfNotNull(
			rawRetentionCutoffMs,
			sourceEventRetentionCutoffMs,
			wifiCellRetentionCutoffMs,
			tripRetentionCutoffMs,
			dailySummaryRetentionCutoffDay,
			explorationRetentionCutoffMs,
			operationalRetentionCutoffMs,
		).forEach { require(it >= 0L) }
		if (workerKind in setOf(WORKER_DATA_RETENTION, WORKER_LEGACY)) {
			require(requestedRetainedFromMs != null)
		}
		if (requestedRetainedFromMs == null) {
			require(rawRetentionCutoffMs == null)
			require(sourceEventRetentionCutoffMs == null)
			require(wifiCellRetentionCutoffMs == null)
		}
	}

	internal fun encode(): String = listOf(
		PLAN_VERSION,
		workerKind,
		requestedAtMs.toString(),
		encodeNullable(requestedRetainedFromMs),
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

		private const val PLAN_VERSION = "2"
		private const val PLAN_SEPARATOR = "|"
		private const val NULL_VALUE = "-"
		internal val WORKER_KINDS = setOf(
			WORKER_RETENTION_PIPELINE,
			WORKER_DATA_RETENTION,
			WORKER_LEGACY,
		)

		internal fun decode(encoded: String): RetentionFloorDestructivePlan {
			val values = encoded.split(PLAN_SEPARATOR)
			require(values.size == 11 && values[0] in setOf("1", PLAN_VERSION)) {
				"Unsupported retention destructive plan"
			}
			return RetentionFloorDestructivePlan(
				workerKind = values[1],
				requestedAtMs = values[2].toLong(),
				requestedRetainedFromMs = if (values[0] == "1") {
					values[3].toLong()
				} else {
					decodeNullable(values[3])
				},
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

	fun canBeExecutedBy(executorWorkerKind: String): Boolean = when (executorWorkerKind) {
		WORKER_RETENTION_PIPELINE -> true
		WORKER_DATA_RETENTION -> workerKind in setOf(WORKER_DATA_RETENTION, WORKER_LEGACY)
		WORKER_LEGACY -> workerKind == WORKER_LEGACY
		else -> false
	}

	val isDestructive: Boolean
		get() = listOfNotNull(
			rawRetentionCutoffMs,
			sourceEventRetentionCutoffMs,
			wifiCellRetentionCutoffMs,
			tripRetentionCutoffMs,
			dailySummaryRetentionCutoffDay,
			explorationRetentionCutoffMs,
			operationalRetentionCutoffMs,
		).isNotEmpty()
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

suspend fun AppDatabase.retentionFloorSettlementForExecution(
	execution: RetentionWorkExecutionReceipt,
	expectedOperationId: String? = null,
): RetentionFloorOperationLookupResult = withTransaction {
	require(expectedOperationId == null || expectedOperationId.isNotBlank())
	val dao = collectedDataDeletionOperationDao()
	val active = dao.activeRetentionFloorSettlement()
	if (expectedOperationId != null && active?.operationId != expectedOperationId) {
		return@withTransaction RetentionFloorOperationLookupResult.Available(null)
	}
	var operation = active?.toRetentionOperation()
		?: dao.latestRetentionFloorSettlementForExecution(execution.executionId)
			?.toRetentionOperation()
		?: return@withTransaction RetentionFloorOperationLookupResult.Available(null)
	if (expectedOperationId != null && operation.operationId != expectedOperationId) {
		return@withTransaction RetentionFloorOperationLookupResult.Available(null)
	}
	if (!operation.destructivePlan.canBeExecutedBy(execution.workerKind)) {
		return@withTransaction RetentionFloorOperationLookupResult.IncompatibleExecutor(
			RetentionFloorExecutorDebt(
				operationId = operation.operationId,
				planWorkerKind = operation.destructivePlan.workerKind,
				executorWorkerKind = execution.workerKind,
			),
		)
	}
	if (active != null && operation.workExecutionId != execution.executionId) {
		val ownerReceipt = retentionWorkExecutionReceiptDao().get(operation.workExecutionId)
			?: retentionWorkExecutionReceiptDao().latest(operation.workExecutionId)
		if (
			ownerReceipt?.state in setOf(
				com.adsamcik.tracker.shared.base.database.data
					.RetentionWorkExecutionReceiptEntity.STATE_OPEN,
				com.adsamcik.tracker.shared.base.database.data
					.RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED,
			) &&
			ownerReceipt.executionId != execution.executionId
		) {
			return@withTransaction RetentionFloorOperationLookupResult.ExecutionOwned(
				RetentionFloorExecutionOwnerDebt(
					operationId = operation.operationId,
					ownerExecutionId = ownerReceipt.executionId,
					requestedExecutionId = execution.executionId,
				),
			)
		}
		check(
			dao.claimActiveRetentionExecution(
				operationId = operation.operationId,
				expectedWorkExecutionId = active.retentionWorkExecutionId,
				newWorkExecutionId = execution.executionId,
			) == 1,
		) { "Unable to claim compatible retention execution debt" }
		operation = requireNotNull(dao.get(operation.operationId)).toRetentionOperation()
	}
	RetentionFloorOperationLookupResult.Available(operation)
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
	dao.activeRetentionFloorSettlement()?.let { active ->
		val operation = active.toRetentionOperation()
		check(operation.workExecutionId == workExecutionId) {
			"Retention-floor operation is claimed by another execution"
		}
		check(operation.destructivePlan == destructivePlan) {
			"Retention-floor operation is bound to another destructive plan"
		}
		return@withTransaction operation
	}
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
				RetentionFloorDestructivePlan.decode(existing.retentionDestructivePlan) ==
				destructivePlan,
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

suspend fun AppDatabase.commitRetentionFloorAuthorityReissued(
	operation: RetentionFloorSettlementOperation,
	activeSourceKeys: Set<String>,
): RetentionFloorSettlementOperation = withTransaction {
	activeSourceKeys.forEach { require(it.isNotBlank() && it.matches(Regex("[A-Z_]+"))) }
	val dao = collectedDataDeletionOperationDao()
	val current = requireNotNull(dao.get(operation.operationId)) {
		"Retention-floor settlement journal disappeared"
	}
	current.requireExact(operation)
	if (
		current.hasReachedRetentionPhase(
			CollectedDataDeletionOperationEntity.PHASE_RETENTION_AUTHORITY_REISSUED,
		)
	) {
		checkNotNull(current.sourceMaintenanceAtMs)
		checkNotNull(current.retentionActiveSources)
		return@withTransaction current.toRetentionOperation()
	}
	check(
		current.phase ==
			CollectedDataDeletionOperationEntity.PHASE_RETENTION_ROOM_GUARD_COMMITTED,
	) { "Retention authority was reissued before the Room guard" }
	val sourceMaintenanceAtMs = maxOf(
		operation.requestedAtMs,
		dao.retentionAuthorityTimestampHighWater() ?: operation.requestedAtMs,
	)
	check(
		dao.compareAndSetRetentionAuthorityReissued(
			operationId = operation.operationId,
			targetCollectedDataEpoch = operation.collectedDataEpoch,
			requestedRetainedFromMs = operation.requestedRetainedFromMs,
			sourceMaintenanceAtMs = sourceMaintenanceAtMs,
			activeSources = activeSourceKeys.sorted().joinToString(","),
			expectedPhase =
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_ROOM_GUARD_COMMITTED,
			newPhase =
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_AUTHORITY_REISSUED,
		) == 1,
	) { "Unable to persist the post-authority maintenance timestamp" }
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
			RetentionFloorDestructivePlan.decode(retentionDestructivePlan) ==
			operation.destructivePlan,
	)
	check(settledRetainedFromMs == null || settledRetainedFromMs == operation.settledRetainedFromMs)
	check(sourceMaintenanceAtMs == null || sourceMaintenanceAtMs == operation.sourceMaintenanceAtMs)
	check(
		retentionActiveSources == null ||
			decodeActiveSourceKeys(retentionActiveSources) == operation.activeSourceKeys,
	)
}

private fun CollectedDataDeletionOperationEntity.toRetentionOperation():
	RetentionFloorSettlementOperation {
	val requestedFloor = requireNotNull(retainedFromMs)
	val legacy = RetentionFloorDestructivePlan.legacy(
		deletedAtMs,
		requestedFloor,
	)
	val authorityReissued = hasReachedRetentionPhase(
		CollectedDataDeletionOperationEntity.PHASE_RETENTION_AUTHORITY_REISSUED,
	)
	val activeSourceKeys = if (authorityReissued) {
		decodeActiveSourceKeys(requireNotNull(retentionActiveSources))
	} else {
		emptySet()
	}
	val maintenanceAtMs = if (authorityReissued) {
		requireNotNull(sourceMaintenanceAtMs)
	} else {
		null
	}
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
		sourceMaintenanceAtMs = maintenanceAtMs,
		activeSourceKeys = activeSourceKeys,
	)
}

private fun decodeActiveSourceKeys(encoded: String): Set<String> =
	if (encoded.isEmpty()) emptySet() else encoded.split(',').toSet()

sealed interface RetentionFloorOperationLookupResult {
	data class Available(
		val operation: RetentionFloorSettlementOperation?,
	) : RetentionFloorOperationLookupResult

	data class IncompatibleExecutor(
		val debt: RetentionFloorExecutorDebt,
	) : RetentionFloorOperationLookupResult

	data class ExecutionOwned(
		val debt: RetentionFloorExecutionOwnerDebt,
	) : RetentionFloorOperationLookupResult
}

data class RetentionFloorExecutorDebt(
	val operationId: String,
	val planWorkerKind: String,
	val executorWorkerKind: String,
) {
	init {
		require(operationId.isNotBlank())
		require(planWorkerKind in RetentionFloorDestructivePlan.WORKER_KINDS)
		require(executorWorkerKind in RetentionFloorDestructivePlan.WORKER_KINDS)
		require(planWorkerKind != executorWorkerKind)
	}
}

data class RetentionFloorExecutionOwnerDebt(
	val operationId: String,
	val ownerExecutionId: String,
	val requestedExecutionId: String,
) {
	init {
		require(operationId.isNotBlank())
		require(ownerExecutionId.isNotBlank())
		require(requestedExecutionId.isNotBlank())
		require(ownerExecutionId != requestedExecutionId)
	}
}
