package com.adsamcik.tracker.app.receiver

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@HiltWorker
class BootTrackingRecoveryWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val trackingStartupGate: TrackingStartupGate,
	private val trackingStartupGuard: TrackingStartupGuard,
	private val lockManager: LockManager,
) : CoroutineWorker(appContext, params) {
	override suspend fun doWork(): Result {
		val outcome = try {
			runBootTrackingRecovery(
				startupGeneration = trackingStartupGate.currentGeneration,
				currentGeneration = { trackingStartupGate.currentGeneration },
				isReady = { trackingStartupGate.isReady },
				isSuppressed = {
					trackingStartupGuard.isAutoRecoverySuppressed(applicationContext)
				},
				reconcileStartup = { trackingStartupGate.reconcile() },
				initializeLocks = { lockManager.initializeFromPersistence(applicationContext) },
				rearmAutomaticControl = {
					BackgroundTrackingApi.reconcileAutomaticControlDemandAfterStartup(
						applicationContext,
					)
				},
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			BootTrackingRecoveryOutcome.RETRY
		}
		return when (outcome) {
			BootTrackingRecoveryOutcome.COMPLETE -> Result.success()
			// The unique WorkManager record is the durable retry owner. A Boolean false means the
			// enabled Activity demand is not yet accepted; ending the work would silently kill
			// automatic continuity without a persisted terminal/degraded product state.
			BootTrackingRecoveryOutcome.RETRY -> Result.retry()
		}
	}
}

internal enum class BootTrackingRecoveryOutcome { COMPLETE, RETRY }

internal suspend fun runBootTrackingRecovery(
	startupGeneration: Long,
	currentGeneration: () -> Long,
	isReady: () -> Boolean,
	isSuppressed: () -> Boolean,
	reconcileStartup: suspend () -> TrackingStartupResult,
	initializeLocks: suspend () -> Unit,
	rearmAutomaticControl: suspend () -> Boolean,
): BootTrackingRecoveryOutcome {
	if (isSuppressed()) return BootTrackingRecoveryOutcome.COMPLETE
	if (currentGeneration() != startupGeneration) return BootTrackingRecoveryOutcome.COMPLETE
	when (reconcileStartup()) {
		is TrackingStartupResult.Ready -> Unit
		is TrackingStartupResult.RetryableFailure -> return BootTrackingRecoveryOutcome.RETRY
		is TrackingStartupResult.Blocked -> return BootTrackingRecoveryOutcome.COMPLETE
	}
	if (!isReady() || currentGeneration() != startupGeneration || isSuppressed()) {
		return BootTrackingRecoveryOutcome.COMPLETE
	}
	initializeLocks()
	if (!isReady() || currentGeneration() != startupGeneration || isSuppressed()) {
		return BootTrackingRecoveryOutcome.COMPLETE
	}
	return if (rearmAutomaticControl()) {
		BootTrackingRecoveryOutcome.COMPLETE
	} else {
		BootTrackingRecoveryOutcome.RETRY
	}
}

@Singleton
class BootTrackingRecoveryScheduler @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	fun enqueue() {
		val request = OneTimeWorkRequestBuilder<BootTrackingRecoveryWorker>()
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
		internal const val UNIQUE_WORK_NAME = "TRACKER.BOOT_RECOVERY"
		private const val MINIMUM_BACKOFF_SECONDS = 30L
	}
}
