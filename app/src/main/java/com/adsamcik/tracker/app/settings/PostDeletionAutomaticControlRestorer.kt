package com.adsamcik.tracker.app.settings

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.app.startup.TrackingStartupDeletionBarrier
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Durable retry owner for reopening collected-data writers and automatic Activity control.
 *
 * Scheduling is safe while the process deletion barrier is still closed. Each worker invocation
 * makes one bounded attempt, and WorkManager owns exponential retry across process death.
 */
@Singleton
class PostDeletionAutomaticControlRestorer @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	fun schedule(collectedDataEpoch: Long) {
		require(collectedDataEpoch >= 0L)
		val request = OneTimeWorkRequestBuilder<PostDeletionRecoveryWorker>()
			.setInputData(workDataOf(PostDeletionRecoveryWorker.COLLECTED_DATA_EPOCH_KEY to collectedDataEpoch))
			.setBackoffCriteria(
				BackoffPolicy.EXPONENTIAL,
				MINIMUM_BACKOFF_SECONDS,
				TimeUnit.SECONDS,
			)
			.build()
		WorkManager.getInstance(context).enqueueUniqueWork(
			UNIQUE_WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			request,
		)
	}

	companion object {
		internal const val UNIQUE_WORK_NAME = "TRACKER.POST_DELETION_RECOVERY"
		private const val MINIMUM_BACKOFF_SECONDS = 30L
	}
}

@HiltWorker
class PostDeletionRecoveryWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val startupGate: TrackingStartupGate,
	private val deletionBarrier: TrackingStartupDeletionBarrier,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val writerQuiescer: CollectedDataWriterQuiescer,
	private val activityRegistrationArbiterProvider: Provider<ActivityRegistrationArbiter>,
) : CoroutineWorker(appContext, params) {
	override suspend fun doWork(): Result {
		val expectedEpoch = inputData.getLong(COLLECTED_DATA_EPOCH_KEY, MISSING_EPOCH)
		if (expectedEpoch < 0L) return Result.failure()
		val startupGeneration = startupGate.currentGeneration
		val outcome = try {
			runPostDeletionRecovery(
				expectedEpoch = expectedEpoch,
				currentEpoch = { lifecycleStore.snapshot().epoch },
				startupGeneration = startupGeneration,
				currentStartupGeneration = { startupGate.currentGeneration },
				isDeletionClosed = { deletionBarrier.isClosed },
				isStartupReady = { startupGate.isReady },
				reconcileStartup = { startupGate.reconcile() },
				resumeWriters = writerQuiescer::resume,
				resumeActivityArbiter = {
					activityRegistrationArbiterProvider.get()
						.resumeAfterCollectedDataDeletion()
						.let { Unit }
				},
				reconcileAutomaticControl = {
					BackgroundTrackingApi.reconcileAutomaticControlDemandAfterStartup(
						applicationContext,
					)
				},
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			PostDeletionRecoveryOutcome.RETRY
		}
		return when (outcome) {
			PostDeletionRecoveryOutcome.COMPLETE -> Result.success()
			PostDeletionRecoveryOutcome.RETRY -> Result.retry()
		}
	}

	companion object {
		internal const val COLLECTED_DATA_EPOCH_KEY = "collected_data_epoch"
		private const val MISSING_EPOCH = -1L
	}
}

internal enum class PostDeletionRecoveryOutcome { COMPLETE, RETRY }

internal suspend fun runPostDeletionRecovery(
	expectedEpoch: Long,
	currentEpoch: suspend () -> Long,
	startupGeneration: Long,
	currentStartupGeneration: () -> Long,
	isDeletionClosed: () -> Boolean,
	isStartupReady: () -> Boolean,
	reconcileStartup: suspend () -> TrackingStartupResult,
	resumeWriters: () -> Unit,
	resumeActivityArbiter: suspend () -> Unit,
	reconcileAutomaticControl: suspend () -> Boolean,
): PostDeletionRecoveryOutcome {
	if (currentEpoch() != expectedEpoch) return PostDeletionRecoveryOutcome.COMPLETE
	if (isDeletionClosed()) return PostDeletionRecoveryOutcome.RETRY
	when (reconcileStartup()) {
		is TrackingStartupResult.Ready -> Unit
		is TrackingStartupResult.RetryableFailure -> return PostDeletionRecoveryOutcome.RETRY
		is TrackingStartupResult.Blocked -> return PostDeletionRecoveryOutcome.COMPLETE
	}
	if (!isStartupReady() || isDeletionClosed() ||
		currentStartupGeneration() != startupGeneration
	) {
		return PostDeletionRecoveryOutcome.RETRY
	}
	if (currentEpoch() != expectedEpoch) return PostDeletionRecoveryOutcome.COMPLETE

	// These operations may open collected Room or schedule Room workers, so they occur only after
	// the exact startup generation is Ready. The arbiter is unpaused before automatic demand repair;
	// the repair's Boolean is true only for an accepted enabled demand or a terminally disabled one.
	resumeWriters()
	resumeActivityArbiter()
	if (currentEpoch() != expectedEpoch || isDeletionClosed() ||
		!isStartupReady() || currentStartupGeneration() != startupGeneration
	) {
		return PostDeletionRecoveryOutcome.RETRY
	}
	return if (reconcileAutomaticControl()) {
		PostDeletionRecoveryOutcome.COMPLETE
	} else {
		PostDeletionRecoveryOutcome.RETRY
	}
}
