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

	data object AlreadyCompleted : RetentionWorkExecutionStartResult
	data object CancellationRequested : RetentionWorkExecutionStartResult
	data object AbandonedByCancellation : RetentionWorkExecutionStartResult

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

	data class CancellationHandoffOwned(
		val ownerExecutionId: String,
		val ownerWorkerKind: String,
	) : RetentionWorkExecutionFailure
}

sealed interface RetentionWorkExecutionPlanResult {
	data class Attached(
		val receipt: RetentionWorkExecutionReceipt,
	) : RetentionWorkExecutionPlanResult

	data object CancellationRequested : RetentionWorkExecutionPlanResult
	data object AbandonedByCancellation : RetentionWorkExecutionPlanResult

	data class Retryable(
		val failure: RetentionWorkExecutionFailure,
	) : RetentionWorkExecutionPlanResult
}

sealed interface RetentionWorkExecutionCompletionResult {
	data object Completed : RetentionWorkExecutionCompletionResult
	data object CancellationRequested : RetentionWorkExecutionCompletionResult
	data object AbandonedByCancellation : RetentionWorkExecutionCompletionResult
	data object SupersededByFullDeletion : RetentionWorkExecutionCompletionResult
	data class Retryable(
		val failure: RetentionWorkExecutionFailure,
	) : RetentionWorkExecutionCompletionResult
}

sealed interface RetentionWorkExecutionContinuationResult {
	data object Continue : RetentionWorkExecutionContinuationResult
	data object CancellationRequested : RetentionWorkExecutionContinuationResult
	data object AbandonedByCancellation : RetentionWorkExecutionContinuationResult
	data object SupersededByFullDeletion : RetentionWorkExecutionContinuationResult
	data object AlreadyCompleted : RetentionWorkExecutionContinuationResult

	data class Retryable(
		val failure: RetentionWorkExecutionFailure,
	) : RetentionWorkExecutionContinuationResult
}

data class RetentionWorkCancellationTarget(
	val workRequestId: String,
	val workerKind: String,
) {
	init {
		require(workRequestId.isNotBlank())
		require(workerKind in RetentionFloorDestructivePlan.WORKER_KINDS)
	}
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
	if (latest?.state == RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED) {
		return@withTransaction RetentionWorkExecutionStartResult.CancellationRequested
	}
	if (latest?.state == RetentionWorkExecutionReceiptEntity.STATE_ABANDONED) {
		return@withTransaction RetentionWorkExecutionStartResult.AbandonedByCancellation
	}
	if (
		runAttemptCount > 0 &&
		latest?.state == RetentionWorkExecutionReceiptEntity.STATE_FINAL
	) {
		return@withTransaction RetentionWorkExecutionStartResult.AlreadyCompleted
	}
	if (
		runAttemptCount > 0 &&
		latest?.state == RetentionWorkExecutionReceiptEntity.STATE_SUPERSEDED
	) {
		return@withTransaction RetentionWorkExecutionStartResult.SupersededByFullDeletion
	}
	val cancellationOwner = dao
		.pendingCancellations(RetentionFloorDestructivePlan.WORKER_KINDS)
		.firstOrNull { it.workRequestId != workRequestId }
	if (cancellationOwner != null) {
		return@withTransaction RetentionWorkExecutionStartResult.Retryable(
			RetentionWorkExecutionFailure.CancellationHandoffOwned(
				ownerExecutionId = cancellationOwner.executionId,
				ownerWorkerKind = cancellationOwner.workerKind,
			),
		)
	}
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
		return@withTransaction RetentionWorkExecutionStartResult.Retryable(
			RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
				workRequestId = workRequestId,
				executionGeneration = latest.executionGeneration,
				state = latest.state,
			),
		)
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
		return@withTransaction RetentionWorkExecutionStartResult.AlreadyCompleted
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
	if (current.state == RetentionWorkExecutionReceiptEntity.STATE_ABANDONED) {
		return@withTransaction RetentionWorkExecutionPlanResult.AbandonedByCancellation
	}
	if (current.state == RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED) {
		return@withTransaction RetentionWorkExecutionPlanResult.CancellationRequested
	}
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
		RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED ->
			RetentionWorkExecutionCompletionResult.CancellationRequested
		RetentionWorkExecutionReceiptEntity.STATE_ABANDONED ->
			RetentionWorkExecutionCompletionResult.AbandonedByCancellation
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

