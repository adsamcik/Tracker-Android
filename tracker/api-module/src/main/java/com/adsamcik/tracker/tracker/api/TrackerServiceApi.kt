package com.adsamcik.tracker.tracker.api

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.resilience.InactiveTrackingSessionStopHandler
import com.adsamcik.tracker.tracker.resilience.LockedTrackingStartResult
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.service.TrackerRuntimeStopDispatcher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hilt EntryPoint for accessing read-only tracker state from static context
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TrackerServiceApiEntryPoint {
	fun trackerStateReader(): TrackerStateReader
	fun trackerServiceController(): TrackerServiceController
	fun manualTrackingCaptureReachabilityReader(): ManualTrackingCaptureReachabilityReader
	fun activityWatcherController(): ActivityWatcherController
	fun inactiveTrackingSessionStopHandler(): InactiveTrackingSessionStopHandler
	fun trackingLifecycleCommandAuthority(): TrackingLifecycleCommandAuthority
	fun trackingStartRequestCoordinator(): TrackingStartRequestCoordinator
	@ApplicationScope fun applicationScope(): CoroutineScope
}

object TrackerServiceContract {
	const val SERVICE_CLASS_NAME = "com.adsamcik.tracker.tracker.service.TrackerService"
	const val ARG_LIFECYCLE_COMMAND_GENERATION = "trackingLifecycleCommandGeneration"
	const val ARG_PREPARED_START_TOKEN = "preparedTrackingStartToken"
	/** Non-authoritative hints used only for immediate foreground-service promotion. */
	const val ARG_PREPARED_SOURCE_MASK_HINT = "preparedTrackingSourceMaskHint"
	const val ARG_PREPARED_USER_INITIATED_HINT = "preparedTrackingUserInitiatedHint"
}

/**
 * Last monotonic instant at which Android enqueue may spend an Activity transition callback's
 * background-start exemption. The persisted trigger expiry remains an independent, possibly
 * earlier, validity fence.
 */
fun activityTransitionCallbackEnqueueDeadlineElapsedRealtimeNanos(
	trigger: AutomaticTrackingStartTrigger,
): Long = minOf(
	trigger.expiresElapsedRealtimeNanos,
	activityTransitionCallbackEnqueueDeadlineElapsedRealtimeNanos(
		trigger.observedElapsedRealtimeNanos,
		trigger.receivedElapsedRealtimeNanos,
	),
)

/** Same live-context fence before policy creates the persisted automatic-start trigger. */
fun activityTransitionCallbackEnqueueDeadlineElapsedRealtimeNanos(
	observedElapsedRealtimeNanos: Long,
	receivedElapsedRealtimeNanos: Long,
): Long = minOf(
	observedElapsedRealtimeNanos.saturatedAdd(ACTIVITY_AUTOMATION_EVIDENCE_MAX_AGE_NANOS),
	receivedElapsedRealtimeNanos.saturatedAdd(ACTIVITY_CALLBACK_ENQUEUE_WINDOW_NANOS),
)

/** Absolute end of the small cleanup reserve inside the receiver's declared total budget. */
fun activityTransitionCallbackCleanupDeadlineElapsedRealtimeNanos(
	trigger: AutomaticTrackingStartTrigger,
): Long = minOf(
	trigger.expiresElapsedRealtimeNanos,
	trigger.receivedElapsedRealtimeNanos.saturatedAdd(ACTIVITY_CALLBACK_CLEANUP_WINDOW_NANOS),
)

/**
 * Public API for Tracker Service.
 */
object TrackerServiceApi {
	@Volatile
	private var cachedEntryPoint: TrackerServiceApiEntryPoint? = null

	private fun getEntryPoint(context: Context): TrackerServiceApiEntryPoint {
		cachedEntryPoint?.let { return it }
		return synchronized(this) {
			cachedEntryPoint ?: EntryPointAccessors.fromApplication(
				context.applicationContext,
				TrackerServiceApiEntryPoint::class.java
			).also { cachedEntryPoint = it }
		}
	}

