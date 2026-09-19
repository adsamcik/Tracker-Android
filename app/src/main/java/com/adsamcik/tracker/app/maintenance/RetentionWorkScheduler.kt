package com.adsamcik.tracker.app.maintenance

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.RetentionFloorDestructivePlan
import com.adsamcik.tracker.shared.base.database.RetentionFloorSettlementOperation
import com.adsamcik.tracker.shared.base.database.activeRetentionFloorSettlement
import com.adsamcik.tracker.shared.base.database.RetentionWorkCancellationTarget
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionReceipt
import com.adsamcik.tracker.shared.base.database.confirmRetentionWorkExecutionCancellations
import com.adsamcik.tracker.shared.base.database.pendingRetentionWorkExecutionCancellations
import com.adsamcik.tracker.shared.base.database.requestRetentionWorkExecutionCancellations
import com.adsamcik.tracker.shared.preferences.retention.ExactApprovedRetentionConfigRead
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class RetentionWorkCancellationDebt(
	val uniqueWorkNames: Set<String>,
	val executionIds: Set<String>,
	val failure: RetentionWorkCancellationFailure,
)

sealed interface RetentionWorkCancellationFailure {
	data class SnapshotUnavailable(
		val cause: Exception,
	) : RetentionWorkCancellationFailure

	data class CancellationApiFailed(
		val workRequestId: String,
		val cause: Exception,
	) : RetentionWorkCancellationFailure

	data class ConfirmationUnavailable(
		val cause: Exception,
	) : RetentionWorkCancellationFailure

	data class ConfirmationTimedOut(
		val activeWorkRequestIds: Set<String>,
	) : RetentionWorkCancellationFailure

	data class ActiveExecutionsRemain(
		val activeWorkRequestIds: Set<String>,
	) : RetentionWorkCancellationFailure
}

class RetentionWorkCancellationPendingException(
	val debt: RetentionWorkCancellationDebt,
) : IllegalStateException("Retention WorkManager cancellation remains pending")

class RetentionScheduleAuthorityUnavailableException(
	val authority: ExactApprovedRetentionConfigRead,
) : IllegalStateException("Exact approved retention schedule authority is unavailable") {
	init {
		require(authority !is ExactApprovedRetentionConfigRead.Approved)
	}
}

