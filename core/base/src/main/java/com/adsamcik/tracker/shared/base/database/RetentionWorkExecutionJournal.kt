package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.RetentionWorkExecutionReceiptDao
import com.adsamcik.tracker.shared.base.database.data.RetentionWorkExecutionReceiptEntity

/**
 * Durable WorkManager attempt identity/generation, state, original destructive plan and UTC
 * millisecond timestamps.
 */
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

/**
 * Open or terminal retention attempt, cancellation/full-clear disposition, or a retryable
 * ownership failure.
 */
sealed interface RetentionWorkExecutionStartResult {
	/**
	 * The returned exact work receipt is open and may bind its original destructive plan.
	 */
	data class Open(
		val receipt: RetentionWorkExecutionReceipt,
	) : RetentionWorkExecutionStartResult

	/**
	 * The exact original retention attempt is durably completed and must not repeat destructive work.
	 */
	data object AlreadyCompleted : RetentionWorkExecutionStartResult
	/**
	 * Cancellation is durably requested; the original retention attempt must stop and settle its
	 * handoff.
	 */
	data object CancellationRequested : RetentionWorkExecutionStartResult
	/**
	 * The original retention attempt was abandoned after its cancellation handoff was confirmed.
	 */
	data object AbandonedByCancellation : RetentionWorkExecutionStartResult

	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class Retryable(
		val failure: RetentionWorkExecutionFailure,
	) : RetentionWorkExecutionStartResult

	/**
	 * A full-data clear superseded this exact retention attempt; it cannot resume old destructive
	 * work.
	 */
	data object SupersededByFullDeletion : RetentionWorkExecutionStartResult
}

/**
 * Precise prior-attempt, worker-kind, plan or cancellation-handoff mismatch preventing this
 * retention attempt.
 */
sealed interface RetentionWorkExecutionFailure {
	/**
	 * The previous generation is not open; retains its original work identity/generation/state for
	 * recovery.
	 */
	data class PreviousExecutionNotOpen(
		val workRequestId: String,
		val executionGeneration: Long,
		val state: String,
	) : RetentionWorkExecutionFailure

	/**
	 * The supplied worker kind differs from the original saved attempt kind.
	 */
	data class WorkerKindMismatch(
		val expectedWorkerKind: String,
		val actualWorkerKind: String,
	) : RetentionWorkExecutionFailure

	/**
	 * The execution already owns different immutable cutoffs, so a new plan cannot replace them.
	 */
	data class DestructivePlanMismatch(
		val executionId: String,
	) : RetentionWorkExecutionFailure

	/**
	 * Another execution owns the unfinished cancellation handoff and its retained destructive work.
	 */
	data class CancellationHandoffOwned(
		val ownerExecutionId: String,
		val ownerWorkerKind: String,
	) : RetentionWorkExecutionFailure
}

/**
 * Attached original destructive plan or a cancellation/ownership boundary preventing attachment.
 */
sealed interface RetentionWorkExecutionPlanResult {
	/**
	 * The original destructive plan is attached to the returned exact open work receipt.
	 */
	data class Attached(
		val receipt: RetentionWorkExecutionReceipt,
	) : RetentionWorkExecutionPlanResult

	/**
	 * Cancellation is durably requested; the original retention attempt must stop and settle its
	 * handoff.
	 */
	data object CancellationRequested : RetentionWorkExecutionPlanResult
	/**
	 * The original retention attempt was abandoned after its cancellation handoff was confirmed.
	 */
	data object AbandonedByCancellation : RetentionWorkExecutionPlanResult

	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class Retryable(
		val failure: RetentionWorkExecutionFailure,
	) : RetentionWorkExecutionPlanResult
}

/**
 * Completed attempt, cancellation/full-clear disposition or a retryable ownership failure.
 */
sealed interface RetentionWorkExecutionCompletionResult {
	/**
	 * The exact original retention attempt is durably completed and must not repeat destructive work.
	 */
	data object Completed : RetentionWorkExecutionCompletionResult
	/**
	 * Cancellation is durably requested; the original retention attempt must stop and settle its
	 * handoff.
	 */
	data object CancellationRequested : RetentionWorkExecutionCompletionResult
	/**
	 * The original retention attempt was abandoned after its cancellation handoff was confirmed.
	 */
	data object AbandonedByCancellation : RetentionWorkExecutionCompletionResult
	/**
	 * A full-data clear superseded this exact retention attempt; it cannot resume old destructive
	 * work.
	 */
	data object SupersededByFullDeletion : RetentionWorkExecutionCompletionResult
	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class Retryable(
		val failure: RetentionWorkExecutionFailure,
	) : RetentionWorkExecutionCompletionResult
}