	private fun getStateReader(context: Context): TrackerStateReader {
		return getEntryPoint(context).trackerStateReader()
	}

	private fun startServiceInternal(
		context: Context,
		isUserInitiated: Boolean,
		isAmbient: Boolean,
		automaticTrigger: AutomaticTrackingStartTrigger? = null,
		recoveryDescriptor: ActiveTrackingSessionDescriptor? = null,
	): Boolean {
		if (!isUserInitiated && automaticTrigger == null && recoveryDescriptor == null) return false
		val appContext = context.applicationContext
		val entryPoint = getEntryPoint(appContext)
		entryPoint.applicationScope().launch {
			val command = entryPoint.trackingLifecycleCommandAuthority().reserveStart()
				?: return@launch
			dispatchPreparedStart(
				request = TrackingStartRequest(
					command = command,
					isUserInitiated = isUserInitiated,
					isAmbient = isAmbient,
					automaticTrigger = automaticTrigger,
					recoveryDescriptor = recoveryDescriptor,
				),
				coordinator = entryPoint.trackingStartRequestCoordinator(),
				lifecycleAuthority = entryPoint.trackingLifecycleCommandAuthority(),
				platformEnqueue = { prepared, preparedCommand ->
					enqueuePreparedStart(appContext, prepared, preparedCommand)
				},
			)
		}
		// This Boolean intentionally means only that an application-scope request was queued.
		// Automatic outbox delivery uses startServiceAndAwaitEnqueue instead.
		return true
	}

	/**
	 * Awaits Room PREPARE and the immediate platform enqueue outcome. This is the only start API an
	 * automatic outbox may interpret as accepted by the Android-delivery boundary.
	 */
	suspend fun startServiceAndAwaitEnqueue(
		context: Context,
		isUserInitiated: Boolean,
		isAmbient: Boolean = false,
		automaticTrigger: AutomaticTrackingStartTrigger? = null,
		recoveryDescriptor: ActiveTrackingSessionDescriptor? = null,
	): Boolean {
		if (!isUserInitiated && automaticTrigger == null && recoveryDescriptor == null) return false
		val appContext = context.applicationContext
		val entryPoint = getEntryPoint(appContext)
		val command = entryPoint.trackingLifecycleCommandAuthority().reserveStart() ?: return false
		return dispatchPreparedStart(
			request = TrackingStartRequest(
				command = command,
				isUserInitiated = isUserInitiated,
				isAmbient = isAmbient,
				automaticTrigger = automaticTrigger,
				recoveryDescriptor = recoveryDescriptor,
			),
			coordinator = entryPoint.trackingStartRequestCoordinator(),
			lifecycleAuthority = entryPoint.trackingLifecycleCommandAuthority(),
			platformEnqueue = { prepared, preparedCommand ->
				enqueuePreparedStart(appContext, prepared, preparedCommand)
			},
		)
	}

	internal fun createPreparedStartIntent(
		context: Context,
		prepared: TrackingStartPreparationResult.Prepared,
		commandGeneration: Long,
	): Intent = Intent().setClassName(context, TrackerServiceContract.SERVICE_CLASS_NAME).apply {
		require(commandGeneration > 0L)
		require(prepared.preparedSourceMaskHint > 0L)
		putExtra(TrackerServiceContract.ARG_PREPARED_START_TOKEN, prepared.token.value)
		putExtra(TrackerServiceContract.ARG_LIFECYCLE_COMMAND_GENERATION, commandGeneration)
		putExtra(
			TrackerServiceContract.ARG_PREPARED_SOURCE_MASK_HINT,
			prepared.preparedSourceMaskHint,
		)
		putExtra(
			TrackerServiceContract.ARG_PREPARED_USER_INITIATED_HINT,
			prepared.preparedStartIsUserInitiatedHint,
		)
	}

