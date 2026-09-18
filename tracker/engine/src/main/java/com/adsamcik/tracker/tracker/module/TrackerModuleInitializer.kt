package com.adsamcik.tracker.tracker.module

import android.content.Context
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticFailureCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticLog
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticWarningCode
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticFailureReason
import com.adsamcik.tracker.shared.base.Process
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityState
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationCoordinator
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationResult
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationResult
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.resilience.TrackingAutoRecoveryAuthorization
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderLifecycleOwner
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderRegistrationResult
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
import java.util.concurrent.atomic.AtomicBoolean
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
	private val ambientStepsProviderLifecycleOwner: AmbientStepsProviderLifecycleOwner,
	private val retentionAuthorityProducer: RetentionAuthorityProducer,
	private val sourcePolicyRevisionReconciliationCoordinator:
		SourcePolicyRevisionReconciliationCoordinator,
) : ModuleInitializer {
	override val priority: Int = 20
	private val initializationGate = TrackerModuleInitializationGate()

	override fun initialize() {
		if (!Process.isMainProcess(context)) return
		if (!initializationGate.tryStart()) return

		applicationScope.launch {
			while (true) {
				try {
					handoffTrackerInitializationAfterAuthorization(
						reconcileStartup = { trackingStartupGate.reconcile() },
						currentReadyGeneration = { trackingStartupGate.currentGeneration },
						awaitAuthorizationAfterReady = {
							trackingStartupGuard.awaitAutoRecoveryAuthorizationAfterStartupReady(context)
						},
						awaitNextReady = trackingStartupGate::awaitReady,
						withReadyGenerationOperation = { generation, operation ->
							trackingStartupGate.withReadyGenerationOperation(generation, operation)
						},
						releaseForReadyGeneration = {
							trackingStartupGuard.releaseAutoRecoveryForReadyGeneration(context)
						},
						handoff = { handoffAuthorization ->
							val readyGeneration = trackingStartupGate.currentGeneration
							when (val authority = reconcileTrackerStartupAuthority(
								reconcileSourcePolicy = {
									sourcePolicyRevisionReconciliationCoordinator
										.reconcileCurrentPolicyRevisionWithinReadyOperation(
											readyGeneration,
										)
								},
								reconcileRetention =
									retentionAuthorityProducer::reconcileCurrentSettings,
								retireAmbientSteps =
									ambientStepsProviderLifecycleOwner::
										retireAfterRetentionAuthorityFailure,
							)) {
								TrackerStartupAuthorityResult.Complete -> Unit
								else -> throw TrackerStartupAuthorityPendingException(authority)
							}
							lockManager.initializeFromPersistence(context)
							activityAutomationEpochAuthority
								.startRuntimeBoundaryMonitoring(applicationScope)
							initializeTrackerAutomaticControlAfterAuthorization(
								authorization = handoffAuthorization,
								initialize = { BackgroundTrackingApi.initialize(context) },
							)
						},
					) ?: return@launch
					break
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: TrackerStartupAuthorityPendingException) {
					TrackerDiagnosticLog.warn(
						TrackerDiagnosticWarningCode
							.AMBIENT_STEPS_PROVIDER_RECONCILIATION_FAILED,
					)
					delay(STARTUP_AUTHORITY_RETRY_DELAY_MS)
				}
			}
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

internal suspend fun reconcileTrackerStartupAuthority(
	reconcileSourcePolicy: suspend () -> SourcePolicyRevisionReconciliationResult,
	reconcileRetention: suspend () -> List<RetentionAuthorityResult>,
	retireAmbientSteps: suspend () -> AmbientStepsSettingsReconciliationResult,
): TrackerStartupAuthorityResult {
	val sourcePolicy = reconcileSourcePolicy()
	val snapshot = when (sourcePolicy) {
		is SourcePolicyRevisionReconciliationResult.Complete -> sourcePolicy.snapshot
		is SourcePolicyRevisionReconciliationResult.Retryable,
		is SourcePolicyRevisionReconciliationResult.Unverifiable,
		-> return TrackerStartupAuthorityResult.SourcePolicyDebt(sourcePolicy)
	}
	val stepsPolicy = snapshot[TrackingSourceComponent.STEPS]
	val expectedStepsState = if (
		stepsPolicy.ambientConsentEpoch != null &&
		stepsPolicy.ambientPersistenceEligible
	) {
		RetentionAuthorityState.ACTIVE
	} else {
		RetentionAuthorityState.REVOKED
	}
	return reconcileRetentionAuthorityAtStartup(
		expectedAmbientStepsState = expectedStepsState,
		reconcileRetention = reconcileRetention,
		retireAmbientSteps = retireAmbientSteps,
	)
}

internal suspend fun reconcileRetentionAuthorityAtStartup(
	expectedAmbientStepsState: RetentionAuthorityState,
	reconcileRetention: suspend () -> List<RetentionAuthorityResult>,
	retireAmbientSteps: suspend () -> AmbientStepsSettingsReconciliationResult,
): TrackerStartupAuthorityResult {
	val results = try {
		reconcileRetention()
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Exception) {
		listOf(
			RetentionAuthorityResult.Unavailable(
				source = TrackingSourceComponent.STEPS,
				scope = RetentionAuthorityScope.LIVE_AMBIENT,
				reason = RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
			),
		)
	}
	val ambientStepsResults = results.filter { result ->
		result.source == TrackingSourceComponent.STEPS &&
			result.scope == RetentionAuthorityScope.LIVE_AMBIENT
	}
	val failure = when {
		ambientStepsResults.size != 1 ->
			TrackerStartupRetentionFailure.ResultSetInvalid
		ambientStepsResults.single() is RetentionAuthorityResult.Unavailable ->
			TrackerStartupRetentionFailure.RetentionUnavailable(
				ambientStepsResults.single() as RetentionAuthorityResult.Unavailable,
			)
		ambientStepsResults.single().stateOrNull() != expectedAmbientStepsState ->
			TrackerStartupRetentionFailure.StateMismatch(
				result = ambientStepsResults.single(),
				expected = expectedAmbientStepsState,
			)
		else -> return TrackerStartupAuthorityResult.Complete
	}
	val retirement = try {
		retireAmbientSteps()
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Exception) {
		AmbientStepsSettingsReconciliationResult(
			complete = false,
			operational = false,
			failure = com.adsamcik.tracker.tracker.api
				.AmbientStepsSettingsReconciliationFailure.PROVIDER_REMOVAL_FAILED,
			retryable = true,
		)
	}
	if (!retirement.complete || retirement.operational) {
		val debt = TrackerStartupRetentionDebt(failure, retirement)
		return if (!retirement.complete && retirement.retryable) {
			TrackerStartupAuthorityResult.RetryableRetentionDebt(debt)
		} else {
			TrackerStartupAuthorityResult.UnverifiableRetentionDebt(debt)
		}
	}
	val debt = TrackerStartupRetentionDebt(failure, retirement)
	return if (failure.retryable) {
		TrackerStartupAuthorityResult.RetryableRetentionDebt(debt)
	} else {
		TrackerStartupAuthorityResult.UnverifiableRetentionDebt(debt)
	}
}

internal sealed interface TrackerStartupAuthorityResult {
	data object Complete : TrackerStartupAuthorityResult

	data class SourcePolicyDebt(
		val reconciliation: SourcePolicyRevisionReconciliationResult,
	) : TrackerStartupAuthorityResult {
		init {
			require(reconciliation !is SourcePolicyRevisionReconciliationResult.Complete)
		}
	}

	data class RetryableRetentionDebt(
		val debt: TrackerStartupRetentionDebt,
	) : TrackerStartupAuthorityResult

	data class UnverifiableRetentionDebt(
		val debt: TrackerStartupRetentionDebt,
	) : TrackerStartupAuthorityResult
}

internal data class TrackerStartupRetentionDebt(
	val failure: TrackerStartupRetentionFailure,
	val retirement: AmbientStepsSettingsReconciliationResult,
)

internal sealed interface TrackerStartupRetentionFailure {
	data class RetentionUnavailable(
		val result: RetentionAuthorityResult.Unavailable,
	) : TrackerStartupRetentionFailure

	data object ResultSetInvalid : TrackerStartupRetentionFailure

	data class StateMismatch(
		val result: RetentionAuthorityResult,
		val expected: RetentionAuthorityState,
	) : TrackerStartupRetentionFailure
}

private val TrackerStartupRetentionFailure.retryable: Boolean
	get() = when (this) {
		is TrackerStartupRetentionFailure.RetentionUnavailable ->
			result.reason !in NON_RETRYABLE_STARTUP_RETENTION_FAILURES
		TrackerStartupRetentionFailure.ResultSetInvalid -> false
		is TrackerStartupRetentionFailure.StateMismatch -> false
	}

private fun RetentionAuthorityResult.stateOrNull(): RetentionAuthorityState? = when (this) {
	is RetentionAuthorityResult.Applied -> state
	is RetentionAuthorityResult.Unchanged -> state
	is RetentionAuthorityResult.Unavailable -> null
}

private class TrackerStartupAuthorityPendingException(
	val result: TrackerStartupAuthorityResult,
) : IllegalStateException("Tracker startup authority remains unavailable")

private const val STARTUP_AUTHORITY_RETRY_DELAY_MS = 1_000L

private val NON_RETRYABLE_STARTUP_RETENTION_FAILURES = setOf(
	RetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED,
	RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
	RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
)

/** One opportunistic process-start reconciliation; provider failures do not block core tracking. */
internal suspend fun runAmbientStepsStartupReconciliation(
	reconcile: suspend () -> AmbientStepsProviderRegistrationResult,
	onFailure: (Exception?) -> Unit,
): AmbientStepsProviderRegistrationResult? = try {
	reconcile().also { result ->
		if (result is AmbientStepsProviderRegistrationResult.Degraded ||
			result is AmbientStepsProviderRegistrationResult.Failed
		) {
			onFailure(null)
		}
	}
} catch (cancellation: CancellationException) {
	throw cancellation
} catch (failure: Exception) {
	onFailure(failure)
	null
}

/**
 * Opens no provider while a confirmed force-stop has only a background process origin. The exact
 * Ready generation established after stale-session recovery is captured before foreground intent
 * is consumed. Initialization and suppression release are then one deletion-excluding operation;
 * a deletion or STOP race rejects the handoff and requires the next Ready generation.
 */
internal suspend fun handoffTrackerInitializationAfterAuthorization(
	reconcileStartup: suspend () -> TrackingStartupResult,
	currentReadyGeneration: () -> Long,
	awaitAuthorizationAfterReady: suspend () -> TrackingAutoRecoveryAuthorization,
	awaitNextReady: suspend () -> TrackingStartupResult.Ready,
	withReadyGenerationOperation: suspend (
		expectedGeneration: Long,
		operation: suspend () -> Unit,
	) -> Unit?,
	releaseForReadyGeneration: () -> Boolean,
	handoff: suspend (TrackingAutoRecoveryAuthorization) -> Unit,
): TrackingAutoRecoveryAuthorization? {
	if (reconcileStartup() !is TrackingStartupResult.Ready) {
		// The app owns the durable reconcile/backoff loop. Keep this one process-owned initializer
		// alive for its next Ready publication instead of consuming the coordinator's one-shot call.
		awaitNextReady()
	}
	var readyGeneration = currentReadyGeneration()
	val authorization = awaitAuthorizationAfterReady()
	while (true) {
		val completed = withReadyGenerationOperation(readyGeneration) {
			// Keep release last. BackgroundTrackingApi's Room/params flows do not filter on this
			// process guard, so their current eligible state is still delivered whether launchIn runs
			// immediately or after this block. Releasing first would let foreground permission repair
			// race a failed/partial singleton installation and would leave suppression open on failure.
			handoff(authorization)
			if (authorization ==
				TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
			) {
				check(releaseForReadyGeneration()) {
					"Explicit foreground recovery was not authorized for Ready-generation release"
				}
			}
		}
		if (completed != null) return authorization

		awaitNextReady()
		readyGeneration = currentReadyGeneration()
	}
}

/** A force-stop creates a fresh singleton; policy observation performs eligible reconciliation. */
internal fun initializeTrackerAutomaticControlAfterAuthorization(
	authorization: TrackingAutoRecoveryAuthorization,
	initialize: () -> Unit,
) {
	when (authorization) {
		TrackingAutoRecoveryAuthorization.ORDINARY_START,
		TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP,
		-> initialize()
	}
}

/** Defense in depth around the app coordinator's own process-wide one-shot initialization. */
internal class TrackerModuleInitializationGate {
	private val started = AtomicBoolean(false)

	fun tryStart(): Boolean = started.compareAndSet(false, true)
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
					TrackerDiagnosticLog.failure(
						TrackerDiagnosticFailureCode.ACTIVITY_SOURCE_RECOVERY_FAILED,
						TrackingDiagnosticFailureReason.RECOVERY_FAILURE,
					)
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
