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

@Singleton
class RetentionWorkExecutionCoordinator @Inject constructor() {
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

	suspend fun attachPlan(
		database: AppDatabase,
		receipt: RetentionWorkExecutionReceipt,
		plan: RetentionFloorDestructivePlan,
	): RetentionWorkExecutionPlanResult =
		database.attachRetentionDestructivePlan(receipt, plan)

	suspend fun continuation(
		database: AppDatabase,
		receipt: RetentionWorkExecutionReceipt,
	): RetentionWorkExecutionContinuationResult =
		database.retentionWorkExecutionContinuation(receipt)

	suspend fun complete(
		database: AppDatabase,
		receipt: RetentionWorkExecutionReceipt,
		completedAtMs: Long,
	): RetentionWorkExecutionCompletionResult =
		database.completeRetentionWorkExecution(receipt, completedAtMs)
}
