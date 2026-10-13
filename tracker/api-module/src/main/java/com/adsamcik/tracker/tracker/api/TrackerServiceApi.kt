package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.tracker.controller.hasCollectionHost
import com.adsamcik.tracker.tracker.controller.hasLiveCapture
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
	fun manualTrackingStartReadinessReader(): ManualTrackingStartReadinessReader
	fun activityWatcherController(): ActivityWatcherController
	fun inactiveTrackingSessionStopHandler(): InactiveTrackingSessionStopHandler
	fun trackingLifecycleCommandAuthority(): TrackingLifecycleCommandAuthority
	fun trackingTurnOffController(): TrackingTurnOffController
	fun trackingStartRequestCoordinator(): TrackingStartRequestCoordinator
	@ApplicationScope fun applicationScope(): CoroutineScope
}

object TrackerServiceContract {
	const val SERVICE_CLASS_NAME = "com.adsamcik.tracker.tracker.service.TrackerService"
	const val ACTION_ACQUIRE_BACKGROUND_LOCATION_HOST =
		"com.adsamcik.tracker.action.ACQUIRE_BACKGROUND_LOCATION_HOST"
	const val ARG_BACKGROUND_LOCATION_HOST_TICKET = "background_location_host_ticket"
	const val ACTION_PREPARED_START =
		"com.adsamcik.tracker.tracker.action.PREPARED_TRACKING_START"
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
	private val turnOffRecoveryInFlight = mutableSetOf<String>()
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
		expectedCommandGeneration: Long? = null,
	): Boolean {
		if (!isUserInitiated && automaticTrigger == null && recoveryDescriptor == null) return false
		if (automaticTrigger != null && (expectedCommandGeneration == null || expectedCommandGeneration < 0L)) return false
		val appContext = context.applicationContext
		val entryPoint = getEntryPoint(appContext)
		entryPoint.applicationScope().launch {
			val commands = entryPoint.trackingLifecycleCommandAuthority()
			val command = if (automaticTrigger != null) {
				commands.reserveStartIfGeneration(requireNotNull(expectedCommandGeneration), automaticTrigger)
			} else commands.reserveStart()
			if (command == null) return@launch
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
		expectedCommandGeneration: Long? = null,
	): Boolean = startServiceAndAwaitEnqueueResult(
		context,
		isUserInitiated,
		isAmbient,
		automaticTrigger,
		recoveryDescriptor,
		expectedCommandGeneration,
	) is TrackingStartDispatchResult.Enqueued

	suspend fun startServiceAndAwaitEnqueueResult(
		context: Context,
		isUserInitiated: Boolean,
		isAmbient: Boolean = false,
		automaticTrigger: AutomaticTrackingStartTrigger? = null,
		recoveryDescriptor: ActiveTrackingSessionDescriptor? = null,
		expectedCommandGeneration: Long? = null,
	): TrackingStartDispatchResult {
		if (!isUserInitiated && automaticTrigger == null && recoveryDescriptor == null) {
			return TrackingStartDispatchResult.Terminal("TRACKING_START_AUTHORITY_MISSING")
		}
		if (automaticTrigger != null && (expectedCommandGeneration == null || expectedCommandGeneration < 0L)) {
			return TrackingStartDispatchResult.Terminal("AUTOMATIC_START_CONTEXT_MISSING")
		}
		val appContext = context.applicationContext
		val entryPoint = getEntryPoint(appContext)
		val commands = entryPoint.trackingLifecycleCommandAuthority()
		val command = if (automaticTrigger != null) {
			commands.reserveStartIfGeneration(requireNotNull(expectedCommandGeneration), automaticTrigger)
				?: return TrackingStartDispatchResult.Terminal("START_COMMAND_SUPERSEDED")
		} else if (expectedCommandGeneration != null) {
			commands.reserveStartIfGeneration(expectedCommandGeneration)
				?: return TrackingStartDispatchResult.Terminal("START_COMMAND_SUPERSEDED")
		} else {
			commands.reserveStart()
				?: return TrackingStartDispatchResult.Retryable("START_COMMAND_UNAVAILABLE")
		}
		return dispatchPreparedStartResult(
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
	): Intent = Intent(TrackerServiceContract.ACTION_PREPARED_START)
		.setClassName(context, TrackerServiceContract.SERVICE_CLASS_NAME).apply {
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

	/** Enqueues an in-process Location host ticket without creating a tracking session. */
	fun enqueueBackgroundLocationHost(context: Context, ticket: String): Boolean {
		require(ticket.isNotBlank())
		val appContext = context.applicationContext
		return startForegroundServiceSafely(
			appContext,
			Intent(TrackerServiceContract.ACTION_ACQUIRE_BACKGROUND_LOCATION_HOST)
				.setClassName(appContext, TrackerServiceContract.SERVICE_CLASS_NAME)
				.putExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET, ticket),
		)
	}

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

	/** Foreground repair attaches sources to the existing session using its accepted Start. */
	fun reconcileProviderStepsSession(context: Context) {
		val appContext = context.applicationContext
		val entryPoint = EntryPointAccessors.fromApplication(appContext, TrackerServiceApiEntryPoint::class.java)
		entryPoint.applicationScope().launch {
			try {
				reconcileProviderStepsSession(entryPoint.trackingStartRequestCoordinator(),
					entryPoint.trackingLifecycleCommandAuthority()) { prepared, command ->
					enqueuePreparedStart(appContext, prepared, command)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				// Keep the accepted organization. The next foreground repair retries the exact lifetime.
			}
		}
	}

	internal suspend fun reconcileProviderStepsSession(
		coordinator: TrackingStartRequestCoordinator,
		lifecycleAuthority: TrackingLifecycleCommandAuthority,
		platformEnqueue: (TrackingStartPreparationResult.Prepared, TrackingStartCommand) -> Boolean,
	): TrackingStartDispatchResult? {
		val request = coordinator.providerStepsContinuationRequest() ?: return null
		return dispatchPreparedStartResult(request, coordinator, lifecycleAuthority, platformEnqueue)
	}

	internal suspend fun dispatchPreparedStart(
		request: TrackingStartRequest,
		coordinator: TrackingStartRequestCoordinator,
		lifecycleAuthority: TrackingLifecycleCommandAuthority,
		platformEnqueue: (TrackingStartPreparationResult.Prepared, TrackingStartCommand) -> Boolean,
		elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
	): Boolean = dispatchPreparedStartResult(
		request,
		coordinator,
		lifecycleAuthority,
		platformEnqueue,
		elapsedRealtimeNanos,
	) is TrackingStartDispatchResult.Enqueued

	internal suspend fun dispatchPreparedStartResult(
		request: TrackingStartRequest,
		coordinator: TrackingStartRequestCoordinator,
		lifecycleAuthority: TrackingLifecycleCommandAuthority,
		platformEnqueue: (TrackingStartPreparationResult.Prepared, TrackingStartCommand) -> Boolean,
		elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
	): TrackingStartDispatchResult {
		var preparedToken: PreparedTrackingStartToken? = null
		var preparedStartupGeneration = -1L
		var enqueueOutcome = PlatformEnqueueOutcome.NOT_ATTEMPTED
		var startupPermitRejected = false
		val enqueueDeadline = request.automaticTrigger
			?.let(::activityTransitionCallbackEnqueueDeadlineElapsedRealtimeNanos)
		if (enqueueDeadline != null && elapsedRealtimeNanos() >= enqueueDeadline) {
			return TrackingStartDispatchResult.Terminal("LIVE_CALLBACK_DEADLINE_EXPIRED")
		}
		return try {
			val prepared = when (val result = coordinator.prepare(request)) {
				is TrackingStartPreparationResult.Prepared -> {
					preparedStartupGeneration = result.startupGeneration
					result
				}
				TrackingStartPreparationResult.AlreadyActive ->
					return TrackingStartDispatchResult.AlreadyActive
				TrackingStartPreparationResult.SetupPending -> return TrackingStartDispatchResult.SetupPending
				is TrackingStartPreparationResult.Rejected -> return when (result.disposition) {
					TrackingStartFailureDisposition.RETRYABLE ->
						TrackingStartDispatchResult.Retryable(result.failureCode)
					TrackingStartFailureDisposition.TERMINAL ->
						TrackingStartDispatchResult.Terminal(result.failureCode)
				}
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
				return TrackingStartDispatchResult.Terminal(
					"LIVE_CALLBACK_DEADLINE_EXPIRED_BEFORE_ENQUEUE",
				)
			}
			// The existing one-use claim happens only after actual Room preparation, so a late setup lag preserves intent.
			request.manualSourceSelection?.let { selection ->
				if (lifecycleAuthority.consumeManualStart(request.command.generation, selection) != request.command) {
					compensatePreparedStartWithinBudget(request, token, coordinator,
						"MANUAL_START_CONTINUATION_SUPERSEDED", elapsedRealtimeNanos)
					return TrackingStartDispatchResult.Terminal("MANUAL_START_CONTINUATION_SUPERSEDED")
				}
			}
			if (!prepared.requiresForegroundService) {
				val accepted = lifecycleAuthority.withCurrentStart(request.command,
					acceptWhen = { it }) {
					currentCoroutineContext().ensureActive()
					if (request.manualSourceSelection != null &&
						lifecycleAuthority.latestCommandGeneration() != request.command.generation) {
						return@withCurrentStart false
					}
					if (enqueueDeadline != null && elapsedRealtimeNanos() >= enqueueDeadline) false
					else coordinator.acceptWithoutForegroundService(prepared, request)
				}
				if (accepted is LockedTrackingStartResult.Executed && accepted.value) {
					enqueueOutcome = PlatformEnqueueOutcome.ENQUEUED
					coordinator.reconcileAcceptedSessionWithoutService(request.command)
					return TrackingStartDispatchResult.Enqueued
				}
				compensatePreparedStartWithinBudget(request, token, coordinator,
					"START_WITHOUT_SERVICE_NOT_ACCEPTED", elapsedRealtimeNanos)
				return TrackingStartDispatchResult.Terminal("START_WITHOUT_SERVICE_NOT_ACCEPTED")
			}
			val dispatchContext = currentCoroutineContext()
			when (val result = lifecycleAuthority.withCurrentStart(request.command) {
				// User-requested process exit advances the command fence without issuing a STOP.
				// A consumed manual tap must still be current at Android delivery.
				if (request.manualSourceSelection != null &&
					lifecycleAuthority.latestCommandGeneration() != request.command.generation
				) return@withCurrentStart false
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
					TrackingStartDispatchResult.Enqueued
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
					TrackingStartDispatchResult.Terminal(
						if (startupPermitRejected) {
							"STARTUP_GENERATION_CLOSED_BEFORE_ENQUEUE"
						} else {
							"ANDROID_FOREGROUND_SERVICE_START_NOT_ENQUEUED"
						},
					)
				}
				LockedTrackingStartResult.Stale -> {
					compensatePreparedStartWithinBudget(
						request,
						token,
						coordinator,
						"START_COMMAND_STALE_BEFORE_ENQUEUE",
						elapsedRealtimeNanos,
					)
					TrackingStartDispatchResult.Terminal("START_COMMAND_STALE_BEFORE_ENQUEUE")
				}
				is LockedTrackingStartResult.BlockedByStop -> {
					compensatePreparedStartWithinBudget(
						request,
						token,
						coordinator,
						"START_BLOCKED_BY_STOP_BEFORE_ENQUEUE",
						elapsedRealtimeNanos,
					)
					TrackingStartDispatchResult.Terminal("START_BLOCKED_BY_STOP_BEFORE_ENQUEUE")
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
			if (enqueueOutcome == PlatformEnqueueOutcome.ENQUEUED) {
				return TrackingStartDispatchResult.Enqueued
			}
			if (enqueueOutcome == PlatformEnqueueOutcome.UNKNOWN) {
				return TrackingStartDispatchResult.Terminal(
					"ANDROID_START_ENQUEUE_OUTCOME_UNKNOWN",
				)
			}
			if (enqueueOutcome != PlatformEnqueueOutcome.UNKNOWN) preparedToken?.let { token ->
				compensatePreparedStartWithinBudget(
					request,
					token,
					coordinator,
					"ANDROID_START_DISPATCH_FAILED",
					elapsedRealtimeNanos,
				)
			}
			TrackingStartDispatchResult.Retryable("ANDROID_START_DISPATCH_FAILED")
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

	/** Whether a live capture exists; Start/End/automatic locks use this predicate. */
	fun hasLiveCapture(context: Context): Boolean = getStateReader(context).hasLiveCapture

	/** Whether the collection service is hosting accepted work independently of live capture. */
	fun hasCollectionHost(context: Context): Boolean = getStateReader(context).hasCollectionHost

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
		expectedCommandGeneration: Long? = null,
	): Boolean = startServiceInternal(
		context,
		isUserInitiated,
		isAmbient = false,
		automaticTrigger = automaticTrigger,
		expectedCommandGeneration = expectedCommandGeneration,
	)

	/** Reads the current source-local rollout authority used by manual session preparation. */
	suspend fun readManualTrackingCaptureReachability(
		context: Context,
	): ManualTrackingCaptureReachability = getEntryPoint(context.applicationContext)
		.manualTrackingCaptureReachabilityReader()
		.read()

	/** Reads the same current prerequisites used by every user-initiated start surface. */
	suspend fun readManualTrackingStartReadiness(
		context: Context,
	): ManualTrackingStartReadiness = try {
		getEntryPoint(context.applicationContext)
			.manualTrackingStartReadinessReader()
			.read()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: RuntimeException) {
		ManualTrackingStartReadiness.TrackingUnavailable
	}

	/**
	 * Runs the complete manual-start boundary: current source readiness, durable PREPARE, and the
	 * immediate Android enqueue acknowledgement. Callers must not infer success from a queued
	 * coroutine or a platform service call alone.
	 */
	suspend fun requestManualTrackingStart(
		context: Context,
		continuationGeneration: Long? = null,
	): ManualTrackingStartResult = try {
		val appContext = context.applicationContext
		val entryPoint = getEntryPoint(appContext)
		val authority = entryPoint.trackingLifecycleCommandAuthority()
		executeManualTrackingStartContinuation(
			continuationGeneration = continuationGeneration,
			authority = authority,
			readPreflight = { entryPoint.manualTrackingStartReadinessReader().preparePreflight() },
			enqueue = { continuation ->
				dispatchPreparedStartResult(
					TrackingStartRequest(continuation.command, isUserInitiated = true,
						isAmbient = false, manualSourceSelection = continuation.selection),
					entryPoint.trackingStartRequestCoordinator(), authority,
					platformEnqueue = { prepared, command -> enqueuePreparedStart(appContext, prepared, command) },
				)
			},
		)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: RuntimeException) {
		ManualTrackingStartResult.TRACKING_UNAVAILABLE
	}

	/** Cold source/policy completion signals; observing them never prepares or starts tracking. */
	fun manualTrackingStartChanges(context: Context): kotlinx.coroutines.flow.Flow<Unit> =
		getEntryPoint(context.applicationContext).manualTrackingStartReadinessReader().changes()

	/** A dismissed/denied repair cannot turn a later permission change into a new Start. */
	suspend fun cancelManualTrackingStart(context: Context, generation: Long) {
		try {
			getEntryPoint(context).trackingLifecycleCommandAuthority().cancelManualStart(generation)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			// The UI drops its handoff; a later explicit Start replaces any unconsumed request.
		}
	}

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
			if (entryPoint.inactiveTrackingSessionStopHandler().finalizeWithoutService(command)) return@launch
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

	/** Stops every tracking purpose while retaining the person's source choices and saved data. */
	fun turnOffTracking(context: Context) {
		val entryPoint = getEntryPoint(context.applicationContext)
		entryPoint.applicationScope().launch {
			try {
				turnOffTrackingAndAwait(context.applicationContext)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				// The durable command remains available for retry through the awaitable API.
			}
		}
	}

	/** Awaitable form for screens that need to show a retryable cleanup result. */
	suspend fun turnOffTrackingAndAwait(
		context: Context,
		expectedStartGeneration: Long? = null,
	): TrackingTurnOffResult {
		val appContext = context.applicationContext
		val entryPoint = getEntryPoint(appContext)
		val controller = entryPoint.trackingTurnOffController()
		val reserved = try {
			controller.reserveAndFence(expectedStartGeneration)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			null
		}
		if (reserved == null) {
			if (expectedStartGeneration != null) {
				return if (entryPoint.trackingLifecycleCommandAuthority()
					.latestCommandGeneration() != expectedStartGeneration
				) TrackingTurnOffResult.SUPERSEDED_BY_NEW_START
				else TrackingTurnOffResult.UNAVAILABLE
			}
			val pendingExitId = entryPoint.trackingLifecycleCommandAuthority()
				.pendingCollectionResumeExitId
				?.takeIf { it.startsWith(TRACKING_TURN_OFF_EXIT_PREFIX) }
				?: return TrackingTurnOffResult.UNAVAILABLE
			scheduleTurnOffRecovery(entryPoint, pendingExitId)
			return TrackingTurnOffResult.DURABLY_OFF_CLEANUP_PENDING
		}
		val result = finishPendingTurnOff(entryPoint, reserved.exitId)
		if (result == TrackingTurnOffResult.DURABLY_OFF_CLEANUP_PENDING) {
			scheduleTurnOffRecovery(entryPoint, reserved.exitId)
		}
		return result
	}

	/** Called after startup STOP reconciliation, outside its non-reentrant operation lock. */
	suspend fun reconcilePendingTurnOffAfterStartup(context: Context): Boolean {
		val appContext = context.applicationContext
		val entryPoint = getEntryPoint(appContext)
		val pendingExitId = entryPoint.trackingLifecycleCommandAuthority()
			.pendingCollectionResumeExitId
			?.takeIf { it.startsWith(TRACKING_TURN_OFF_EXIT_PREFIX) }
			?: return true
		return finishPendingTurnOff(entryPoint, pendingExitId) !=
			TrackingTurnOffResult.DURABLY_OFF_CLEANUP_PENDING
	}

	private suspend fun finishPendingTurnOff(
		entryPoint: TrackerServiceApiEntryPoint,
		exitId: String,
	): TrackingTurnOffResult {
		val authority = entryPoint.trackingLifecycleCommandAuthority()
		if (authority.pendingCollectionResumeExitId != exitId) {
			return TrackingTurnOffResult.SUPERSEDED_BY_NEW_START
		}
		val pendingStop = authority.latestUnhandledStop()
		if (pendingStop != null) {
			val stop = try {
				withContext(NonCancellable) {
					withTimeoutOrNull(TURN_OFF_STOP_TIMEOUT_MILLIS) {
						reserveRouteAndAwaitDurableStop(
							reserve = { pendingStop },
							deliverToRunningService = TrackerRuntimeStopDispatcher::dispatch,
							finalizeWithoutService = { command ->
								entryPoint.inactiveTrackingSessionStopHandler().finalizeWithoutService(command)
							},
							finalizeInactiveService = { command ->
								entryPoint.inactiveTrackingSessionStopHandler()
									.finalizeStoredSession(command)
							},
							isCurrent = authority::isStopCurrent,
							isActionable = authority::isStopActionable,
						)
					}
				}
			} catch (_: Exception) {
				null
			}
			if (stop == TrackingStopQuiescenceResult.SUPERSEDED) {
				return TrackingTurnOffResult.SUPERSEDED_BY_NEW_START
			}
			if (stop != TrackingStopQuiescenceResult.HANDLED) {
				return TrackingTurnOffResult.DURABLY_OFF_CLEANUP_PENDING
			}
		}
		val ownersClosed = withContext(NonCancellable) {
			try {
				entryPoint.trackingTurnOffController().closeOwnersAfterSessionStop(exitId)
			} catch (_: Exception) {
				false
			}
		}
		return when (ownersClosed) {
			null -> TrackingTurnOffResult.SUPERSEDED_BY_NEW_START
			true -> TrackingTurnOffResult.COMPLETE
			false -> TrackingTurnOffResult.DURABLY_OFF_CLEANUP_PENDING
		}
	}

	private fun scheduleTurnOffRecovery(
		entryPoint: TrackerServiceApiEntryPoint,
		exitId: String,
	) {
		synchronized(turnOffRecoveryInFlight) {
			if (!turnOffRecoveryInFlight.add(exitId)) return
		}
		entryPoint.applicationScope().launch {
			try {
				var retryDelayMs = TURN_OFF_RETRY_INITIAL_DELAY_MILLIS
				while (entryPoint.trackingLifecycleCommandAuthority()
					.pendingCollectionResumeExitId == exitId) {
					val result = try {
						finishPendingTurnOff(entryPoint, exitId)
					} catch (cancelled: CancellationException) {
						throw cancelled
					} catch (_: Exception) {
						TrackingTurnOffResult.DURABLY_OFF_CLEANUP_PENDING
					}
					if (result != TrackingTurnOffResult.DURABLY_OFF_CLEANUP_PENDING) break
					delay(retryDelayMs)
					retryDelayMs = (retryDelayMs * 2L).coerceAtMost(TURN_OFF_RETRY_MAX_DELAY_MILLIS)
				}
			} finally {
				synchronized(turnOffRecoveryInFlight) { turnOffRecoveryInFlight.remove(exitId) }
			}
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
			finalizeWithoutService = { command ->
				entryPoint.inactiveTrackingSessionStopHandler().finalizeWithoutService(command)
			},
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
	finalizeWithoutService: suspend (TrackingStopCommand) -> Boolean = { false },
	isCurrent: (TrackingStopCommand) -> Boolean,
	isActionable: (TrackingStopCommand) -> Boolean,
	pauseBeforeRetry: suspend () -> Unit = { delay(STOP_QUIESCENCE_POLL_MILLIS) },
): TrackingStopQuiescenceResult {
	val command = reserve() ?: return TrackingStopQuiescenceResult.RESERVATION_FAILED
	if (finalizeWithoutService(command)) return TrackingStopQuiescenceResult.HANDLED
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
private const val TURN_OFF_STOP_TIMEOUT_MILLIS = 15_000L
private const val TURN_OFF_RETRY_INITIAL_DELAY_MILLIS = 1_000L
private const val TURN_OFF_RETRY_MAX_DELAY_MILLIS = 30_000L
