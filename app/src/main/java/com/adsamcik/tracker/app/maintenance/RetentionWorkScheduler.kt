package com.adsamcik.tracker.app.maintenance

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.RetentionFloorDestructivePlan
import com.adsamcik.tracker.shared.base.database.RetentionWorkCancellationTarget
import com.adsamcik.tracker.shared.base.database.confirmRetentionWorkExecutionCancellations
import com.adsamcik.tracker.shared.base.database.requestRetentionWorkExecutionCancellations
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

@Singleton
class RetentionWorkScheduler @Inject constructor(
	private val appDatabaseProvider: Provider<AppDatabase>,
	private val workManager: WorkManager,
) {
	private val schedulingMutex = Mutex()

	suspend fun ensureScheduled() {
		schedulingMutex.withLock {
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
		}
	}

	suspend fun cancel() {
		schedulingMutex.withLock {
			cancelWithDurableAbandonment(
				mapOf(
					RetentionPipelineWorker.WORK_NAME to
						RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
					RetentionPipelineWorker.LEGACY_WORK_NAME to
						RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
				),
			)
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

	private suspend fun cancelWithDurableAbandonment(
		schedules: Map<String, String>,
	) {
		val initial = workInfos(schedules)
		val active = initial.filter { it.workInfo.state in ACTIVE_WORK_STATES }
		val database = appDatabaseProvider.get()
		val requested = database.requestRetentionWorkExecutionCancellations(
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
		val lateRequested = database.requestRetentionWorkExecutionCancellations(
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
		database.confirmRetentionWorkExecutionCancellations(
			executionIds = requestedExecutionIds,
			confirmedAtMs = System.currentTimeMillis(),
		)
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
	) = RetentionWorkCancellationPendingException(
		RetentionWorkCancellationDebt(
			uniqueWorkNames = schedules.keys,
			executionIds = executionIds,
			failure = failure,
		),
	)

	private fun periodicRequest() = PeriodicWorkRequestBuilder<RetentionPipelineWorker>(
		RETENTION_PERIOD_DAYS,
		TimeUnit.DAYS,
	).build()

	private data class ScheduledWorkInfo(
		val workerKind: String,
		val workInfo: WorkInfo,
	)

	private companion object {
		const val RETENTION_PERIOD_DAYS = 7L
		const val CANCELLATION_CONFIRMATION_TIMEOUT_MS = 30_000L
		val ACTIVE_WORK_STATES = setOf(
			WorkInfo.State.RUNNING,
			WorkInfo.State.ENQUEUED,
			WorkInfo.State.BLOCKED,
		)
	}
}