suspend fun AppDatabase.retentionWorkExecutionContinuation(
	receipt: RetentionWorkExecutionReceipt,
): RetentionWorkExecutionContinuationResult = withTransaction {
	val current = retentionWorkExecutionReceiptDao().get(receipt.executionId)
		?: return@withTransaction RetentionWorkExecutionContinuationResult.Retryable(
			RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
				receipt.workRequestId,
				receipt.executionGeneration,
				"MISSING",
			),
		)
	if (
		current.workRequestId != receipt.workRequestId ||
		current.executionGeneration != receipt.executionGeneration
	) {
		return@withTransaction RetentionWorkExecutionContinuationResult.Retryable(
			RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
				receipt.workRequestId,
				receipt.executionGeneration,
				current.state,
			),
		)
	}
	if (current.workerKind != receipt.workerKind) {
		return@withTransaction RetentionWorkExecutionContinuationResult.Retryable(
			RetentionWorkExecutionFailure.WorkerKindMismatch(
				expectedWorkerKind = current.workerKind,
				actualWorkerKind = receipt.workerKind,
			),
		)
	}
	if (current.state == RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED) {
		return@withTransaction RetentionWorkExecutionContinuationResult.CancellationRequested
	}
	if (current.state == RetentionWorkExecutionReceiptEntity.STATE_ABANDONED) {
		return@withTransaction RetentionWorkExecutionContinuationResult.AbandonedByCancellation
	}
	if (current.state == RetentionWorkExecutionReceiptEntity.STATE_SUPERSEDED) {
		return@withTransaction RetentionWorkExecutionContinuationResult.SupersededByFullDeletion
	}
	if (
		current.state == RetentionWorkExecutionReceiptEntity.STATE_FINAL ||
		current.state == RetentionWorkExecutionReceiptEntity.STATE_ACKNOWLEDGED
	) {
		return@withTransaction RetentionWorkExecutionContinuationResult.AlreadyCompleted
	}
	val cancellationOwner = retentionWorkExecutionReceiptDao()
		.pendingCancellations(RetentionFloorDestructivePlan.WORKER_KINDS)
		.firstOrNull { it.executionId != receipt.executionId }
	if (cancellationOwner != null) {
		return@withTransaction RetentionWorkExecutionContinuationResult.Retryable(
			RetentionWorkExecutionFailure.CancellationHandoffOwned(
				ownerExecutionId = cancellationOwner.executionId,
				ownerWorkerKind = cancellationOwner.workerKind,
			),
		)
	}
	when (current.state) {
		RetentionWorkExecutionReceiptEntity.STATE_OPEN ->
			RetentionWorkExecutionContinuationResult.Continue
		else -> RetentionWorkExecutionContinuationResult.Retryable(
			RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
				current.workRequestId,
				current.executionGeneration,
				current.state,
			),
		)
	}
}

/**
 * Publishes cancellation ownership before WorkManager is asked to stop an execution. Active work
 * without a receipt receives a cancellation generation so a concurrently starting worker cannot
 * create OPEN ownership between the WorkManager snapshot and cancellation processing.
 */
