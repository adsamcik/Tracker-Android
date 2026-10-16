package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.CollectedDataDeletionOperationDao
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.RetentionWorkExecutionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.hasReachedRetentionPhase

/**
 * Durable retention operation with requested/settled UTC millisecond floor, exact data epoch,
 * execution ownership and phase.
 */
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

	/**
	 * Checks whether the saved operation has reached the named durable phase in the defined retention
	 * ordering.
	 */
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

/**
 * Original worker-specific deletion cutoffs; UTC milliseconds are distinct from the daily-summary
 * calendar epoch-day cutoff.
 */
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

	/**
	 * Supported executor kinds and original destructive-plan encoding/legacy construction.
	 */
	companion object {
		const val WORKER_RETENTION_PIPELINE = "RETENTION_PIPELINE"
		const val WORKER_DATA_RETENTION = "DATA_RETENTION"
		const val WORKER_LEGACY = "LEGACY"

		private const val PLAN_VERSION = "2"
		private const val ENCODED_PLAN_FIELD_COUNT = 11
		private const val PLAN_SEPARATOR = "|"
		private const val NULL_VALUE = "-"
		internal val WORKER_KINDS = setOf(
			WORKER_RETENTION_PIPELINE,
			WORKER_DATA_RETENTION,
			WORKER_LEGACY,
		)

		internal fun decode(encoded: String): RetentionFloorDestructivePlan {
			val values = encoded.split(PLAN_SEPARATOR)
			require(values.size == ENCODED_PLAN_FIELD_COUNT && values[0] in setOf("1", PLAN_VERSION)) {
				"Unsupported retention destructive plan"
			}
			val fields = values.iterator()
			val version = fields.next()
			return RetentionFloorDestructivePlan(
				workerKind = fields.next(),
				requestedAtMs = fields.next().toLong(),
				requestedRetainedFromMs = fields.next().let { value ->
					if (version == "1") {
						value.toLong()
					} else {
						decodeNullable(value)
					}
				},
				rawRetentionCutoffMs = decodeNullable(fields.next()),
				sourceEventRetentionCutoffMs = decodeNullable(fields.next()),
				wifiCellRetentionCutoffMs = decodeNullable(fields.next()),
				tripRetentionCutoffMs = decodeNullable(fields.next()),
				dailySummaryRetentionCutoffDay = decodeNullable(fields.next()),
				explorationRetentionCutoffMs = decodeNullable(fields.next()),
				operationalRetentionCutoffMs = decodeNullable(fields.next()),
			)
		}

		/**
		 * Creates the legacy raw/source-event floor plan; other source/product cutoffs remain
		 * unspecified.
		 */
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

	/**
	 * Checks whether the worker kind can execute this exact retained destructive plan.
	 */
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

/**
 * Returns the active durable retention operation, or null when no settlement is in progress.
 */
suspend fun AppDatabase.activeRetentionFloorSettlement():
	RetentionFloorSettlementOperation? =
	collectedDataDeletionOperationDao().activeRetentionFloorSettlement()?.toRetentionOperation()

/**
 * Loads and validates the saved retention operation by identity, or returns null when absent.
 */
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

/**
 * Resolves the exact operation/epoch to active, completed, missing or full-clear-superseded state
 * atomically.
 */
suspend fun AppDatabase.retentionFloorSettlementDisposition(
	operationId: String,
	expectedCollectedDataEpoch: Long,
): RetentionFloorSettlementDisposition = withTransaction {
	require(operationId.isNotBlank())
	require(expectedCollectedDataEpoch >= 0L)
	val operation = collectedDataDeletionOperationDao().get(operationId)
	if (
		operation != null &&
		operation.targetCollectedDataEpoch == expectedCollectedDataEpoch &&
		operation.phase in CollectedDataDeletionOperationEntity.RETENTION_PHASES
	) {
		return@withTransaction when (operation.phase) {
			CollectedDataDeletionOperationEntity.PHASE_RETENTION_FINAL,
			CollectedDataDeletionOperationEntity.PHASE_RETENTION_ACKNOWLEDGED,
			-> RetentionFloorSettlementDisposition.Completed
			else -> RetentionFloorSettlementDisposition.Active
		}
	}
	val deletion = collectedDataDeletionOperationDao()
		.completedFullDeletionAfter(expectedCollectedDataEpoch)
	return@withTransaction if (deletion == null) {
		RetentionFloorSettlementDisposition.Missing
	} else {
		RetentionFloorSettlementDisposition.SupersededByFullDeletion(
			deletion.targetCollectedDataEpoch,
		)
	}
}

/**
 * Resolves the operation under this exact work receipt, retaining incompatible-worker and
 * another-execution ownership debt.
 */
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
	val operation = active?.toRetentionOperation()
		?: dao.latestRetentionFloorSettlementForExecution(execution.executionId)
			?.toRetentionOperation()
		?: return@withTransaction RetentionFloorOperationLookupResult.Available(null)
	qualifySelectedRetentionOperation(dao, active, operation, execution, expectedOperationId)
}