/**
 * Whether the exact work receipt may continue, or is completed, cancelled, superseded or
 * temporarily blocked.
 */
sealed interface RetentionWorkExecutionContinuationResult {
	/**
	 * The exact original attempt is still open and may continue its saved destructive work.
	 */
	data object Continue : RetentionWorkExecutionContinuationResult
	/**
	 * Cancellation is durably requested; the original retention attempt must stop and settle its
	 * handoff.
	 */
	data object CancellationRequested : RetentionWorkExecutionContinuationResult
	/**
	 * The original retention attempt was abandoned after its cancellation handoff was confirmed.
	 */
	data object AbandonedByCancellation : RetentionWorkExecutionContinuationResult
	/**
	 * A full-data clear superseded this exact retention attempt; it cannot resume old destructive
	 * work.
	 */
	data object SupersededByFullDeletion : RetentionWorkExecutionContinuationResult
	/**
	 * The exact original retention attempt is durably completed and must not repeat destructive work.
	 */
	data object AlreadyCompleted : RetentionWorkExecutionContinuationResult

	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class Retryable(
		val failure: RetentionWorkExecutionFailure,
	) : RetentionWorkExecutionContinuationResult
}

/**
 * Exact WorkManager request identity and worker kind to cancel without affecting unrelated work.
 */
data class RetentionWorkCancellationTarget(
	val workRequestId: String,
	val workerKind: String,
) {
	init {
		require(workRequestId.isNotBlank())
		require(workerKind in RetentionFloorDestructivePlan.WORKER_KINDS)
	}
}

/**
 * Reads durable pending cancellation receipts for only the requested worker kinds.
 */
suspend fun AppDatabase.pendingRetentionWorkExecutionCancellations(
	workerKinds: Collection<String>,
): List<RetentionWorkExecutionReceipt> = withTransaction {
	val exactWorkerKinds = workerKinds.onEach {
		require(it in RetentionFloorDestructivePlan.WORKER_KINDS)
	}.distinct()
	if (exactWorkerKinds.isEmpty()) {
		emptyList()
	} else {
		retentionWorkExecutionReceiptDao()
			.pendingCancellations(exactWorkerKinds)
			.map { it.toReceipt() }
	}
}

/**
 * Creates or resumes the exact WorkManager attempt/generation, preserving cancellation and
 * prior-completion outcomes.
 */
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
	latest.terminalStartDisposition(runAttemptCount)?.let { return@withTransaction it }
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
	latest.existingStartDisposition(workRequestId, workerKind, runAttemptCount)?.let { return@withTransaction it }
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
	createNextRetentionWorkExecution(dao, latest, workRequestId, workerKind, startedAtMs, legacyFinal != null)
}

private fun RetentionWorkExecutionReceiptEntity?.terminalStartDisposition(
	runAttemptCount: Int,
): RetentionWorkExecutionStartResult? = when {
	this?.state == RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED ->
		RetentionWorkExecutionStartResult.CancellationRequested
	this?.state == RetentionWorkExecutionReceiptEntity.STATE_ABANDONED ->
		RetentionWorkExecutionStartResult.AbandonedByCancellation
	runAttemptCount > 0 && this?.state == RetentionWorkExecutionReceiptEntity.STATE_FINAL ->
		RetentionWorkExecutionStartResult.AlreadyCompleted
	runAttemptCount > 0 && this?.state == RetentionWorkExecutionReceiptEntity.STATE_SUPERSEDED ->
		RetentionWorkExecutionStartResult.SupersededByFullDeletion
	else -> null
}

private fun RetentionWorkExecutionReceiptEntity?.existingStartDisposition(
	requestedWorkRequestId: String,
	workerKind: String,
	runAttemptCount: Int,
): RetentionWorkExecutionStartResult? {
	if (this?.state == RetentionWorkExecutionReceiptEntity.STATE_OPEN) {
		return if (this.workerKind == workerKind) {
			RetentionWorkExecutionStartResult.Open(toReceipt())
		} else {
			RetentionWorkExecutionStartResult.Retryable(
				RetentionWorkExecutionFailure.WorkerKindMismatch(
					expectedWorkerKind = this.workerKind,
					actualWorkerKind = workerKind,
				),
			)
		}
	}
	if (runAttemptCount > 0 && this != null) {
		return RetentionWorkExecutionStartResult.Retryable(
			RetentionWorkExecutionFailure.PreviousExecutionNotOpen(
				workRequestId = requestedWorkRequestId,
				executionGeneration = executionGeneration,
				state = state,
			),
		)
	}
	return null
}

