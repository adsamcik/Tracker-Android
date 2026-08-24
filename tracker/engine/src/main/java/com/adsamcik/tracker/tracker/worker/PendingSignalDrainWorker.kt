package com.adsamcik.tracker.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider

@HiltWorker
class PendingSignalDrainWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParameters: WorkerParameters,
	private val persistenceProcessorProvider: Provider<PersistenceProcessor>,
	private val trackingStartupGate: TrackingStartupGate,
) : CoroutineWorker(context, workerParameters) {
	override suspend fun doWork(): Result {
		val expectedGeneration = inputData.getLong(STARTUP_GENERATION_KEY, MISSING_GENERATION)
		return when (runPendingSignalDrain(
			expectedGeneration = expectedGeneration,
			currentGeneration = { trackingStartupGate.currentGeneration },
			isReady = { trackingStartupGate.isReady },
			reconcileStartup = { trackingStartupGate.reconcile() },
			drain = { verifyCollectedDataAccess ->
				persistenceProcessorProvider.get().drainOrphanedSignals(verifyCollectedDataAccess)
			},
		)) {
			PendingSignalDrainWorkOutcome.COMPLETE -> Result.success()
			PendingSignalDrainWorkOutcome.RETRY -> Result.retry()
			PendingSignalDrainWorkOutcome.FAILED -> Result.failure()
		}
	}

	companion object {
		internal const val STARTUP_GENERATION_KEY = "tracking_startup_generation"
		private const val MISSING_GENERATION = -1L
	}
}

internal enum class PendingSignalDrainWorkOutcome { COMPLETE, RETRY, FAILED }

internal suspend fun runPendingSignalDrain(
	expectedGeneration: Long,
	currentGeneration: () -> Long,
	isReady: () -> Boolean,
	reconcileStartup: suspend () -> TrackingStartupResult,
	drain: suspend (verifyCollectedDataAccess: () -> Unit) -> Boolean,
): PendingSignalDrainWorkOutcome {
	if (expectedGeneration < 0L) return PendingSignalDrainWorkOutcome.FAILED
	if (currentGeneration() != expectedGeneration) return PendingSignalDrainWorkOutcome.COMPLETE
	return when (reconcileStartup()) {
		is TrackingStartupResult.Ready -> {
			if (!isReady() || currentGeneration() != expectedGeneration) {
				PendingSignalDrainWorkOutcome.COMPLETE
			} else {
				try {
					val verifyCollectedDataAccess = {
						if (!isReady() || currentGeneration() != expectedGeneration) {
							throw PendingSignalDrainGenerationChangedException
						}
					}
					val drained = drain(verifyCollectedDataAccess)
					verifyCollectedDataAccess()
					if (drained) {
						PendingSignalDrainWorkOutcome.COMPLETE
					} else {
						PendingSignalDrainWorkOutcome.RETRY
					}
				} catch (_: PendingSignalDrainGenerationChangedException) {
					PendingSignalDrainWorkOutcome.COMPLETE
				}
			}
		}
		is TrackingStartupResult.Blocked -> PendingSignalDrainWorkOutcome.COMPLETE
		is TrackingStartupResult.RetryableFailure -> PendingSignalDrainWorkOutcome.RETRY
	}
}

private object PendingSignalDrainGenerationChangedException : IllegalStateException(
	"Tracking startup generation changed during pending-signal drain",
)