	/**
	 * Adds one already-prepared start to Android's started-service queue.
	 *
	 * Recovery uses this same boundary to replace the Intent Android may redeliver. The source/type
	 * fields are non-authoritative deadline hints only; foreground-source legality and capture still
	 * come from the persisted start envelope after the startup gate is Ready.
	 */
	fun enqueuePreparedStart(
		context: Context,
		prepared: TrackingStartPreparationResult.Prepared,
		command: TrackingStartCommand,
	): Boolean = startForegroundServiceSafely(
		context.applicationContext,
		createPreparedStartIntent(context.applicationContext, prepared, command.generation),
	)

	/**
	 * Delivers a replacement start ID to an already-running foreground TrackerService. This does
	 * not attempt to spend a new background foreground-service exemption; the current service has
	 * already promoted before using this boundary.
	 */
	fun enqueuePreparedStartFromRunningService(
		context: Context,
		prepared: TrackingStartPreparationResult.Prepared,
		command: TrackingStartCommand,
	): Boolean = try {
		context.applicationContext.startService(
			createPreparedStartIntent(context.applicationContext, prepared, command.generation),
		) != null
	} catch (_: SecurityException) {
		false
	} catch (_: IllegalStateException) {
		false
	}

	internal suspend fun dispatchPreparedStart(
		request: TrackingStartRequest,
		coordinator: TrackingStartRequestCoordinator,
		lifecycleAuthority: TrackingLifecycleCommandAuthority,
		platformEnqueue: (TrackingStartPreparationResult.Prepared, TrackingStartCommand) -> Boolean,
		elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
	): Boolean {
		var preparedToken: PreparedTrackingStartToken? = null
		var preparedStartupGeneration = -1L
		var enqueueOutcome = PlatformEnqueueOutcome.NOT_ATTEMPTED
		var startupPermitRejected = false
		val enqueueDeadline = request.automaticTrigger
			?.let(::activityTransitionCallbackEnqueueDeadlineElapsedRealtimeNanos)
		if (enqueueDeadline != null && elapsedRealtimeNanos() >= enqueueDeadline) return false
		return try {
			val prepared = when (val result = coordinator.prepare(request)) {
				is TrackingStartPreparationResult.Prepared -> {
					preparedStartupGeneration = result.startupGeneration
					result
				}
				TrackingStartPreparationResult.AlreadyActive,
				is TrackingStartPreparationResult.Rejected,
				-> return false
			}
			val token = prepared.token
			preparedToken = token
			currentCoroutineContext().ensureActive()
			if (enqueueDeadline != null && elapsedRealtimeNanos() >= enqueueDeadline) {
				compensatePreparedStartWithinBudget(
					request,
					token,
					coordinator,
					"LIVE_CALLBACK_DEADLINE_EXPIRED_BEFORE_ENQUEUE",
					elapsedRealtimeNanos,
				)
				return false
			}
			val dispatchContext = currentCoroutineContext()
			when (val result = lifecycleAuthority.withCurrentStart(request.command) {
				coordinator.withStartupEnqueuePermit(preparedStartupGeneration) {
					dispatchContext.ensureActive()
					if (enqueueDeadline != null && elapsedRealtimeNanos() >= enqueueDeadline) {
						false
					} else {
						enqueueOutcome = PlatformEnqueueOutcome.UNKNOWN
						platformEnqueue(prepared, request.command).also { enqueued ->
							enqueueOutcome = if (enqueued) {
								PlatformEnqueueOutcome.ENQUEUED
							} else {
								PlatformEnqueueOutcome.NOT_ENQUEUED
							}
						}
					}
				} ?: run {
					startupPermitRejected = true
					false
				}
			}) {
				is LockedTrackingStartResult.Executed -> if (result.value) {
					// Android accepted the call already. A rejected/late acknowledgement must leave
					// the exact PREPARED row for service claim or startup reconciliation.
					coordinator.markAndroidStartEnqueued(token, request.command)
					true
				} else {
					compensatePreparedStartWithinBudget(
						request,
						token,
						coordinator,
						if (startupPermitRejected) {
							"STARTUP_GENERATION_CLOSED_BEFORE_ENQUEUE"
						} else {
							"ANDROID_FOREGROUND_SERVICE_START_NOT_ENQUEUED"
						},
						elapsedRealtimeNanos,
					)
					false
				}
				LockedTrackingStartResult.Stale -> {
					compensatePreparedStartWithinBudget(
						request,
						token,
						coordinator,
						"START_COMMAND_STALE_BEFORE_ENQUEUE",
						elapsedRealtimeNanos,
					)
					false
				}
				is LockedTrackingStartResult.BlockedByStop -> {
					compensatePreparedStartWithinBudget(
						request,
						token,
						coordinator,
						"START_BLOCKED_BY_STOP_BEFORE_ENQUEUE",
						elapsedRealtimeNanos,
					)
					false
				}
			}
		} catch (cancelled: CancellationException) {
			if (enqueueOutcome == PlatformEnqueueOutcome.NOT_ATTEMPTED ||
				enqueueOutcome == PlatformEnqueueOutcome.NOT_ENQUEUED
			) preparedToken?.let { token ->
				try {
					compensatePreparedStartWithinBudget(
						request,
						token,
						coordinator,
						"ANDROID_START_DISPATCH_CANCELLED",
						elapsedRealtimeNanos,
					)
				} catch (_: Throwable) {
					// Preserve the initiating cancellation. Durable PREPARED state is recoverable.
				}
			}
			throw cancelled
		} catch (_: RuntimeException) {
			if (enqueueOutcome == PlatformEnqueueOutcome.ENQUEUED) return true
			if (enqueueOutcome != PlatformEnqueueOutcome.UNKNOWN) preparedToken?.let { token ->
				compensatePreparedStartWithinBudget(
					request,
					token,
					coordinator,
					"ANDROID_START_DISPATCH_FAILED",
					elapsedRealtimeNanos,
				)
			}
			false
		}
	}

