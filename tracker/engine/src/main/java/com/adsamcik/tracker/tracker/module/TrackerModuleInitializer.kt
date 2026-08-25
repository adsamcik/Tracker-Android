package com.adsamcik.tracker.tracker.module

import android.content.Context
import dev.tracebox.Tracebox
import com.adsamcik.tracker.shared.base.Process
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainResult
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEpochAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.inject.Inject

/**
 * Initializes tracker module
 */
class TrackerModuleInitializer @Inject constructor(
	@ApplicationContext private val context: Context,
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val lockManager: LockManager,
	private val trackingStartupGuard: TrackingStartupGuard,
	private val trackingStartupGate: TrackingStartupGate,
	private val sourcePipelineRecovery: SourcePipelineRecovery,
	private val activityAutomationEpochAuthority: ActivityAutomationEpochAuthority,
	private val automaticControlRecoveryScheduler: AutomaticControlRecoveryScheduler,
) : ModuleInitializer {
	override val priority: Int = 20

	override fun initialize() {
		if (!Process.isMainProcess(context)) return
		if (trackingStartupGuard.isAutoRecoverySuppressed(context)) return

		applicationScope.launch {
			if (trackingStartupGate.reconcile() !is TrackingStartupResult.Ready) return@launch
			if (trackingStartupGuard.isAutoRecoverySuppressed(context)) return@launch
			lockManager.initializeFromPersistence(context)
			activityAutomationEpochAuthority.startRuntimeBoundaryMonitoring(applicationScope)
			BackgroundTrackingApi.initialize(context)
			driveActivityAutomationEffectDrain(
				authorityReady = BackgroundTrackingApi.activityAutomationAuthorityReady,
				drainRequired = sourcePipelineRecovery.activityAutomationDrainRequired,
				onRetryGenerationExhausted = {
					automaticControlRecoveryScheduler.enqueue()
					sourcePipelineRecovery.suspendActivityAutomationRetryGeneration()
				},
				drain = sourcePipelineRecovery::drainActivityAutomationEffects,
			)
		}
	}
}

/**
 * Single process-owned retry driver for durable Activity automation effects. The two flows are
 * deliberately separate: authority can close without erasing the durable pending-work hint, and a
 * later coherent revision resumes from the oldest effect. Delays are only in-process retries; a
 * future process probes the durable outbox again because its hint starts true.
 */
internal suspend fun driveActivityAutomationEffectDrain(
	authorityReady: Flow<Boolean>,
	drainRequired: Flow<Boolean>,
	initialRetryDelayMillis: Long = 500L,
	maxRetryDelayMillis: Long = 30_000L,
	maxDrainAttempts: Int = 8,
	maxDrainElapsedMillis: Long = 2 * 60_000L,
	elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
	onRetryGenerationExhausted: () -> Unit,
	drain: suspend () -> ActivityAutomationDrainResult,
) {
	require(initialRetryDelayMillis > 0L)
	require(maxRetryDelayMillis >= initialRetryDelayMillis)
	require(maxDrainAttempts > 0)
	require(maxDrainElapsedMillis > 0L)
	combine(authorityReady, drainRequired) { authority, pending -> authority && pending }
		.distinctUntilChanged()
		.collectLatest { shouldDrain ->
			if (!shouldDrain) return@collectLatest
			val startedAtMillis = elapsedRealtimeMillis()
			var attemptCount = 0
			var retryDelayMillis = initialRetryDelayMillis
			while (
				attemptCount < maxDrainAttempts &&
				elapsedRealtimeMillis().elapsedSince(startedAtMillis) < maxDrainElapsedMillis
			) {
				attemptCount += 1
				val result = try {
					drain()
				} catch (cancellation: CancellationException) {
					throw cancellation
				} catch (failure: Exception) {
					Tracebox.log.error(failure, "Activity automation outbox drain failed")
					null
				}
				when (result) {
					is ActivityAutomationDrainResult.Complete -> return@collectLatest
					is ActivityAutomationDrainResult.ProjectionDeferred -> {
						// WAL remains truth; unique WorkManager work gets one battery-bounded retry
						// generation before this process waits for a later relevant state change.
						onRetryGenerationExhausted()
						return@collectLatest
					}
					is ActivityAutomationDrainResult.MorePending -> {
						retryDelayMillis = initialRetryDelayMillis
						yield()
					}
					is ActivityAutomationDrainResult.Retryable,
					null,
					-> {
						if (attemptCount >= maxDrainAttempts) break
						val remainingMillis = maxDrainElapsedMillis -
							elapsedRealtimeMillis().elapsedSince(startedAtMillis)
						if (remainingMillis <= 0L) break
						delay(retryDelayMillis.coerceAtMost(remainingMillis))
						retryDelayMillis = (retryDelayMillis * 2)
							.coerceAtMost(maxRetryDelayMillis)
					}
				}
			}
			onRetryGenerationExhausted()
		}
}

private fun Long.elapsedSince(startedAtMillis: Long): Long =
	(this - startedAtMillis).coerceAtLeast(0L)
