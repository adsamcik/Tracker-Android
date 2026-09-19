package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.RetentionWorkExecutionReceiptEntity

data class RetentionWorkExecutionReceipt(
	val executionId: String,
	val workRequestId: String,
	val executionGeneration: Long,
	val workerKind: String,
	val startedAtMs: Long,
	val state: String,
	val destructivePlan: RetentionFloorDestructivePlan?,
	val updatedAtMs: Long,
) {
	init {
		require(executionId.isNotBlank())
		require(workRequestId.isNotBlank())
		require(executionGeneration > 0L)
		require(workerKind in RetentionFloorDestructivePlan.WORKER_KINDS)
		require(startedAtMs >= 0L)
		require(updatedAtMs >= startedAtMs)
		require(destructivePlan == null || destructivePlan.canBeExecutedBy(workerKind))
	}
}

sealed interface RetentionWorkExecutionStartResult {
	data class Open(
		val receipt: RetentionWorkExecutionReceipt,
	) : RetentionWorkExecutionStartResult

	data class Retryable(
		val failure: RetentionWorkExecutionFailure,
	) : RetentionWorkExecutionStartResult

	data object SupersededByFullDeletion : RetentionWorkExecutionStartResult
}

sealed interface RetentionWorkExecutionFailure {
	data class PreviousExecutionNotOpen(
		val workRequestId: String,
		val executionGeneration: Long,
		val state: String,
	) : RetentionWorkExecutionFailure

	data class WorkerKindMismatch(
		val expectedWorkerKind: String,
		val actualWorkerKind: String,
	) : RetentionWorkExecutionFailure

	data class DestructivePlanMismatch(
		val executionId: String,
	) : RetentionWorkExecutionFailure
}

sealed interface RetentionWorkExecutionPlanResult {
	data class Attached(
		val receipt: RetentionWorkExecutionReceipt,
	) : RetentionWorkExecutionPlanResult

	data class Retryable(
		val failure: RetentionWorkExecutionFailure,
	) : RetentionWorkExecutionPlanResult
}

sealed interface RetentionWorkExecutionCompletionResult {
	data object Completed : RetentionWorkExecutionCompletionResult
	data object SupersededByFullDeletion : RetentionWorkExecutionCompletionResult
	data class Retryable(
		val failure: RetentionWorkExecutionFailure,
	) : RetentionWorkExecutionCompletionResult
}

suspend fun AppDatabase.beginOrResumeRetentionWorkExecution(
	workRequestId: String,
	workerKind: String,
	runAttemptCount: Int,
	startedAtMs: Long,
): RetentionWorkExecutionStartResult = withTransaction {
	require(workRequestId.isNotBlank())
	require(workerKind in RetentionFloorDestructivePlan.WORKER_KINDS)
	require(runAttemptCount >= 0)
	require(startedAtMs >= 0L)
	val dao = retentionWorkExecutionReceiptDao()
	val latest = dao.latest(workRequestId)
	if (latest?.state == RetentionWorkExecutionReceiptEntity.STATE_OPEN) {
		return@withTransaction if (latest.workerKind == workerKind) {
			RetentionWorkExecutionStartResult.Open(latest.toReceipt())
		} else {
			RetentionWorkExecutionStartResult.Retryable(
				RetentionWorkExecutionFailure.WorkerKindMismatch(
					expectedWorkerKind = latest.workerKind,
					actualWorkerKind = workerKind,
				),
			)
		}
	}
	if (runAttemptCount > 0 && latest != null) {
		return@withTransaction if (
			latest.state == RetentionWorkExecutionReceiptEntity.STATE_SUPERSEDED
		) {
			RetentionWorkExecutionStartResult.SupersededByFullDeletion
		} else {
			RetentionWorkExecutionStartResult.Retryable(
				RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
					workRequestId = workRequestId,
					executionGeneration = latest.executionGeneration,
					state = latest.state,
				),
			)
		}
	}
	val legacyFinal = if (latest == null) {
		collectedDataDeletionOperationDao()
			.latestRetentionFloorSettlementForExecution(workRequestId)
			?.takeIf {
				it.phase ==
					com.adsamcik.tracker.shared.base.database.data
						.CollectedDataDeletionOperationEntity.PHASE_RETENTION_FINAL
			}
	} else {
		null
	}
	if (legacyFinal != null && runAttemptCount > 0) {
		return@withTransaction RetentionWorkExecutionStartResult.Retryable(
			RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
				workRequestId = workRequestId,
				executionGeneration = 0L,
				state = legacyFinal.phase,
			),
		)
	}
	val generation = try {
		Math.addExact(latest?.executionGeneration ?: 0L, 1L)
	} catch (_: ArithmeticException) {
		return@withTransaction RetentionWorkExecutionStartResult.Retryable(
			RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
				workRequestId = workRequestId,
				executionGeneration = Long.MAX_VALUE,
				state = latest?.state ?: "MISSING",
			),
		)
	}
	if (latest?.state == RetentionWorkExecutionReceiptEntity.STATE_FINAL) {
		check(
			dao.compareAndSetState(
				executionId = latest.executionId,
				expectedState = RetentionWorkExecutionReceiptEntity.STATE_FINAL,
				newState = RetentionWorkExecutionReceiptEntity.STATE_ACKNOWLEDGED,
				updatedAtMs = maxOf(startedAtMs, latest.updatedAtMs),
			) == 1,
		) { "Unable to acknowledge the previous retention execution" }
		collectedDataDeletionOperationDao()
			.acknowledgeFinalRetentionFloorSettlementsForExecution(latest.executionId)
	}
	if (legacyFinal != null) {
		check(
			collectedDataDeletionOperationDao()
				.acknowledgeFinalRetentionFloorSettlementsForExecution(workRequestId) > 0,
		) { "Unable to acknowledge the legacy retention execution" }
	}
	val receipt = RetentionWorkExecutionReceiptEntity(
		executionId = "$workRequestId:g$generation",
		workRequestId = workRequestId,
		executionGeneration = generation,
		workerKind = workerKind,
		startedAtMs = startedAtMs,
		state = RetentionWorkExecutionReceiptEntity.STATE_OPEN,
		destructivePlan = null,
		updatedAtMs = startedAtMs,
	)
	dao.insert(receipt)
	RetentionWorkExecutionStartResult.Open(receipt.toReceipt())
}

