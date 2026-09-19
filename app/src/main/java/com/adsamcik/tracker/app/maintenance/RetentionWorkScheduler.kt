package com.adsamcik.tracker.app.maintenance

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.abandonOpenRetentionWorkExecutions
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class RetentionWorkScheduler @Inject constructor(
	private val appDatabaseProvider: Provider<AppDatabase>,
	private val workManager: WorkManager,
) {
	private val schedulingMutex = Mutex()

	suspend fun ensureScheduled() {
		schedulingMutex.withLock {
			cancelWithDurableAbandonment(setOf(RetentionPipelineWorker.LEGACY_WORK_NAME))
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
				setOf(
					RetentionPipelineWorker.WORK_NAME,
					RetentionPipelineWorker.LEGACY_WORK_NAME,
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

	private suspend fun cancelWithDurableAbandonment(uniqueWorkNames: Set<String>) {
		val beforeCancellation = workRequestIds(uniqueWorkNames)
		abandon(beforeCancellation)
		uniqueWorkNames.map { workManager.cancelUniqueWork(it) }.forEach { it.await() }
		// Catch a receipt opened after the first WorkManager snapshot but before cancellation won.
		abandon(beforeCancellation + workRequestIds(uniqueWorkNames))
	}

	private suspend fun workRequestIds(uniqueWorkNames: Set<String>): Set<String> =
		uniqueWorkNames.flatMapTo(linkedSetOf<String>()) { uniqueWorkName ->
			workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).first()
				.map { it.id.toString() }
		}

	private suspend fun abandon(workRequestIds: Set<String>) {
		if (workRequestIds.isEmpty()) return
		appDatabaseProvider.get().abandonOpenRetentionWorkExecutions(
			workRequestIds = workRequestIds,
			abandonedAtMs = System.currentTimeMillis(),
		)
	}

	private fun periodicRequest() = PeriodicWorkRequestBuilder<RetentionPipelineWorker>(
		RETENTION_PERIOD_DAYS,
		TimeUnit.DAYS,
	).build()

	private companion object {
		const val RETENTION_PERIOD_DAYS = 7L
	}
}
