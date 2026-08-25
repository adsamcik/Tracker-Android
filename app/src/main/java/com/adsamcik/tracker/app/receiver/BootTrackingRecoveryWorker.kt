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
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryResult
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryScheduler
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainResult
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
	private val sourcePipelineRecovery: SourcePipelineRecovery,
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
				withReadyGenerationOperation = { generation, operation ->
					trackingStartupGate.withReadyGenerationOperation(generation, operation)
				},
				drainActivityAutomationEffects =
					sourcePipelineRecovery::drainActivityAutomationEffects,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			BootTrackingRecoveryOutcome.DURABLE_RETRY
		}
		return if (outcome.shouldRetry(runAttemptCount)) {
			Result.retry()
		} else {
			Result.success()
		}
	}
}

internal enum class BootTrackingRecoveryOutcome {
	COMPLETE,
	/** Startup and frozen-v27 recovery obligations retain retry ownership until terminal. */
	DURABLE_RETRY,
	/** Automatic control, including its durable outbox, receives a battery-bounded wake budget. */
	OPTIONAL_CONTROL_RETRY,
}

internal fun BootTrackingRecoveryOutcome.shouldRetry(runAttemptCount: Int): Boolean = when (this) {
	BootTrackingRecoveryOutcome.COMPLETE -> false
	BootTrackingRecoveryOutcome.DURABLE_RETRY -> true
	BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY ->
		runAttemptCount + 1 < OPTIONAL_CONTROL_MAX_ATTEMPTS
}

internal suspend fun runBootTrackingRecovery(
	startupGeneration: Long,
	currentGeneration: () -> Long,
	isReady: () -> Boolean,
	isSuppressed: () -> Boolean,
	reconcileStartup: suspend () -> TrackingStartupResult,
	initializeLocks: suspend () -> Unit,
	rearmAutomaticControl: suspend () -> AutomaticControlRecoveryResult,
	withReadyGenerationOperation: suspend (
		expectedGeneration: Long,
		operation: suspend () -> AutomaticControlRecoveryResult?,
	) -> AutomaticControlRecoveryResult? = { _, operation -> operation() },
	drainActivityAutomationEffects: suspend () -> ActivityAutomationDrainResult = {
		ActivityAutomationDrainResult.Complete(0, 0)
	},
): BootTrackingRecoveryOutcome {
	if (isSuppressed()) return BootTrackingRecoveryOutcome.COMPLETE
	if (currentGeneration() != startupGeneration) return BootTrackingRecoveryOutcome.COMPLETE
	val startup = try {
		reconcileStartup()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		return BootTrackingRecoveryOutcome.DURABLE_RETRY
	}
	when (startup) {
		is TrackingStartupResult.Ready -> Unit
		is TrackingStartupResult.RetryableFailure ->
			return BootTrackingRecoveryOutcome.DURABLE_RETRY
		is TrackingStartupResult.Blocked -> return BootTrackingRecoveryOutcome.COMPLETE
	}
	if (!isReady() || currentGeneration() != startupGeneration || isSuppressed()) {
		return BootTrackingRecoveryOutcome.COMPLETE
	}
	try {
		initializeLocks()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		return BootTrackingRecoveryOutcome.DURABLE_RETRY
	}
	if (!isReady() || currentGeneration() != startupGeneration || isSuppressed()) {
		return BootTrackingRecoveryOutcome.COMPLETE
	}
	val control = try {
		withReadyGenerationOperation(startupGeneration) {
			if (!isReady() || currentGeneration() != startupGeneration || isSuppressed()) {
				return@withReadyGenerationOperation null
			}
			try {
				rearmAutomaticControl()
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				AutomaticControlRecoveryResult.RETRYABLE
			}
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		return BootTrackingRecoveryOutcome.DURABLE_RETRY
	} ?: return BootTrackingRecoveryOutcome.COMPLETE
	if (!isReady() || currentGeneration() != startupGeneration || isSuppressed()) {
		return BootTrackingRecoveryOutcome.COMPLETE
	}
	val drain = try {
		drainActivityAutomationEffects()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		return BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY
	}
	return when {
		drain !is ActivityAutomationDrainResult.Complete ->
			BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY
		control == AutomaticControlRecoveryResult.RETRYABLE ->
			BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY
		else -> BootTrackingRecoveryOutcome.COMPLETE
	}
}

private const val OPTIONAL_CONTROL_MAX_ATTEMPTS = 3

@Singleton
class BootTrackingRecoveryScheduler @Inject constructor(
	@ApplicationContext private val context: Context,
) : AutomaticControlRecoveryScheduler {
	override fun enqueue() {
		val request = OneTimeWorkRequestBuilder<BootTrackingRecoveryWorker>()
			.setBackoffCriteria(
				BackoffPolicy.EXPONENTIAL,
				MINIMUM_BACKOFF_SECONDS,
				TimeUnit.SECONDS,
			)
			.build()
		WorkManager.getInstance(context).enqueueUniqueWork(
			UNIQUE_WORK_NAME,
			ExistingWorkPolicy.KEEP,
			request,
		)
	}

	companion object {
		internal const val UNIQUE_WORK_NAME = "TRACKER.BOOT_RECOVERY"
		private const val MINIMUM_BACKOFF_SECONDS = 30L
	}
}
