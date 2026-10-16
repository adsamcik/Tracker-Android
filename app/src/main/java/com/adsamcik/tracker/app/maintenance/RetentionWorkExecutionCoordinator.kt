package com.adsamcik.tracker.app.maintenance

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.RetentionFloorDestructivePlan
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionCompletionResult
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionContinuationResult
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionPlanResult
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionReceipt
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionStartResult
import com.adsamcik.tracker.shared.base.database.attachRetentionDestructivePlan
import com.adsamcik.tracker.shared.base.database.beginOrResumeRetentionWorkExecution
import com.adsamcik.tracker.shared.base.database.completeRetentionWorkExecution
import com.adsamcik.tracker.shared.base.database.retentionWorkExecutionContinuation
import javax.inject.Inject
import javax.inject.Singleton

/** Coordinates durable worker execution receipts through the existing database operations. */
@Singleton
class RetentionWorkExecutionCoordinator @Inject constructor() {
	/** Starts or resumes this exact WorkManager request and attempt, using UTC milliseconds. */
	suspend fun begin(
		database: AppDatabase,
		workRequestId: String,
		workerKind: String,
		runAttemptCount: Int,
		startedAtMs: Long,
	): RetentionWorkExecutionStartResult = database.beginOrResumeRetentionWorkExecution(
		workRequestId,
		workerKind,
		runAttemptCount,
		startedAtMs,
	)

	/** Attaches the destructive plan to its owned execution receipt before mutations. */
	suspend fun attachPlan(
		database: AppDatabase,
		receipt: RetentionWorkExecutionReceipt,
		plan: RetentionFloorDestructivePlan,
	): RetentionWorkExecutionPlanResult =
		database.attachRetentionDestructivePlan(receipt, plan)

	/** Checks that this execution still owns permission to continue after suspension. */
	suspend fun continuation(
		database: AppDatabase,
		receipt: RetentionWorkExecutionReceipt,
	): RetentionWorkExecutionContinuationResult =
		database.retentionWorkExecutionContinuation(receipt)

	/** Records completion of the exact receipt at the supplied UTC millisecond timestamp. */
	suspend fun complete(
		database: AppDatabase,
		receipt: RetentionWorkExecutionReceipt,
		completedAtMs: Long,
	): RetentionWorkExecutionCompletionResult =
		database.completeRetentionWorkExecution(receipt, completedAtMs)
}

/** Checks the execution receipt and reports the existing shared worker stop/defer outcomes. */
internal suspend fun RetentionWorkExecutionCoordinator.requireExecutionContinuation(
	database: AppDatabase,
	execution: RetentionWorkExecutionReceipt,
) {
	when (continuation(database, execution)) {
		RetentionWorkExecutionContinuationResult.Continue -> Unit
		RetentionWorkExecutionContinuationResult.CancellationRequested,
		RetentionWorkExecutionContinuationResult.AbandonedByCancellation,
		RetentionWorkExecutionContinuationResult.SupersededByFullDeletion,
		RetentionWorkExecutionContinuationResult.AlreadyCompleted,
		-> throw RetentionExecutionStoppedException()
		is RetentionWorkExecutionContinuationResult.Retryable ->
			throw RetentionExecutionDeferredException()
	}
}
