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
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryResult
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
				withReadyGenerationOperation = { generation, operation ->
					startupGate.withReadyGenerationOperation(generation, operation)
				},
				resumeWriters = writerQuiescer::resume,
				resumeActivityArbiter = {
					activityRegistrationArbiterProvider.get()
						.resumeAfterCollectedDataDeletion()
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
			// This unique epoch-fenced work is the only durable owner that can reopen process-local
			// writers after deletion. WorkManager backoff is intentionally retained until the epoch
			// changes or recovery reaches a terminal result.
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
	withReadyGenerationOperation: suspend (
		Long,
		suspend () -> AutomaticControlRecoveryResult?,
	) -> AutomaticControlRecoveryResult? =
		{ _, operation -> operation() },
	resumeWriters: () -> Unit,
	resumeActivityArbiter: suspend () -> Unit,
	reconcileAutomaticControl: suspend () -> AutomaticControlRecoveryResult,
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

	// These operations may open collected Room or schedule Room workers. Serialize their final
	// epoch/generation check and resume with deletion closure so a new deletion cannot win between
	// the check and either side effect.
	val controlRecovery = withReadyGenerationOperation(startupGeneration) {
		if (currentEpoch() != expectedEpoch || isDeletionClosed() ||
			!isStartupReady() || currentStartupGeneration() != startupGeneration
		) {
			null
		} else {
			resumeWriters()
			resumeActivityArbiter()
			reconcileAutomaticControl()
		}
	} ?: return PostDeletionRecoveryOutcome.RETRY

	// Deletion closure waits for the protected handoff above; if it wins after the handoff it owns
	// the matching quiesce/removal. Optional control containment is terminal while explicitly
	// transient failures retain retry work.
	if (currentEpoch() != expectedEpoch || isDeletionClosed() ||
		!isStartupReady() || currentStartupGeneration() != startupGeneration
	) {
		return PostDeletionRecoveryOutcome.RETRY
	}
	return when (controlRecovery) {
		AutomaticControlRecoveryResult.ACCEPTED,
		AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED ->
			PostDeletionRecoveryOutcome.COMPLETE
		AutomaticControlRecoveryResult.RETRYABLE -> PostDeletionRecoveryOutcome.RETRY
	}
}