// One existing journal operation keeps the exact DAO, prior receipt and original creation context explicit.
private suspend fun AppDatabase.createNextRetentionWorkExecution(
	dao: RetentionWorkExecutionReceiptDao,
	latest: RetentionWorkExecutionReceiptEntity?,
	workRequestId: String,
	workerKind: String,
	startedAtMs: Long,
	hasLegacyFinal: Boolean,
): RetentionWorkExecutionStartResult {
	val generation = try {
		Math.addExact(latest?.executionGeneration ?: 0L, 1L)
	} catch (_: ArithmeticException) {
		return RetentionWorkExecutionStartResult.Retryable(
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
	if (hasLegacyFinal) {
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
	return RetentionWorkExecutionStartResult.Open(receipt.toReceipt())
}

/**
 * Atomically binds the original immutable cutoffs to the exact open work receipt; conflicting
 * plans remain explicit.
 */
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

/**
 * Completes the exact open retention attempt at the UTC millisecond time, honoring cancellation
 * and full-clear supersession.
 */
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

/**
 * Rechecks whether the exact saved work receipt may continue before further destructive work.
 */
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
	current.terminalContinuation()?.let { return@withTransaction it }

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


private fun RetentionWorkExecutionReceiptEntity.terminalContinuation(): RetentionWorkExecutionContinuationResult? =
	when (state) {
		RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED ->
			RetentionWorkExecutionContinuationResult.CancellationRequested
		RetentionWorkExecutionReceiptEntity.STATE_ABANDONED ->
			RetentionWorkExecutionContinuationResult.AbandonedByCancellation
		RetentionWorkExecutionReceiptEntity.STATE_SUPERSEDED ->
			RetentionWorkExecutionContinuationResult.SupersededByFullDeletion
		RetentionWorkExecutionReceiptEntity.STATE_FINAL,
		RetentionWorkExecutionReceiptEntity.STATE_ACKNOWLEDGED ->
			RetentionWorkExecutionContinuationResult.AlreadyCompleted
		else -> null
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

	if (exactWorkerKinds.isNotEmpty()) {
		dao.pendingCancellations(exactWorkerKinds).forEach { pending ->
			requested[pending.executionId] = pending
		}
	}
	exactTargets.forEach { target ->
		val cancellation = dao.requestTargetCancellation(target, exactActiveIds, requestedAtMs)
		if (cancellation != null) {
			requested[cancellation.executionId] = cancellation
		}
	}
	requested.values.map { it.toReceipt() }
}

private fun cancellationGeneration(
	target: RetentionWorkCancellationTarget,
	latest: RetentionWorkExecutionReceiptEntity?,
	requestedAtMs: Long,
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

private suspend fun RetentionWorkExecutionReceiptDao.requestCancellation(
	current: RetentionWorkExecutionReceiptEntity,
	requestedAtMs: Long,
): RetentionWorkExecutionReceiptEntity = when (current.state) {
	RetentionWorkExecutionReceiptEntity.STATE_OPEN -> {
		check(
			compareAndSetState(
				executionId = current.executionId,
				expectedState = RetentionWorkExecutionReceiptEntity.STATE_OPEN,
				newState =
					RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED,
				updatedAtMs = maxOf(requestedAtMs, current.updatedAtMs),
			) == 1,
		) { "Unable to request exact retention execution cancellation" }
		requireNotNull(get(current.executionId))
	}
	RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED -> current
	else -> error("Retention execution is not cancellable: ${current.executionId}")
}

private suspend fun RetentionWorkExecutionReceiptDao.requestTargetCancellation(
	target: RetentionWorkCancellationTarget,
	activeIds: Set<String>,
	requestedAtMs: Long,
): RetentionWorkExecutionReceiptEntity? {
	val latest = latest(target.workRequestId)
	return when {
		latest?.state == RetentionWorkExecutionReceiptEntity.STATE_OPEN ->
			requestCancellation(requireNotNull(latest), requestedAtMs)
		latest?.state ==
			RetentionWorkExecutionReceiptEntity.STATE_CANCELLATION_REQUESTED ->
			requestCancellation(requireNotNull(latest), requestedAtMs)
		target.workRequestId in activeIds -> {
			val created = cancellationGeneration(target, latest, requestedAtMs)
			insert(created)
			created
		}
		else -> null
	}
}

/**
 * Marks only the named execution receipts durably cancelled after the external cancellation
 * handshake is confirmed.
 */
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