private suspend fun AppDatabase.qualifySelectedRetentionOperation(
	dao: CollectedDataDeletionOperationDao,
	active: CollectedDataDeletionOperationEntity?,
	operation: RetentionFloorSettlementOperation,
	execution: RetentionWorkExecutionReceipt,
	expectedOperationId: String?,
): RetentionFloorOperationLookupResult {
	if (expectedOperationId != null && operation.operationId != expectedOperationId) {
		return RetentionFloorOperationLookupResult.Available(null)
	}
	if (!operation.destructivePlan.canBeExecutedBy(execution.workerKind)) {
		return RetentionFloorOperationLookupResult.IncompatibleExecutor(
			RetentionFloorExecutorDebt(
				operationId = operation.operationId,
				planWorkerKind = operation.destructivePlan.workerKind,
				executorWorkerKind = execution.workerKind,
			),
		)
	}
	if (active != null && operation.workExecutionId != execution.executionId) {
		return claimRetentionExecution(dao, operation, active, execution)
	}
	return RetentionFloorOperationLookupResult.Available(operation)
}

private suspend fun AppDatabase.claimRetentionExecution(
	dao: CollectedDataDeletionOperationDao,
	operation: RetentionFloorSettlementOperation,
	active: CollectedDataDeletionOperationEntity,
	execution: RetentionWorkExecutionReceipt,
): RetentionFloorOperationLookupResult {
	val ownerReceipt = retentionWorkExecutionReceiptDao().get(operation.workExecutionId)
		?: retentionWorkExecutionReceiptDao().latest(operation.workExecutionId)
	if (
		ownerReceipt != null && ownerReceipt.state in setOf(
			com.adsamcik.tracker.shared.base.database.data
				.RetentionWorkExecutionReceiptEntity.STATE_OPEN,
			com.adsamcik.tracker.shared.base.database.data
				.RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED,
		) &&
		ownerReceipt.executionId != execution.executionId
	) {
		return RetentionFloorOperationLookupResult.ExecutionOwned(
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
	val claimed = requireNotNull(dao.get(operation.operationId)).toRetentionOperation()
	return RetentionFloorOperationLookupResult.Available(claimed)
}

/**
 * Atomically creates or resumes the exact retention operation, epoch, original cutoffs and
 * work-execution ownership.
 */
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
	requireRetentionPreparationExecution(workExecutionId, operationId, destructivePlan)
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

private suspend fun AppDatabase.requireRetentionPreparationExecution(
	workExecutionId: String,
	operationId: String,
	destructivePlan: RetentionFloorDestructivePlan,
) {
	val execution = retentionWorkExecutionReceiptDao().get(workExecutionId)
	if (execution == null) {
		check(
			destructivePlan.workerKind == RetentionFloorDestructivePlan.WORKER_LEGACY &&
				workExecutionId == operationId,
		) { "Retention-floor preparation requires its durable work execution" }
	} else {
		check(execution.state == RetentionWorkExecutionReceiptEntity.STATE_OPEN) {
			"Retention-floor preparation requires an OPEN work execution"
		}
		check(destructivePlan.canBeExecutedBy(execution.workerKind)) {
			"Retention-floor preparation worker kind changed"
		}
		check(execution.destructivePlan == destructivePlan.encode()) {
			"Retention-floor preparation requires the attached destructive plan"
		}
	}
}

/**
 * Atomically advances only the expected saved operation phase and original authority with UTC
 * millisecond update time.
 */
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

/**
 * Records the original operation's reissued active-source keys without allowing a different
 * execution to claim it.
 */
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

/**
 * Atomically publishes the settled UTC millisecond floor and room-guard phase under the original
 * retention operation.
 */
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
	if (encoded.isEmpty()) {
		emptySet()
	} else {
		encoded.split(',').toSet()
	}

/**
 * Whether a saved retention operation is active, completed, absent or superseded by a full-data
 * clear.
 */
sealed interface RetentionFloorSettlementDisposition {
	/**
	 * The exact original retention operation is still active.
	 */
	data object Active : RetentionFloorSettlementDisposition
	/**
	 * The exact original retention operation has durably reached its completed phase.
	 */
	data object Completed : RetentionFloorSettlementDisposition
	/**
	 * A full-data deletion changed the epoch and superseded the original retention operation.
	 */
	data class SupersededByFullDeletion(
		val collectedDataEpoch: Long,
	) : RetentionFloorSettlementDisposition {
		init {
			require(collectedDataEpoch >= 0L)
		}
	}
	/**
	 * No original retention operation exists for the selected identity/epoch.
	 */
	data object Missing : RetentionFloorSettlementDisposition
}

/**
 * Execution-owned retention lookup, distinguishing compatible work from another executor or
 * another execution owner.
 */
sealed interface RetentionFloorOperationLookupResult {
	/**
	 * The executor may use the matched operation; null means no owned settlement operation exists.
	 */
	data class Available(
		val operation: RetentionFloorSettlementOperation?,
	) : RetentionFloorOperationLookupResult

	/**
	 * The saved destructive plan belongs to a different worker kind; its remaining work is retained
	 * as debt.
	 */
	data class IncompatibleExecutor(
		val debt: RetentionFloorExecutorDebt,
	) : RetentionFloorOperationLookupResult

	/**
	 * Another exact work execution owns the saved operation and must settle it before a handoff.
	 */
	data class ExecutionOwned(
		val debt: RetentionFloorExecutionOwnerDebt,
	) : RetentionFloorOperationLookupResult
}

/**
 * Saved plan/worker-kind mismatch that prevents this executor from completing the retention
 * operation.
 */
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

/**
 * Saved execution ownership that prevents another work attempt from claiming the operation.
 */
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
