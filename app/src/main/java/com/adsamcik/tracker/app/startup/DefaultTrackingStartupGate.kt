package com.adsamcik.tracker.app.startup

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.adsamcik.tracker.app.ApplicationStartupRecoveryAction
import com.adsamcik.tracker.app.HistoricalProcessExit
import com.adsamcik.tracker.app.applicationStartupRecoveryAction
import com.adsamcik.tracker.app.mostRecentMainProcessExit
import com.adsamcik.tracker.app.recentMainProcessExitCount
import com.adsamcik.tracker.app.settings.CollectedDataDeletionService
import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.tracker.resilience.InactiveTrackingSessionStopHandler
import com.adsamcik.tracker.tracker.resilience.InactiveTrackingSessionStopOutcome
import com.adsamcik.tracker.tracker.resilience.PreviousExitRecoveryCoordinator
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.source.coordinator.LegacyV27ProjectionRecoveryNotReadyException
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecoveryResult
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tracebox.Tracebox
import dev.tracebox.api.public
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Resolves Android process-exit evidence without opening Tracker's Room database. */
@Singleton
class ApplicationStartupRecoveryResolver @Inject constructor(
	@ApplicationContext private val context: Context,
	private val previousExitRecoveryCoordinatorProvider: Provider<PreviousExitRecoveryCoordinator>,
	private val trackingStartupGuard: TrackingStartupGuard,
) {
	internal fun resolveAndPrepare(): ApplicationStartupRecoveryAction {
		val action = applicationStartupRecoveryAction(
			confirmedForceStop = trackingStartupGuard.wasForceStopped(context),
			mainProcessExit = previousMainProcessExit(),
			fallbackTimestampMs = System.currentTimeMillis(),
		)
		if (action is ApplicationStartupRecoveryAction.ConfirmedForceStop) {
			// This must precede pending-deletion reconciliation and the first Room open.
			trackingStartupGuard.suppressAutoRecoveryForCurrentProcess()
		}
		return action
	}

	internal suspend fun apply(
		action: ApplicationStartupRecoveryAction,
		startupGeneration: Long,
	) {
		val previousExitRecoveryCoordinator = previousExitRecoveryCoordinatorProvider.get()
		when (action) {
			is ApplicationStartupRecoveryAction.ConfirmedForceStop ->
				previousExitRecoveryCoordinator.suppressAfterForceStop(action.completedAtMs)
			is ApplicationStartupRecoveryAction.PreviousExit ->
				previousExitRecoveryCoordinator.handle(
					reason = action.reason,
					completedAtMs = action.completedAtMs,
					startupGeneration = startupGeneration,
				)
			ApplicationStartupRecoveryAction.None ->
				// API 26-29 may have no prior-exit record at all. Old-boot Room authority must
				// still be retired before SourcePipelineRecovery reconciles providers below.
				previousExitRecoveryCoordinator.reconcileStaleSessions()
		}
	}

	/** Re-runs only stale Room lifecycle retirement for a mismatch discovered after initial apply. */
	internal suspend fun reconcileStaleSessions() {
		previousExitRecoveryCoordinatorProvider.get().reconcileStaleSessions()
	}

	private fun previousMainProcessExit(): HistoricalProcessExit? {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
		return try {
			val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
			val records = activityManager
				.getHistoricalProcessExitReasons(context.packageName, 0, HISTORICAL_PROCESS_LIMIT)
				.map { exitInfo ->
					HistoricalProcessExit(
						processName = exitInfo.processName,
						reason = exitInfo.reason,
						timestampMs = exitInfo.timestamp,
					)
				}
			val repeatedLowMemoryExitCount = recentMainProcessExitCount(
				records = records,
				mainProcessName = context.packageName,
				reason = ApplicationExitInfo.REASON_LOW_MEMORY,
				nowMs = System.currentTimeMillis(),
				windowMs = REPEATED_LOW_MEMORY_WINDOW_MS,
			)
			if (repeatedLowMemoryExitCount >= REPEATED_LOW_MEMORY_EXIT_THRESHOLD) {
				Tracebox.log.warn(
					TrackerTraceboxTemplates.APPLICATION_REPEATED_LOW_MEMORY_EXITS,
					public(repeatedLowMemoryExitCount),
					public(REPEATED_LOW_MEMORY_WINDOW_HOURS),
				)
			}
			mostRecentMainProcessExit(records, context.packageName)
		} catch (_: RuntimeException) {
			null
		}
	}

	private companion object {
		const val HISTORICAL_PROCESS_LIMIT = 16
		const val REPEATED_LOW_MEMORY_EXIT_THRESHOLD = 2
		const val REPEATED_LOW_MEMORY_WINDOW_HOURS = 24
		const val REPEATED_LOW_MEMORY_WINDOW_MS =
			REPEATED_LOW_MEMORY_WINDOW_HOURS * 60L * 60L * 1_000L
	}
}