	private suspend fun compensatePreparedStartWithinBudget(
		request: TrackingStartRequest,
		token: PreparedTrackingStartToken,
		coordinator: TrackingStartRequestCoordinator,
		failureCode: String,
		elapsedRealtimeNanos: () -> Long,
	): Boolean {
		val timeoutMs = request.compensationTimeoutMillis(elapsedRealtimeNanos())
		if (timeoutMs <= 0L) return false
		val compensate: suspend () -> Boolean = {
			withTimeoutOrNull(timeoutMs) {
				coordinator.compensate(token, request.command, failureCode)
				true
			} ?: false
		}
		return if (currentCoroutineContext().isActive) {
			compensate()
		} else {
			withContext(NonCancellable) { compensate() }
		}
	}

	fun restartService(
		context: Context,
		descriptor: ActiveTrackingSessionDescriptor,
	): Boolean {
		if (!descriptor.isRestartEligible) return false
		return startServiceInternal(
			context = context,
			isUserInitiated = descriptor.isUserInitiated,
			isAmbient = descriptor.isAmbient,
			recoveryDescriptor = descriptor,
		)
	}

	private fun startForegroundServiceSafely(context: Context, intent: Intent): Boolean {
		return try {
			ContextCompat.startForegroundService(context, intent)
			true
		} catch (exception: SecurityException) {
			false
		} catch (exception: RuntimeException) {
			val isForegroundStartRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
				exception::class.java.name == "android.app.ForegroundServiceStartNotAllowedException"
			if (isForegroundStartRestricted) {
				false
			} else {
				throw exception
			}
		}
	}

	/**
	 * Information about current tracking session as Flow. Null if no session is currently active.
	 */
	fun sessionInfoFlow(context: Context): StateFlow<TrackerSessionInfo?> =
		getStateReader(context).sessionInfoFlow

	/**
	 * Indicates whether tracker service is active.
	 */
	fun isActive(context: Context): Boolean =
		getStateReader(context).isServiceRunning