suspend fun AppDatabase.attachRetentionDestructivePlan(
	receipt: RetentionWorkExecutionReceipt,
	destructivePlan: RetentionFloorDestructivePlan,
): RetentionWorkExecutionPlanResult = withTransaction {
	if (!destructivePlan.canBeExecutedBy(receipt.workerKind)) {
		return@withTransaction RetentionWorkExecutionPlanResult.Retryable(
			RetentionWorkExecutionFailure.WorkerKindMismatch(
				expectedWorkerKind = destructivePlan.workerKind,
				actualWorkerKind = receipt.workerKind,
			),
		)
	}
	val dao = retentionWorkExecutionReceiptDao()
	val current = dao.get(receipt.executionId)
		?: return@withTransaction RetentionWorkExecutionPlanResult.Retryable(
			RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
				receipt.workRequestId,
				receipt.executionGeneration,
				"MISSING",
			),
		)
	if (current.state != RetentionWorkExecutionReceiptEntity.STATE_OPEN) {
		return@withTransaction RetentionWorkExecutionPlanResult.Retryable(
			RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
				current.workRequestId,
				current.executionGeneration,
				current.state,
			),
		)
	}
	val encoded = destructivePlan.encode()
	if (current.destructivePlan == null) {
		check(
			dao.attachDestructivePlan(
				executionId = current.executionId,
				destructivePlan = encoded,
				updatedAtMs = maxOf(current.updatedAtMs, destructivePlan.requestedAtMs),
			) == 1,
		) { "Unable to attach the immutable retention destructive plan" }
	} else if (current.destructivePlan != encoded) {
		return@withTransaction RetentionWorkExecutionPlanResult.Retryable(
			RetentionWorkExecutionFailure.DestructivePlanMismatch(current.executionId),
		)
	}
	RetentionWorkExecutionPlanResult.Attached(
		requireNotNull(dao.get(current.executionId)).toReceipt(),
	)
}

suspend fun AppDatabase.completeRetentionWorkExecution(
	receipt: RetentionWorkExecutionReceipt,
	completedAtMs: Long,
): RetentionWorkExecutionCompletionResult = withTransaction {
	require(completedAtMs >= receipt.startedAtMs)
	val dao = retentionWorkExecutionReceiptDao()
	val current = dao.get(receipt.executionId)
		?: return@withTransaction RetentionWorkExecutionCompletionResult.Retryable(
			RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
				receipt.workRequestId,
				receipt.executionGeneration,
				"MISSING",
			),
		)
	when (current.state) {
		RetentionWorkExecutionReceiptEntity.STATE_FINAL ->
			RetentionWorkExecutionCompletionResult.Completed
		RetentionWorkExecutionReceiptEntity.STATE_SUPERSEDED ->
			RetentionWorkExecutionCompletionResult.SupersededByFullDeletion
		RetentionWorkExecutionReceiptEntity.STATE_OPEN -> {
			check(
				dao.compareAndSetState(
					executionId = current.executionId,
					expectedState = RetentionWorkExecutionReceiptEntity.STATE_OPEN,
					newState = RetentionWorkExecutionReceiptEntity.STATE_FINAL,
					updatedAtMs = maxOf(completedAtMs, current.updatedAtMs),
				) == 1,
			) { "Unable to finalize the retention work execution" }
			RetentionWorkExecutionCompletionResult.Completed
		}
		else -> RetentionWorkExecutionCompletionResult.Retryable(
			RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
				current.workRequestId,
				current.executionGeneration,
				current.state,
			),
		)
	}
}

private fun RetentionWorkExecutionReceiptEntity.toReceipt() = RetentionWorkExecutionReceipt(
	executionId = executionId,
	workRequestId = workRequestId,
	executionGeneration = executionGeneration,
	workerKind = workerKind,
	startedAtMs = startedAtMs,
	state = state,
	destructivePlan = destructivePlan?.let(RetentionFloorDestructivePlan::decode),
	updatedAtMs = updatedAtMs,
)