/** Process-local half of the durable collected-data deletion marker. */
@Singleton
class TrackingStartupDeletionBarrier internal constructor(
	private val operationQuiescenceTimeoutMs: Long,
) {
	@Inject
	constructor() : this(DEFAULT_OPERATION_QUIESCENCE_TIMEOUT_MS)

	private val closed = AtomicBoolean(false)
	private val generation = AtomicLong(0L)
	private val mutableOpenGenerations = MutableStateFlow(0L)
	private val startupRecoveryMutex = Mutex()
	private val admissionMonitor = Any()

	/**
	 * Emits once for the initial process generation and again after each completed deletion.
	 * Closing deliberately does not emit: a reconciliation may itself be completing a durable
	 * pending deletion and must not be cancelled halfway through that transaction.
	 */
	val openGenerations: StateFlow<Long> = mutableOpenGenerations.asStateFlow()

	val isClosed: Boolean
		get() = closed.get()

	val currentGeneration: Long
		get() = generation.get()

	/**
	 * Closes admission immediately. Collected-data deletion uses this phase before it asks runtime
	 * owners to cancel providers, so a blocked provider cannot prevent its own stop request.
	 */
	internal fun closeAdmission() {
		synchronized(admissionMonitor) {
			if (closed.compareAndSet(false, true)) generation.incrementAndGet()
		}
	}

	/** Waits until every operation admitted before [closeAdmission] has left its protected section. */
	internal suspend fun awaitQuiescence() {
		val quiesced = withTimeoutOrNull(operationQuiescenceTimeoutMs) {
			startupRecoveryMutex.withLock { Unit }
			true
		} == true
		check(quiesced) {
			"Tracking runtime did not quiesce before the collected-data deletion deadline"
		}
	}

	/** Compatibility operation for callers that do not own runtime cancellation. */
	suspend fun close() {
		closeAdmission()
		awaitQuiescence()
	}

	suspend fun reopen() {
		startupRecoveryMutex.withLock {
			synchronized(admissionMonitor) {
				closed.set(false)
				mutableOpenGenerations.value = generation.get()
			}
		}
	}

	/** Runs one startup flight while deletion is excluded, or returns [onClosed] fail-closed. */
	internal suspend fun <T> withStartupRecovery(
		onClosed: () -> T,
		operation: suspend () -> T,
	): T = startupRecoveryMutex.withLock {
		val open = synchronized(admissionMonitor) { !closed.get() }
		if (open) operation() else onClosed()
	}

	/** Linearizes a short external handoff with close() without holding a coroutine lock. */
	internal fun <T> withOpenGeneration(
		expectedGeneration: Long,
		operation: () -> T,
	): T? = synchronized(admissionMonitor) {
		if (closed.get() || generation.get() != expectedGeneration) null else operation()
	}

	private companion object {
		const val DEFAULT_OPERATION_QUIESCENCE_TIMEOUT_MS = 30_000L
	}
}

/**
 * The one process-local authority that serializes storage, released-v27 recovery, lifecycle, and
 * deletion readiness. Live v28 projectors are source-local and never process-wide prerequisites.
 * Durable migration/drain rows remain the crash authority; only successful milestones are cached.
 */