	/** Informational compatibility probe used only to repair stale presentation state. */
	fun isRunningInSystem(context: Context): Boolean {
		val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
			?: return false
		@Suppress("DEPRECATION")
		return activityManager.getRunningServices(Int.MAX_VALUE).any { info ->
			info.service.className == TrackerServiceContract.SERVICE_CLASS_NAME
		}
	}

	/**
	 * Clears application-scoped tracker state after Android reports that the service process is gone.
	 */
	fun repairStoppedServiceState(context: Context) {
		val entryPoint = getEntryPoint(context)
		repairStoppedServiceState(
			controller = entryPoint.trackerServiceController(),
			activityWatcherController = entryPoint.activityWatcherController(),
		)
	}

	internal fun repairStoppedServiceState(
		controller: TrackerServiceController,
		activityWatcherController: ActivityWatcherController,
	) {
		controller.updateServiceRunning(false)
		controller.updateSessionInfo(null)
		controller.updateSession(null)
		controller.updateCollectionData(null)
		controller.updatePolicyState(null)
		controller.updatePolicyTier(PolicyTier.OFF)
		controller.updateSkiState(null)
		controller.updateSailingState(null)
		controller.updatePlaneState(null)
		activityWatcherController.poke()
	}

	/**
	 * Starts tracker service in foreground.
	 */
	fun startService(
		context: Context,
		isUserInitiated: Boolean,
		automaticTrigger: AutomaticTrackingStartTrigger? = null,
	): Boolean = startServiceInternal(
		context,
		isUserInitiated,
		isAmbient = false,
		automaticTrigger = automaticTrigger,
	)

	/** Reads the current source-local rollout authority used by manual session preparation. */
	suspend fun readManualTrackingCaptureReachability(
		context: Context,
	): ManualTrackingCaptureReachability = getEntryPoint(context.applicationContext)
		.manualTrackingCaptureReachabilityReader()
		.read()