@Singleton
class RetentionWorkScheduler @Inject constructor(
	private val appDatabaseProvider: Provider<AppDatabase>,
	private val workManager: WorkManager,
) {
	private val schedulingMutex = Mutex()

	suspend fun ensureScheduled() {
		executeWithRecovery(enabled = true)
	}

	suspend fun cancel() {
		executeWithRecovery(enabled = false)
	}

	suspend fun reconcileCurrentPreference(retentionConfigStore: RetentionConfigStore) {
		schedulingMutex.withLock {
			val recoveryOwnerId = establishCancellationRecoveryOwner()
			reconcileAuthoritativePreference(retentionConfigStore)
			cancelCancellationRecoveryOwner(recoveryOwnerId)
		}
	}

	internal suspend fun recoverCurrentPreference(retentionConfigStore: RetentionConfigStore) {
		schedulingMutex.withLock {
			// Recovery requests intentionally carry no preference snapshot. Reading here prevents
			// an older WorkManager request from overwriting a newer approved schedule decision.
			reconcileAuthoritativePreference(retentionConfigStore)
		}
	}

	/**
	 * Quiescence already awaited and durably abandoned both unique-work identities.
	 */
	fun resumeAfterQuiescence() {
		workManager.enqueueUniquePeriodicWork(
			RetentionPipelineWorker.WORK_NAME,
			ExistingPeriodicWorkPolicy.KEEP,
			periodicRequest(),
		)
	}

	private suspend fun executeWithRecovery(enabled: Boolean) {
		schedulingMutex.withLock {
			val recoveryOwnerId = establishCancellationRecoveryOwner()
			reconcileSchedule(enabled)
			cancelCancellationRecoveryOwner(recoveryOwnerId)
		}
	}

	private suspend fun cancelCancellationRecoveryOwner(recoveryOwnerId: UUID) {
		workManager.cancelWorkById(recoveryOwnerId).await()
	}

	private suspend fun reconcileSchedule(enabled: Boolean) {
		if (enabled) {
			reconcilePendingCancellationReceipts()
			cancelWithDurableAbandonment(
				mapOf(
					RetentionPipelineWorker.LEGACY_WORK_NAME to
						RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
				),
			)
			workManager.enqueueUniquePeriodicWork(
				RetentionPipelineWorker.WORK_NAME,
				ExistingPeriodicWorkPolicy.KEEP,
				periodicRequest(),
			).await()
		} else {
			val protectedSettlement = ensureSettlementFinisher()
			cancelWithDurableAbandonment(
				mapOf(
					RetentionPipelineWorker.WORK_NAME to
						RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					RetentionPipelineWorker.LEGACY_WORK_NAME to
						RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
				),
			)
			val activeAfterCancellation = activeSettlement()
			if (
				activeAfterCancellation != null &&
				activeAfterCancellation.operationId != protectedSettlement?.operationId
			) {
				enqueueSettlementFinisher(activeAfterCancellation)
			}
		}
	}

	private suspend fun reconcileAuthoritativePreference(
		retentionConfigStore: RetentionConfigStore,
	) {
		retentionConfigStore.withExactApprovedConfigReconciliation { authority ->
			when (authority) {
				is ExactApprovedRetentionConfigRead.Approved -> reconcileSchedule(
					authority.configuration.autoCleanupEnabled ||
						authority.configuration.autoPurgeEnabled,
				)
				is ExactApprovedRetentionConfigRead.Pending,
				is ExactApprovedRetentionConfigRead.Invalid,
				is ExactApprovedRetentionConfigRead.Unavailable,
				-> throw RetentionScheduleAuthorityUnavailableException(authority)
			}
		}
	}

	private suspend fun establishCancellationRecoveryOwner(): UUID {
		// Establish durable ownership before any cancellation receipt can be written or any
		// cancellable WorkManager wait can begin. APPEND_OR_REPLACE installs a successor behind a
		// still-running recovery worker instead of silently accepting KEEP's no-op.
		val request = cancellationRecoveryRequest()
		val failure = withContext(NonCancellable) {
			try {
				withTimeout(RECOVERY_ENQUEUE_TIMEOUT_MS) {
					workManager.enqueueUniqueWork(
						RetentionCancellationRecoveryWorker.UNIQUE_WORK_NAME,
						ExistingWorkPolicy.APPEND_OR_REPLACE,
						request,
					).await()
				}
				null
			} catch (error: Exception) {
				error
			}
		}
		if (failure != null) {
			throw IllegalStateException(
				"Unable to establish retention cancellation recovery ownership",
				failure,
			)
		}
		return request.id
	}

	private suspend fun ensureSettlementFinisher(): RetentionFloorSettlementOperation? {
		val operation = activeSettlement() ?: return null
		enqueueSettlementFinisher(operation)
		return operation
	}

	private suspend fun activeSettlement(): RetentionFloorSettlementOperation? =
		appDatabaseProvider.get().activeRetentionFloorSettlement()

	private suspend fun enqueueSettlementFinisher(operation: RetentionFloorSettlementOperation) {
		workManager.enqueueUniqueWork(
			settlementFinisherWorkName(operation.operationId),
			ExistingWorkPolicy.KEEP,
			settlementFinisherRequest(operation),
		).await()
	}

	private suspend fun reconcilePendingCancellationReceipts() {
		val workerKinds = setOf(
			RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
			RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
		)
		val allUniqueWorkNames = setOf(
			RetentionPipelineWorker.WORK_NAME,
			RetentionPipelineWorker.LEGACY_WORK_NAME,
		)
		val database = try {
			appDatabaseProvider.get()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (error: Exception) {
			throw cancellationPending(
				uniqueWorkNames = allUniqueWorkNames,
				executionIds = emptySet(),
				failure = RetentionWorkCancellationFailure.SnapshotUnavailable(error),
			)
		}
		val pending = try {
			database.pendingRetentionWorkExecutionCancellations(workerKinds)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (error: Exception) {
			throw cancellationPending(
				uniqueWorkNames = allUniqueWorkNames,
				executionIds = emptySet(),
				failure = RetentionWorkCancellationFailure.SnapshotUnavailable(error),
			)
		}
		if (pending.isEmpty()) return

		val uniqueWorkNames = pending.mapTo(linkedSetOf()) {
			it.workerKind.uniqueWorkName()
		}
		val executionIds = pending.mapTo(linkedSetOf()) { it.executionId }
		pending.forEach { receipt ->
			reconcilePendingCancellationReceipt(
				receipt,
				uniqueWorkNames,
				executionIds,
			)
		}
		try {
			database.confirmRetentionWorkExecutionCancellations(
				executionIds = executionIds,
				confirmedAtMs = System.currentTimeMillis(),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (error: Exception) {
			throw cancellationPending(
				uniqueWorkNames,
				executionIds,
				RetentionWorkCancellationFailure.ConfirmationUnavailable(error),
			)
		}
	}

	private suspend fun reconcilePendingCancellationReceipt(
		receipt: RetentionWorkExecutionReceipt,
		uniqueWorkNames: Set<String>,
		executionIds: Set<String>,
	) {
		val requestId = try {
			UUID.fromString(receipt.workRequestId)
		} catch (error: IllegalArgumentException) {
			throw cancellationPending(
				uniqueWorkNames,
				executionIds,
				RetentionWorkCancellationFailure.SnapshotUnavailable(error),
			)
		}
		val initial = exactWorkInfo(requestId, uniqueWorkNames, executionIds)
		if (initial?.state in ACTIVE_WORK_STATES) {
			val cancellation = try {
				workManager.cancelWorkById(requestId)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (error: Exception) {
				throw cancellationPending(
					uniqueWorkNames,
					executionIds,
					RetentionWorkCancellationFailure.CancellationApiFailed(
						receipt.workRequestId,
						error,
					),
				)
			}
			try {
				cancellation.await()
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (error: Exception) {
				throw cancellationPending(
					uniqueWorkNames,
					executionIds,
					RetentionWorkCancellationFailure.CancellationApiFailed(
						receipt.workRequestId,
						error,
					),
				)
			}
		}
		val terminal = withTimeoutOrNull(CANCELLATION_CONFIRMATION_TIMEOUT_MS) {
			while (
				exactWorkInfo(requestId, uniqueWorkNames, executionIds)?.state in
				ACTIVE_WORK_STATES
			) {
				delay(CANCELLATION_CONFIRMATION_POLL_MS)
			}
			true
		}
		if (terminal != true) {
			throw cancellationPending(
				uniqueWorkNames,
				executionIds,
				RetentionWorkCancellationFailure.ConfirmationTimedOut(
					setOf(receipt.workRequestId),
				),
			)
		}
	}

	private suspend fun exactWorkInfo(
		requestId: UUID,
		uniqueWorkNames: Set<String>,
		executionIds: Set<String>,
	): WorkInfo? = try {
		workManager.getWorkInfoById(requestId).await()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (error: Exception) {
		throw cancellationPending(
			uniqueWorkNames,
			executionIds,
			RetentionWorkCancellationFailure.SnapshotUnavailable(error),
		)
	}

	private suspend fun cancelWithDurableAbandonment(
		schedules: Map<String, String>,
	) {
		val initial = workInfos(schedules)
		val active = initial.filter { it.workInfo.state in ACTIVE_WORK_STATES }
		val database = try {
			appDatabaseProvider.get()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (error: Exception) {
			throw cancellationPending(
				schedules,
				emptySet(),
				RetentionWorkCancellationFailure.SnapshotUnavailable(error),
			)
		}
		val requested = try {
			database.requestRetentionWorkExecutionCancellations(
				targets = initial.map {
					RetentionWorkCancellationTarget(
						workRequestId = it.workInfo.id.toString(),
						workerKind = it.workerKind,
					)
				},
				activeWorkRequestIds = active.map { it.workInfo.id.toString() },
				workerKinds = schedules.values,
				requestedAtMs = System.currentTimeMillis(),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (error: Exception) {
			throw cancellationPending(
				schedules,
				emptySet(),
				RetentionWorkCancellationFailure.SnapshotUnavailable(error),
			)
		}
		val requestedExecutionIds = requested.mapTo(linkedSetOf()) { it.executionId }
		val cancellationOperations = active.map { scheduled ->
			try {
				scheduled.workInfo.id to workManager.cancelWorkById(scheduled.workInfo.id)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (error: Exception) {
				throw cancellationPending(
					schedules,
					requestedExecutionIds,
					RetentionWorkCancellationFailure.CancellationApiFailed(
						scheduled.workInfo.id.toString(),
						error,
					),
				)
			}
		}
		cancellationOperations.forEach { (workRequestId, operation) ->
			try {
				operation.await()
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (error: Exception) {
				throw cancellationPending(
					schedules,
					requestedExecutionIds,
					RetentionWorkCancellationFailure.CancellationApiFailed(
						workRequestId.toString(),
						error,
					),
				)
			}
		}
		val confirmed = try {
			withTimeoutOrNull(CANCELLATION_CONFIRMATION_TIMEOUT_MS) {
				schedules.keys.forEach { uniqueWorkName ->
					workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
						.first { workInfos ->
							workInfos.none { it.state in ACTIVE_WORK_STATES }
						}
				}
				true
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (error: Exception) {
			throw cancellationPending(
				schedules,
				requestedExecutionIds,
				RetentionWorkCancellationFailure.ConfirmationUnavailable(error),
			)
		}
		if (confirmed != true) {
			val activeWorkRequestIds = try {
				workInfos(schedules)
					.filter { it.workInfo.state in ACTIVE_WORK_STATES }
					.mapTo(linkedSetOf()) { it.workInfo.id.toString() }
			} catch (_: RetentionWorkCancellationPendingException) {
				active.mapTo(linkedSetOf()) { it.workInfo.id.toString() }
			}
			throw cancellationPending(
				schedules,
				requestedExecutionIds,
				RetentionWorkCancellationFailure.ConfirmationTimedOut(
					activeWorkRequestIds,
				),
			)
		}
		// A running worker may have opened its receipt after the first database transaction.
		val finalSnapshot = workInfos(schedules)
		val finalActive = finalSnapshot.filter { it.workInfo.state in ACTIVE_WORK_STATES }
		val lateRequested = try {
			database.requestRetentionWorkExecutionCancellations(
				targets = finalSnapshot.map {
					RetentionWorkCancellationTarget(
						workRequestId = it.workInfo.id.toString(),
						workerKind = it.workerKind,
					)
				},
				activeWorkRequestIds = finalActive.map { it.workInfo.id.toString() },
				workerKinds = schedules.values,
				requestedAtMs = System.currentTimeMillis(),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (error: Exception) {
			throw cancellationPending(
				schedules,
				requestedExecutionIds,
				RetentionWorkCancellationFailure.SnapshotUnavailable(error),
			)
		}
		requestedExecutionIds += lateRequested.map { it.executionId }
		if (finalActive.isNotEmpty()) {
			throw cancellationPending(
				schedules,
				requestedExecutionIds,
				RetentionWorkCancellationFailure.ActiveExecutionsRemain(
					finalActive.mapTo(linkedSetOf()) { it.workInfo.id.toString() },
				),
			)
		}
		try {
			database.confirmRetentionWorkExecutionCancellations(
				executionIds = requestedExecutionIds,
				confirmedAtMs = System.currentTimeMillis(),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (error: Exception) {
			throw cancellationPending(
				schedules,
				requestedExecutionIds,
				RetentionWorkCancellationFailure.ConfirmationUnavailable(error),
			)
		}
	}

	private suspend fun workInfos(
		schedules: Map<String, String>,
	): List<ScheduledWorkInfo> = try {
		schedules.flatMap { (uniqueWorkName, workerKind) ->
			workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).first()
				.map { ScheduledWorkInfo(workerKind, it) }
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (error: RetentionWorkCancellationPendingException) {
		throw error
	} catch (error: Exception) {
		throw cancellationPending(
			schedules,
			emptySet(),
			RetentionWorkCancellationFailure.SnapshotUnavailable(error),
		)
	}

	private fun cancellationPending(
		schedules: Map<String, String>,
		executionIds: Set<String>,
		failure: RetentionWorkCancellationFailure,
	) = cancellationPending(schedules.keys, executionIds, failure)

	private fun cancellationPending(
		uniqueWorkNames: Set<String>,
		executionIds: Set<String>,
		failure: RetentionWorkCancellationFailure,
	) = RetentionWorkCancellationPendingException(
		RetentionWorkCancellationDebt(
			uniqueWorkNames = uniqueWorkNames,
			executionIds = executionIds,
			failure = failure,
		),
	)

	private fun periodicRequest() = PeriodicWorkRequestBuilder<RetentionPipelineWorker>(
		RETENTION_PERIOD_DAYS,
		TimeUnit.DAYS,
	).build()

	internal fun cancellationRecoveryRequest(): OneTimeWorkRequest =
		OneTimeWorkRequestBuilder<RetentionCancellationRecoveryWorker>()
			.setInitialDelay(
				RECOVERY_BACKOFF_SECONDS,
				TimeUnit.SECONDS,
			)
			.setBackoffCriteria(
				BackoffPolicy.EXPONENTIAL,
				RECOVERY_BACKOFF_SECONDS,
				TimeUnit.SECONDS,
			)
			.build()

	internal fun settlementFinisherRequest(
		operation: RetentionFloorSettlementOperation,
	): OneTimeWorkRequest {
		return OneTimeWorkRequestBuilder<RetentionPipelineWorker>()
			.setInputData(
				workDataOf(
					RetentionPipelineWorker.SETTLEMENT_OPERATION_ID_KEY to operation.operationId,
					RetentionPipelineWorker.SETTLEMENT_COLLECTED_DATA_EPOCH_KEY to
						operation.collectedDataEpoch,
				),
			)
			.setBackoffCriteria(
				BackoffPolicy.EXPONENTIAL,
				RECOVERY_BACKOFF_SECONDS,
				TimeUnit.SECONDS,
			)
			.build()
	}

	internal fun settlementFinisherWorkName(operationId: String): String {
		require(operationId.isNotBlank())
		return "$SETTLEMENT_FINISHER_WORK_NAME_PREFIX:$operationId"
	}

	private data class ScheduledWorkInfo(
		val workerKind: String,
		val workInfo: WorkInfo,
	)

	private fun String.uniqueWorkName(): String = when (this) {
		RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE ->
			RetentionPipelineWorker.WORK_NAME
		RetentionFloorDestructivePlan.WORKER_DATA_RETENTION ->
			RetentionPipelineWorker.LEGACY_WORK_NAME
		else -> error("Unsupported retention worker kind: $this")
	}

	companion object {
		private const val RETENTION_PERIOD_DAYS = 7L
		private const val CANCELLATION_CONFIRMATION_TIMEOUT_MS = 30_000L
		private const val CANCELLATION_CONFIRMATION_POLL_MS = 100L
		private const val RECOVERY_ENQUEUE_TIMEOUT_MS = 30_000L
		private const val SETTLEMENT_FINISHER_WORK_NAME_PREFIX =
			"APP.RETENTION_SETTLEMENT_FINISHER"
		internal const val RECOVERY_BACKOFF_SECONDS = 30L
		private val ACTIVE_WORK_STATES = setOf(
			WorkInfo.State.RUNNING,
			WorkInfo.State.ENQUEUED,
			WorkInfo.State.BLOCKED,
		)
	}
}