suspend fun AppDatabase.requestRetentionWorkExecutionCancellations(
	targets: Collection<RetentionWorkCancellationTarget>,
	activeWorkRequestIds: Collection<String>,
	workerKinds: Collection<String>,
	requestedAtMs: Long,
): List<RetentionWorkExecutionReceipt> = withTransaction {
	require(requestedAtMs >= 0L)
	val exactWorkerKinds = workerKinds.onEach {
		require(it in RetentionFloorDestructivePlan.WORKER_KINDS)
	}.distinct()
	val exactTargets = targets
		.distinctBy(RetentionWorkCancellationTarget::workRequestId)
	val exactActiveIds = activeWorkRequestIds
		.onEach { require(it.isNotBlank()) }
		.toSet()
	require(exactActiveIds.all { activeId ->
		exactTargets.any { it.workRequestId == activeId }
	})
	val dao = retentionWorkExecutionReceiptDao()
	val requested = linkedMapOf<String, RetentionWorkExecutionReceiptEntity>()

	fun cancellationGeneration(
		target: RetentionWorkCancellationTarget,
		latest: RetentionWorkExecutionReceiptEntity?,
	): RetentionWorkExecutionReceiptEntity {
		val generation = Math.addExact(latest?.executionGeneration ?: 0L, 1L)
		return RetentionWorkExecutionReceiptEntity(
			executionId = "${target.workRequestId}:g$generation",
			workRequestId = target.workRequestId,
			executionGeneration = generation,
			workerKind = target.workerKind,
			startedAtMs = requestedAtMs,
			state = RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED,
			destructivePlan = null,
			updatedAtMs = requestedAtMs,
		)
	}

	suspend fun request(
		current: RetentionWorkExecutionReceiptEntity,
	): RetentionWorkExecutionReceiptEntity = when (current.state) {
		RetentionWorkExecutionReceiptEntity.STATE_OPEN -> {
			check(
				dao.compareAndSetState(
					executionId = current.executionId,
					expectedState = RetentionWorkExecutionReceiptEntity.STATE_OPEN,
					newState =
						RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED,
					updatedAtMs = maxOf(requestedAtMs, current.updatedAtMs),
				) == 1,
			) { "Unable to request exact retention execution cancellation" }
			requireNotNull(dao.get(current.executionId))
		}
		RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED -> current
		else -> error("Retention execution is not cancellable: ${current.executionId}")
	}

	if (exactWorkerKinds.isNotEmpty()) {
		dao.pendingCancellations(exactWorkerKinds).forEach { pending ->
			requested[pending.executionId] = pending
		}
	}
	exactTargets.forEach { target ->
		val latest = dao.latest(target.workRequestId)
		val cancellation = when {
			latest?.state == RetentionWorkExecutionReceiptEntity.STATE_OPEN ->
				request(requireNotNull(latest))
			latest?.state ==
				RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED ->
				request(requireNotNull(latest))
			target.workRequestId in exactActiveIds -> {
				val created = cancellationGeneration(target, latest)
				dao.insert(created)
				created
			}
			else -> null
		}
		if (cancellation != null) {
			requested[cancellation.executionId] = cancellation
		}
	}
	requested.values.map { it.toReceipt() }
}

suspend fun AppDatabase.confirmRetentionWorkExecutionCancellations(
	executionIds: Collection<String>,
	confirmedAtMs: Long,
): Int = withTransaction {
	require(confirmedAtMs >= 0L)
	val dao = retentionWorkExecutionReceiptDao()
	executionIds.onEach { require(it.isNotBlank()) }.distinct().sumOf { executionId ->
		val current = requireNotNull(dao.get(executionId)) {
			"Missing retention cancellation receipt: $executionId"
		}
		when (current.state) {
			RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED -> {
				check(
					dao.compareAndSetState(
						executionId = executionId,
						expectedState =
							RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED,
						newState = RetentionWorkExecutionReceiptEntity.STATE_ABANDONED,
						updatedAtMs = maxOf(confirmedAtMs, current.updatedAtMs),
					) == 1,
				) { "Unable to confirm exact retention execution cancellation" }
				1
			}
			RetentionWorkExecutionReceiptEntity.STATE_ABANDONED -> 0
			else -> error(
				"Retention cancellation confirmation requires CANCELLATION_REQUESTED: " +
					"$executionId=${current.state}",
			)
		}
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