@Singleton
class DefaultTrackingStartupGate @Inject constructor(
	private val legacyDatabaseUpgradeCoordinator: LegacyDatabaseUpgradeCoordinator,
	private val collectedDataDeletionService: CollectedDataDeletionService,
	private val recoveryResolver: ApplicationStartupRecoveryResolver,
	private val lifecycleCommandAuthority: TrackingLifecycleCommandAuthority,
	private val inactiveSessionStopHandler: InactiveTrackingSessionStopHandler,
	private val sourcePipelineRecoveryProvider: Provider<SourcePipelineRecovery>,
	private val deletionBarrier: TrackingStartupDeletionBarrier,
) : TrackingStartupGate {
	private val flightMonitor = Any()
	private var activeFlight: StartupFlight? = null
	@Volatile private var ready: TrackingStartupResult.Ready? = null
	@Volatile private var readyGeneration = -1L
	@Volatile private var readyStopGeneration = -1L
	private val readySignals = MutableStateFlow<RuntimeReadySignal?>(null)
	private var recoveryAction: ApplicationStartupRecoveryAction? = null
	private var previousExitApplied = false
	private var storageReadyGeneration = -1L
	private var mismatchRecoveryStopGeneration = 0L
	private var readySignalVersion = 0L

	override val isReady: Boolean
		get() = !deletionBarrier.isClosed && ready != null &&
			readyGeneration == deletionBarrier.currentGeneration &&
			readyStopGeneration == currentHandledStopGenerationOrNull()

	override val currentGeneration: Long
		get() = deletionBarrier.currentGeneration

	override fun isReadyGeneration(expectedGeneration: Long): Boolean =
		expectedGeneration == deletionBarrier.currentGeneration && isReady

	override fun <T> withReadyGeneration(
		expectedGeneration: Long,
		operation: () -> T,
	): T? = deletionBarrier.withOpenGeneration(expectedGeneration) {
		if (ready != null && readyGeneration == expectedGeneration &&
			readyStopGeneration == currentHandledStopGenerationOrNull()
		) operation() else null
	}

	override suspend fun <T> withReadyGenerationOperation(
		expectedGeneration: Long,
		operation: suspend () -> T,
	): T? = deletionBarrier.withStartupRecovery(onClosed = { null }) {
		if (expectedGeneration != deletionBarrier.currentGeneration ||
			ready == null || readyGeneration != expectedGeneration ||
			readyStopGeneration != currentHandledStopGenerationOrNull()
		) {
			return@withStartupRecovery null
		}
		var completed = false
		var value: T? = null
		val stopFenceAccepted = lifecycleCommandAuthority.runWithHandledStopGeneration(
			readyStopGeneration,
		) {
			value = operation()
			completed = true
		}
		if (stopFenceAccepted && completed) value else null
	}

	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult {
		ready.takeIf { isReady }?.let { return it }
		while (true) {
			val selection = synchronized(flightMonitor) {
				ready.takeIf { isReady }?.let { return it }
				val current = activeFlight
				if (current != null) {
					FlightSelection(current, owner = false)
				} else {
					val created = StartupFlight(retryFailedStorage)
					activeFlight = created
					FlightSelection(created, owner = true)
				}
			}
			if (!selection.owner) {
				val shared = selection.flight.result.await()
				if (retryFailedStorage && !selection.flight.retryFailedStorage) continue
				return shared
			}

			val result = try {
				runStartupFlight(retryFailedStorage)
			} catch (cancelled: CancellationException) {
				clearFlight(selection.flight)
				selection.flight.result.completeExceptionally(cancelled)
				throw cancelled
			} catch (failure: Exception) {
				TrackingStartupResult.RetryableFailure(
					TrackingStartupStage.LIVE_V2,
					failure.failureCode(),
				)
			} catch (failure: Throwable) {
				clearFlight(selection.flight)
				selection.flight.result.completeExceptionally(failure)
				throw failure
			}
			clearFlight(selection.flight)
			selection.flight.result.complete(result)
			return result
		}
	}

	private suspend fun runStartupFlight(
		retryFailedStorage: Boolean,
	): TrackingStartupResult {
		val action = recoveryAction ?: try {
			recoveryResolver.resolveAndPrepare().also { recoveryAction = it }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			return TrackingStartupResult.RetryableFailure(
				TrackingStartupStage.PREVIOUS_EXIT,
				failure.failureCode(),
			)
		}
		try {
			// Pending deletion owns its own mutex and may close/reopen this barrier. Running it
			// before the startup lease avoids self-deadlock while still keeping it ahead of Room.
			collectedDataDeletionService.reconcilePendingDeletion()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			return TrackingStartupResult.RetryableFailure(
				TrackingStartupStage.STORAGE,
				failure.failureCode(),
			)
		}
		return deletionBarrier.withStartupRecovery(
			onClosed = { deletionPendingFailure() },
		) {
			ready.takeIf { isReady }?.let { return@withStartupRecovery it }
			if (lifecycleCommandAuthority.latestUnhandledStop() != null) invalidateReadyLocked()
			reconcilePrerequisitesLocked(action, retryFailedStorage)?.let {
				return@withStartupRecovery it
			}
			val recoveryGeneration = deletionBarrier.currentGeneration
			val recoveryStopGeneration = currentHandledStopGenerationOrNull()
				?: return@withStartupRecovery pendingStopChangedFailure()

			val legacyRecovery = try {
				sourcePipelineRecoveryProvider.get().recoverStartupAuthority()
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (legacy: LegacyV27ProjectionRecoveryNotReadyException) {
				return@withStartupRecovery legacy.result.toStartupFailure()
			} catch (failure: Exception) {
				return@withStartupRecovery TrackingStartupResult.RetryableFailure(
					TrackingStartupStage.LEGACY_V27,
					failure.failureCode(),
				)
			}

			if (deletionBarrier.isClosed || recoveryGeneration != deletionBarrier.currentGeneration) {
				return@withStartupRecovery deletionPendingFailure()
			}
			if (currentHandledStopGenerationOrNull() != recoveryStopGeneration) {
				invalidateReadyLocked()
				return@withStartupRecovery TrackingStartupResult.RetryableFailure(
					TrackingStartupStage.PREVIOUS_EXIT,
					PENDING_STOP_ARRIVED_DURING_RECOVERY,
				)
			}

			TrackingStartupResult.Ready(
				legacyRecoveryPartial =
					(legacyRecovery as? LegacyV27ProjectionRecoveryResult.Complete)?.partial == true,
				// Live projection progress is intentionally not a process-wide admission boundary.
				liveCompletedThroughOrdinal = 0L,
			).also { result ->
				ready = result
				readyGeneration = recoveryGeneration
				readyStopGeneration = recoveryStopGeneration
				readySignalVersion += 1L
				readySignals.value = RuntimeReadySignal(
					version = readySignalVersion,
					generation = recoveryGeneration,
					stopGeneration = recoveryStopGeneration,
					result = result,
				)
			}
		}
	}

	private fun clearFlight(flight: StartupFlight) {
		synchronized(flightMonitor) {
			if (activeFlight === flight) activeFlight = null
		}
	}

	override suspend fun awaitReady(): TrackingStartupResult.Ready {
		ready.takeIf { isReady }?.let { return it }
		// Application owns the single active reconcile/backoff loop. Callers wait on its durable
		// Ready publication instead of starting competing retry loops; a Blocked result therefore
		// remains passive until an explicit repair reconciles successfully.
		return readySignals.filterNotNull().first { signal ->
			!deletionBarrier.isClosed &&
				signal.generation == deletionBarrier.currentGeneration &&
				signal.stopGeneration == currentHandledStopGenerationOrNull()
		}.result
	}

	/** Returns one stable handled-STOP snapshot, or null while STOP authority is changing/actionable. */
	private fun currentHandledStopGenerationOrNull(): Long? {
		val before = lifecycleCommandAuthority.latestStopGeneration()
		if (lifecycleCommandAuthority.latestUnhandledStop() != null) return null
		val after = lifecycleCommandAuthority.latestStopGeneration()
		return before.takeIf { it == after }
	}

	private suspend fun reconcilePrerequisitesLocked(
		action: ApplicationStartupRecoveryAction,
		retryFailedStorage: Boolean,
	): TrackingStartupResult? {
		if (deletionBarrier.isClosed) {
			return deletionPendingFailure()
		}

		if (storageReadyGeneration != deletionBarrier.currentGeneration) {
			when (val storage = legacyDatabaseUpgradeCoordinator.ensureReady(retryFailedStorage)) {
				LegacyDatabaseStartupResult.Ready -> {
					if (deletionBarrier.isClosed) {
						return TrackingStartupResult.RetryableFailure(
							TrackingStartupStage.STORAGE,
							COLLECTED_DATA_DELETION_PENDING,
						)
					}
					storageReadyGeneration = deletionBarrier.currentGeneration
				}
				is LegacyDatabaseStartupResult.Failed -> {
					val failureCode = storage.message.ifBlank { STORAGE_NOT_READY }
					return if (storage.requiresExplicitRetry) {
						TrackingStartupResult.Blocked(
							TrackingStartupStage.LEGACY_IMPORT,
							failureCode,
						)
					} else {
						TrackingStartupResult.RetryableFailure(
							TrackingStartupStage.STORAGE,
							failureCode,
						)
					}
				}
			}
		}

		reconcilePendingStopLocked(action)?.let { return it }

		applyPreviousExitLocked(action)?.let { return it }

		if (deletionBarrier.isClosed) {
			return TrackingStartupResult.RetryableFailure(
				TrackingStartupStage.STORAGE,
				COLLECTED_DATA_DELETION_PENDING,
			)
		}
		return null
	}

	/**
	 * Consumes the durable STOP fence before previous-exit or provider recovery can revive the
	 * descriptor it names. A START reservation cannot supersede this STOP; foreground-and-Room
	 * acceptance remains blocked until terminal cleanup and handled publication finish.
	 */
	private suspend fun reconcilePendingStopLocked(
		action: ApplicationStartupRecoveryAction,
	): TrackingStartupResult? {
		val command = lifecycleCommandAuthority.latestUnhandledStop() ?: return null
		invalidateReadyLocked()
		val firstAttempt = finalizePendingStop(command)
		val firstFailure = firstAttempt.exceptionOrNull()
		if (firstFailure != null) return pendingStopFailure(firstFailure)
		val firstOutcome = firstAttempt.getOrThrow()
		val remainingAfterFirst = lifecycleCommandAuthority.latestUnhandledStop()
		if (remainingAfterFirst == null) return null
		if (remainingAfterFirst != command) return pendingStopChangedFailure()
		if (firstOutcome != InactiveTrackingSessionStopOutcome.SESSION_MISMATCH) {
			return pendingStopNotHandledFailure(firstOutcome)
		}
		if (mismatchRecoveryStopGeneration == command.generation) {
			return pendingStopNotHandledFailure(firstOutcome)
		}

		val recoveryFailure = if (!previousExitApplied) {
			applyPreviousExitLocked(action)
		} else {
			try {
				recoveryResolver.reconcileStaleSessions()
				null
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (failure: Exception) {
				TrackingStartupResult.RetryableFailure(
					TrackingStartupStage.PREVIOUS_EXIT,
					failure.failureCode(),
				)
			}
		}
		if (recoveryFailure != null) return recoveryFailure
		mismatchRecoveryStopGeneration = command.generation

		val remainingBeforeRetry = lifecycleCommandAuthority.latestUnhandledStop()
		if (remainingBeforeRetry == null) return null
		if (remainingBeforeRetry != command) return pendingStopChangedFailure()
		val retryAttempt = finalizePendingStop(command)
		val retryFailure = retryAttempt.exceptionOrNull()
		if (retryFailure != null) return pendingStopFailure(retryFailure)
		val retryOutcome = retryAttempt.getOrThrow()
		val remainingAfterRetry = lifecycleCommandAuthority.latestUnhandledStop()
		if (remainingAfterRetry == null) return null
		if (remainingAfterRetry != command) return pendingStopChangedFailure()
		return pendingStopNotHandledFailure(retryOutcome)
	}

	private suspend fun applyPreviousExitLocked(
		action: ApplicationStartupRecoveryAction,
	): TrackingStartupResult.RetryableFailure? {
		if (previousExitApplied) return null
		return try {
			recoveryResolver.apply(action, deletionBarrier.currentGeneration)
			previousExitApplied = true
			null
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			TrackingStartupResult.RetryableFailure(
				TrackingStartupStage.PREVIOUS_EXIT,
				failure.failureCode(),
			)
		}
	}

	private suspend fun finalizePendingStop(
		command: TrackingStopCommand,
	): Result<InactiveTrackingSessionStopOutcome> = try {
		Result.success(inactiveSessionStopHandler.finalizeStoredSession(command))
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: Exception) {
		Result.failure(failure)
	}

	private fun pendingStopFailure(failure: Throwable) = TrackingStartupResult.RetryableFailure(
		TrackingStartupStage.PREVIOUS_EXIT,
		"$PENDING_STOP_FAILED:${failure.javaClass.simpleName.ifBlank { UNKNOWN_FAILURE }}",
	)

	private fun deletionPendingFailure() = TrackingStartupResult.RetryableFailure(
		TrackingStartupStage.STORAGE,
		COLLECTED_DATA_DELETION_PENDING,
	)

	private fun pendingStopChangedFailure() = TrackingStartupResult.RetryableFailure(
		TrackingStartupStage.PREVIOUS_EXIT,
		PENDING_STOP_CHANGED,
	)

	private fun pendingStopNotHandledFailure(
		outcome: InactiveTrackingSessionStopOutcome,
	) = TrackingStartupResult.RetryableFailure(
		TrackingStartupStage.PREVIOUS_EXIT,
		if (outcome == InactiveTrackingSessionStopOutcome.SESSION_MISMATCH) {
			PENDING_STOP_SESSION_MISMATCH
		} else {
			PENDING_STOP_NOT_HANDLED
		},
	)

	private fun invalidateReadyLocked() {
		ready = null
		readyGeneration = -1L
		readyStopGeneration = -1L
		readySignals.value = null
	}

	private fun LegacyV27ProjectionRecoveryResult.toStartupFailure(): TrackingStartupResult = when (this) {
		is LegacyV27ProjectionRecoveryResult.Blocked -> TrackingStartupResult.Blocked(
			TrackingStartupStage.LEGACY_V27,
			failureCode,
		)
		is LegacyV27ProjectionRecoveryResult.FailedRetryable ->
			TrackingStartupResult.RetryableFailure(TrackingStartupStage.LEGACY_V27, failureCode)
		LegacyV27ProjectionRecoveryResult.LeaseUnavailable -> TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.LEGACY_V27,
			LEGACY_LEASE_UNAVAILABLE,
		)
		LegacyV27ProjectionRecoveryResult.LifecycleSuperseded ->
			TrackingStartupResult.RetryableFailure(
				TrackingStartupStage.LEGACY_V27,
				LEGACY_LIFECYCLE_SUPERSEDED,
			)
		is LegacyV27ProjectionRecoveryResult.Complete,
		LegacyV27ProjectionRecoveryResult.NotRequired,
		-> error("Terminal released-v27 recovery was reported as not ready")
	}

	private fun Exception.failureCode(): String = javaClass.simpleName.ifBlank { UNKNOWN_FAILURE }

	private companion object {
		const val STORAGE_NOT_READY = "STORAGE_NOT_READY"
		const val COLLECTED_DATA_DELETION_PENDING = "COLLECTED_DATA_DELETION_PENDING"
		const val PENDING_STOP_FAILED = "PENDING_STOP_FAILED"
		const val PENDING_STOP_CHANGED = "PENDING_STOP_CHANGED"
		const val PENDING_STOP_ARRIVED_DURING_RECOVERY =
			"PENDING_STOP_ARRIVED_DURING_RECOVERY"
		const val PENDING_STOP_NOT_HANDLED = "PENDING_STOP_NOT_HANDLED"
		const val PENDING_STOP_SESSION_MISMATCH = "PENDING_STOP_SESSION_MISMATCH"
		const val LEGACY_LEASE_UNAVAILABLE = "LEGACY_V27_LEASE_UNAVAILABLE"
		const val LEGACY_LIFECYCLE_SUPERSEDED = "LEGACY_V27_LIFECYCLE_SUPERSEDED"
		const val UNKNOWN_FAILURE = "UNKNOWN_STARTUP_FAILURE"
	}

	private data class RuntimeReadySignal(
		val version: Long,
		val generation: Long,
		val stopGeneration: Long,
		val result: TrackingStartupResult.Ready,
	)

	private data class StartupFlight(
		val retryFailedStorage: Boolean,
		val result: kotlinx.coroutines.CompletableDeferred<TrackingStartupResult> =
			kotlinx.coroutines.CompletableDeferred(),
	)

	private data class FlightSelection(
		val flight: StartupFlight,
		val owner: Boolean,
	)
}