	/**
	 * Legacy session-shaped ambient entry. Ambient acquisition is app-scoped broker work and this
	 * method deliberately fails closed instead of manufacturing an automatic FGS without a trigger.
	 */
	@Deprecated("Ambient acquisition is owned by the app-scoped source broker")
	fun startAmbientService(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

	/**
	 * Stops tracker service.
	 */
	fun stopService(
		context: Context,
		reason: TrackingStopCandidateReason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
	) {
		val appContext = context.applicationContext
		val entryPoint = getEntryPoint(appContext)
		entryPoint.applicationScope().launch {
			val command = entryPoint.trackingLifecycleCommandAuthority().reserveStop(
				reason = reason,
				requestedAtEpochMs = System.currentTimeMillis(),
				requestedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
			) ?: return@launch
			routeDurableStop(
				command = command,
				deliverToRunningService = TrackerRuntimeStopDispatcher::dispatch,
				finalizeInactiveService = { candidate ->
					entryPoint.applicationScope().launch {
						entryPoint.inactiveTrackingSessionStopHandler().finalizeStoredSession(candidate)
					}
				},
			)
		}
	}

	/**
	 * Reserves one durable STOP and does not return until that exact generation has crossed the
	 * runtime/provider teardown boundary. This is intentionally separate from the fire-and-forget
	 * UI API above: destructive maintenance must not infer quiescence from presentation state.
	 *
	 * The caller owns the timeout. A timeout or cancellation leaves the durable STOP actionable so
	 * startup reconciliation can finish it before writers are reopened.
	 */
	suspend fun stopServiceAndAwaitQuiescence(
		context: Context,
		reason: TrackingStopCandidateReason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
	): TrackingStopQuiescenceResult {
		val appContext = context.applicationContext
		val entryPoint = getEntryPoint(appContext)
		val authority = entryPoint.trackingLifecycleCommandAuthority()
		return reserveRouteAndAwaitDurableStop(
			reserve = {
				authority.reserveStop(
					reason = reason,
					requestedAtEpochMs = System.currentTimeMillis(),
					requestedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
				)
			},
			deliverToRunningService = TrackerRuntimeStopDispatcher::dispatch,
			finalizeInactiveService = { command ->
				entryPoint.inactiveTrackingSessionStopHandler().finalizeStoredSession(command)
			},
			isCurrent = authority::isStopCurrent,
			isActionable = authority::isStopActionable,
		)
	}

	/**
	 * Delivers to the one process-local runtime owner or finalizes the exact inactive lifecycle.
	 * There is deliberately no Android service start in this path.
	 */
	internal fun routeDurableStop(
		command: TrackingStopCommand,
		deliverToRunningService: (
			TrackingStopCommand,
			(TrackingStopCommand) -> Unit,
		) -> Boolean,
		finalizeInactiveService: (TrackingStopCommand) -> Unit,
	) {
		if (!deliverToRunningService(command, finalizeInactiveService)) {
			finalizeInactiveService(command)
		}
	}
}

enum class TrackingStopQuiescenceResult {
	HANDLED,
	RESERVATION_FAILED,
	SUPERSEDED,
}

/**
 * Testable stop-routing core. Runtime disappearance is delivered through a small process-local
 * mailbox; the durable STOP generation remains the sole completion authority.
 */
internal suspend fun reserveRouteAndAwaitDurableStop(
	reserve: suspend () -> TrackingStopCommand?,
	deliverToRunningService: (
		TrackingStopCommand,
		(TrackingStopCommand) -> Unit,
	) -> Boolean,
	finalizeInactiveService: suspend (TrackingStopCommand) -> Unit,
	isCurrent: (TrackingStopCommand) -> Boolean,
	isActionable: (TrackingStopCommand) -> Boolean,
	pauseBeforeRetry: suspend () -> Unit = { delay(STOP_QUIESCENCE_POLL_MILLIS) },
): TrackingStopQuiescenceResult {
	val command = reserve() ?: return TrackingStopQuiescenceResult.RESERVATION_FAILED
	val undelivered = Channel<TrackingStopCommand>(Channel.UNLIMITED)
	try {
		if (!deliverToRunningService(command) { candidate -> undelivered.trySend(candidate) }) {
			finalizeInactiveService(command)
		}
		while (true) {
			while (true) {
				val candidate = undelivered.tryReceive().getOrNull() ?: break
				finalizeInactiveService(candidate)
			}
			if (!isActionable(command)) {
				return if (isCurrent(command)) {
					TrackingStopQuiescenceResult.HANDLED
				} else {
					TrackingStopQuiescenceResult.SUPERSEDED
				}
			}
			pauseBeforeRetry()
		}
	} finally {
		undelivered.close()
	}
}

private fun TrackingStartRequest.compensationTimeoutMillis(nowElapsedRealtimeNanos: Long): Long {
	val deadline = automaticTrigger
		?.let(::activityTransitionCallbackCleanupDeadlineElapsedRealtimeNanos)
		?: return DEFAULT_START_COMPENSATION_TIMEOUT_MS
	if (deadline <= nowElapsedRealtimeNanos) return 0L
	return (deadline - nowElapsedRealtimeNanos) / NANOS_PER_MILLISECOND
}

private fun Long.saturatedAdd(increment: Long): Long =
	if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment

private enum class PlatformEnqueueOutcome {
	NOT_ATTEMPTED,
	UNKNOWN,
	NOT_ENQUEUED,
	ENQUEUED,
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val ACTIVITY_CALLBACK_ENQUEUE_WINDOW_NANOS = 7_000L * NANOS_PER_MILLISECOND
private const val ACTIVITY_CALLBACK_CLEANUP_WINDOW_NANOS = 7_750L * NANOS_PER_MILLISECOND
private const val ACTIVITY_AUTOMATION_EVIDENCE_MAX_AGE_NANOS = 60_000L * NANOS_PER_MILLISECOND
private const val DEFAULT_START_COMPENSATION_TIMEOUT_MS = 250L
private const val STOP_QUIESCENCE_POLL_MILLIS = 25L
