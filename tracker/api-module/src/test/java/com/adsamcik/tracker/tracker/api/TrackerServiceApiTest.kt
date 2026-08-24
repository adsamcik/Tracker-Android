package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommandDisposition
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerServiceApiTest {
	@Test
	fun `prepared Room intent precedes platform enqueue and enqueue acknowledgement`() = runTest {
		val events = mutableListOf<String>()
		val token = PreparedTrackingStartToken("prepared-1")
		val coordinator = RecordingStartCoordinator(
			preparation = TrackingStartPreparationResult.Prepared(token),
			events = events,
		)
		val command = TrackingStartCommand(1L)

		TrackerServiceApi.dispatchPreparedStart(
			request = TrackingStartRequest(command, isUserInitiated = true, isAmbient = false),
			coordinator = coordinator,
			lifecycleAuthority = DispositionAuthority(TrackingStartCommandDisposition.Allowed, events),
			platformEnqueue = { deliveredPrepared, deliveredCommand ->
				deliveredPrepared.token shouldBe token
				deliveredCommand shouldBe command
				events += "enqueue"
				true
			},
		) shouldBe true

		events shouldBe listOf("prepare", "resolve", "enqueue", "mark-enqueued")
	}

	@Test
	fun `stop fence after prepare prevents Android delivery and compensates exact token`() = runTest {
		val events = mutableListOf<String>()
		val token = PreparedTrackingStartToken("prepared-stop")
		val command = TrackingStartCommand(1L)
		val stop = TrackingStopCommand(
			generation = 2L,
			reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
			requestedAtEpochMs = 2_000L,
		)
		val coordinator = RecordingStartCoordinator(
			preparation = TrackingStartPreparationResult.Prepared(token),
			events = events,
		)

		TrackerServiceApi.dispatchPreparedStart(
			request = TrackingStartRequest(command, isUserInitiated = true, isAmbient = false),
			coordinator = coordinator,
			lifecycleAuthority = DispositionAuthority(
				TrackingStartCommandDisposition.BlockedByStop(stop),
				events,
			),
			platformEnqueue = { _, _ -> error("blocked start must not reach Android") },
		) shouldBe false

		events shouldBe listOf(
			"prepare",
			"resolve",
			"compensate:START_BLOCKED_BY_STOP_BEFORE_ENQUEUE",
		)
	}

	@Test
	fun `closed startup generation after prepare prevents Android delivery`() = runTest {
		val events = mutableListOf<String>()
		val token = PreparedTrackingStartToken("prepared-generation-closed")
		val coordinator = RecordingStartCoordinator(
			preparation = TrackingStartPreparationResult.Prepared(token, startupGeneration = 9L),
			events = events,
		).also { it.allowEnqueue = false }

		TrackerServiceApi.dispatchPreparedStart(
			request = TrackingStartRequest(
				TrackingStartCommand(1L),
				isUserInitiated = true,
				isAmbient = false,
			),
			coordinator = coordinator,
			lifecycleAuthority = DispositionAuthority(
				TrackingStartCommandDisposition.Allowed,
				events,
			),
			platformEnqueue = { _, _ -> error("closed startup must not reach Android") },
		) shouldBe false

		events shouldBe listOf(
			"prepare",
			"resolve",
			"compensate:STARTUP_GENERATION_CLOSED_BEFORE_ENQUEUE",
		)
	}

	@Test
	fun `platform enqueue failure terminalizes the exact prepared request`() = runTest {
		val events = mutableListOf<String>()
		val token = PreparedTrackingStartToken("prepared-failure")
		val coordinator = RecordingStartCoordinator(
			preparation = TrackingStartPreparationResult.Prepared(token),
			events = events,
		)

		TrackerServiceApi.dispatchPreparedStart(
			request = TrackingStartRequest(
				TrackingStartCommand(3L),
				isUserInitiated = true,
				isAmbient = false,
			),
			coordinator = coordinator,
			lifecycleAuthority = DispositionAuthority(TrackingStartCommandDisposition.Allowed, events),
			platformEnqueue = { _, _ ->
				events += "enqueue"
				false
			},
		) shouldBe false

		events shouldBe listOf(
			"prepare",
			"resolve",
			"enqueue",
			"compensate:ANDROID_FOREGROUND_SERVICE_START_NOT_ENQUEUED",
		)
	}

	@Test
	fun `expired live callback deadline never starts PREPARE or platform enqueue`() = runTest {
		val events = mutableListOf<String>()
		val trigger = automaticTrigger()
		val coordinator = RecordingStartCoordinator(
			TrackingStartPreparationResult.Prepared(PreparedTrackingStartToken("too-late")),
			events,
		)

		TrackerServiceApi.dispatchPreparedStart(
			request = automaticRequest(trigger),
			coordinator = coordinator,
			lifecycleAuthority = DispositionAuthority(
				TrackingStartCommandDisposition.Allowed,
				events,
			),
			platformEnqueue = { _, _ -> error("expired callback must never reach Android") },
			elapsedRealtimeNanos = { 7_000_000_000L },
		) shouldBe false

		events shouldBe emptyList()
	}

	@Test
	fun `PREPARE completing at live deadline compensates without platform enqueue`() = runTest {
		val events = mutableListOf<String>()
		val token = PreparedTrackingStartToken("prepared-at-deadline")
		val coordinator = RecordingStartCoordinator(
			TrackingStartPreparationResult.Prepared(token),
			events,
			prepareDelayMs = 7_000L,
		)

		TrackerServiceApi.dispatchPreparedStart(
			request = automaticRequest(),
			coordinator = coordinator,
			lifecycleAuthority = DispositionAuthority(
				TrackingStartCommandDisposition.Allowed,
				events,
			),
			platformEnqueue = { _, _ -> error("deadline must be rechecked after PREPARE") },
			elapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000L },
		) shouldBe false

		events shouldBe listOf(
			"prepare",
			"compensate:LIVE_CALLBACK_DEADLINE_EXPIRED_BEFORE_ENQUEUE",
		)
	}

	@Test
	fun `authority wait crossing live deadline never reaches platform enqueue`() = runTest {
		val events = mutableListOf<String>()
		val coordinator = RecordingStartCoordinator(
			TrackingStartPreparationResult.Prepared(PreparedTrackingStartToken("authority-wait")),
			events,
		)

		TrackerServiceApi.dispatchPreparedStart(
			request = automaticRequest(),
			coordinator = coordinator,
			lifecycleAuthority = DispositionAuthority(
				TrackingStartCommandDisposition.Allowed,
				events,
				beforeAction = { delay(7_000L) },
			),
			platformEnqueue = { _, _ -> error("authority wait consumed the live deadline") },
			elapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000L },
		) shouldBe false

		events shouldBe listOf(
			"prepare",
			"resolve",
			"compensate:ANDROID_FOREGROUND_SERVICE_START_NOT_ENQUEUED",
		)
	}

	@Test
	fun `ack cancellation propagates and leaves enqueued PREPARED state for reconciliation`() = runTest {
		val events = mutableListOf<String>()
		val coordinator = RecordingStartCoordinator(
			TrackingStartPreparationResult.Prepared(PreparedTrackingStartToken("ack-cancelled")),
			events,
			markEnqueued = { awaitCancellation() },
		)

		shouldThrow<TimeoutCancellationException> {
			withTimeout(7_500L) {
				TrackerServiceApi.dispatchPreparedStart(
					request = automaticRequest(),
					coordinator = coordinator,
					lifecycleAuthority = DispositionAuthority(
						TrackingStartCommandDisposition.Allowed,
						events,
					),
					platformEnqueue = { _, _ ->
						events += "enqueue"
						true
					},
					elapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000L },
				)
			}
		}

		testScheduler.currentTime shouldBe 7_500L
		events shouldBe listOf("prepare", "resolve", "enqueue", "mark-enqueued")
	}

	@Test
	fun `cancellation compensation stops at absolute cleanup deadline`() = runTest {
		val events = mutableListOf<String>()
		val coordinator = RecordingStartCoordinator(
			TrackingStartPreparationResult.Prepared(PreparedTrackingStartToken("cleanup-timeout")),
			events,
			compensation = { awaitCancellation() },
		)

		shouldThrow<TimeoutCancellationException> {
			withTimeout(7_500L) {
				TrackerServiceApi.dispatchPreparedStart(
					request = automaticRequest(),
					coordinator = coordinator,
					lifecycleAuthority = DispositionAuthority(
						TrackingStartCommandDisposition.Allowed,
						events,
						beforeAction = { awaitCancellation() },
					),
					platformEnqueue = { _, _ -> error("cancelled authority must not reach Android") },
					elapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000L },
				)
			}
		}

		testScheduler.currentTime shouldBe 7_750L
		events shouldBe listOf(
			"prepare",
			"resolve",
			"compensate:ANDROID_START_DISPATCH_CANCELLED",
		)
	}

	@Test
	fun `unavailable runtime stop finalizes durable state without starting an Android service`() {
		var deliveryAttempts = 0
		var inactiveFinalizations = 0
		val command = stopCommand()

		TrackerServiceApi.routeDurableStop(
			command = command,
			deliverToRunningService = { _, _ ->
				deliveryAttempts += 1
				false
			},
			finalizeInactiveService = {
				it shouldBe command
				inactiveFinalizations += 1
			},
		)

		deliveryAttempts shouldBe 1
		inactiveFinalizations shouldBe 1
	}

	@Test
	fun `running service stop never falls through to inactive finalization`() {
		var deliveryAttempts = 0
		var inactiveFinalizations = 0

		TrackerServiceApi.routeDurableStop(
			command = stopCommand(),
			deliverToRunningService = { _, _ ->
				deliveryAttempts += 1
				true
			},
			finalizeInactiveService = { inactiveFinalizations += 1 },
		)

		deliveryAttempts shouldBe 1
		inactiveFinalizations shouldBe 0
	}

	@Test
	fun `runtime disappearing after dispatch falls back to the same exact finalizer`() {
		var fallback: ((TrackingStopCommand) -> Unit)? = null
		var finalized: TrackingStopCommand? = null
		val command = stopCommand()

		TrackerServiceApi.routeDurableStop(
			command = command,
			deliverToRunningService = { _, onUndelivered ->
				fallback = onUndelivered
				true
			},
			finalizeInactiveService = { finalized = it },
		)
		fallback?.invoke(command)

		finalized shouldBe command
	}

	@Test
	fun `awaitable stop completes only after the exact running-runtime command is handled`() = runTest {
		val command = stopCommand()
		var actionable = true
		var pauseCount = 0
		var inactiveFinalizations = 0

		reserveRouteAndAwaitDurableStop(
			reserve = { command },
			deliverToRunningService = { delivered, _ ->
				delivered shouldBe command
				true
			},
			finalizeInactiveService = { inactiveFinalizations += 1 },
			isCurrent = { it == command },
			isActionable = { actionable },
			pauseBeforeRetry = {
				pauseCount += 1
				if (pauseCount == 2) actionable = false
			},
		) shouldBe TrackingStopQuiescenceResult.HANDLED

		pauseCount shouldBe 2
		inactiveFinalizations shouldBe 0
	}

	@Test
	fun `awaitable stop finalizes an absent runtime before reporting quiescence`() = runTest {
		val command = stopCommand()
		var actionable = true
		var finalized: TrackingStopCommand? = null

		reserveRouteAndAwaitDurableStop(
			reserve = { command },
			deliverToRunningService = { _, _ -> false },
			finalizeInactiveService = {
				finalized = it
				actionable = false
			},
			isCurrent = { it == command },
			isActionable = { actionable },
			pauseBeforeRetry = { error("terminal inactive finalization must not poll") },
		) shouldBe TrackingStopQuiescenceResult.HANDLED

		finalized shouldBe command
	}

	@Test
	fun `awaitable stop never treats a superseding generation as quiescence`() = runTest {
		val command = stopCommand()
		var actionable = true
		var current = true

		reserveRouteAndAwaitDurableStop(
			reserve = { command },
			deliverToRunningService = { _, _ -> true },
			finalizeInactiveService = { error("running runtime must own the stop") },
			isCurrent = { current },
			isActionable = { actionable },
			pauseBeforeRetry = {
				actionable = false
				current = false
			},
		) shouldBe TrackingStopQuiescenceResult.SUPERSEDED
	}

	@Test
	fun `stale service repair restarts automatic tracking watcher evaluation`() {
		val controller = mockk<TrackerServiceController>(relaxed = true)
		val activityWatcherController = mockk<ActivityWatcherController>(relaxed = true)

		TrackerServiceApi.repairStoppedServiceState(controller, activityWatcherController)

		verifyOrder {
			controller.updateServiceRunning(false)
			controller.updateSessionInfo(null)
			controller.updateSession(null)
			controller.updateCollectionData(null)
			controller.updatePolicyState(null)
			controller.updatePolicyTier(any())
			controller.updateSkiState(null)
			controller.updateSailingState(null)
			controller.updatePlaneState(null)
			activityWatcherController.poke()
		}
	}

	private fun stopCommand() = TrackingStopCommand(
		generation = 2L,
		reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
		requestedAtEpochMs = 1_000L,
	)

	private fun automaticRequest(
		trigger: AutomaticTrackingStartTrigger = automaticTrigger(),
	) = TrackingStartRequest(
		command = TrackingStartCommand(1L),
		isUserInitiated = false,
		isAmbient = false,
		automaticTrigger = trigger,
	)

	private fun automaticTrigger() = AutomaticTrackingStartTrigger(
		triggerId = "activity-transition:boot-1:1",
		kind = "ACTIVITY_TRANSITION:WALKING:ENTER",
		bootId = "boot-1",
		observedElapsedRealtimeNanos = 0L,
		receivedElapsedRealtimeNanos = 0L,
		expiresElapsedRealtimeNanos = 60_000_000_000L,
		automationEpoch = 1L,
		startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
		sourcePolicyRevision = 1L,
		intendedCaptureSourceMask = 1L,
		requestedCaptureSourceMask = 1L,
		intendedForegroundServiceTypeMask = 0L,
		collectedDataEpoch = 1L,
	)

	private class RecordingStartCoordinator(
		private val preparation: TrackingStartPreparationResult,
		private val events: MutableList<String>,
		private val prepareDelayMs: Long = 0L,
		private val markEnqueued: suspend () -> Boolean = { true },
		private val compensation: suspend (String) -> Unit = {},
	) : TrackingStartRequestCoordinator {
		var allowEnqueue = true

		override suspend fun prepare(request: TrackingStartRequest): TrackingStartPreparationResult {
			events += "prepare"
			if (prepareDelayMs > 0L) delay(prepareDelayMs)
			return preparation
		}

		override fun <T> withStartupEnqueuePermit(
			startupGeneration: Long,
			operation: () -> T,
		): T? = if (allowEnqueue) operation() else null

		override suspend fun markAndroidStartEnqueued(
			token: PreparedTrackingStartToken,
			command: TrackingStartCommand,
		): Boolean {
			events += "mark-enqueued"
			return markEnqueued()
		}

		override suspend fun compensate(
			token: PreparedTrackingStartToken,
			command: TrackingStartCommand,
			failureCode: String,
		) {
			events += "compensate:$failureCode"
			compensation(failureCode)
		}
	}

	private class DispositionAuthority(
		private val disposition: TrackingStartCommandDisposition,
		private val events: MutableList<String>,
		private val beforeAction: suspend () -> Unit = {},
	) : TrackingLifecycleCommandAuthority {
		override suspend fun reserveStart(): TrackingStartCommand? = null
		override suspend fun reserveRedeliveryRecoveryStart(
			redeliveredCommand: TrackingStartCommand,
		) = com.adsamcik.tracker.tracker.resilience.TrackingRedeliveryRecoveryReservation.Stale
		override suspend fun reserveStop(
			reason: TrackingStopCandidateReason,
			requestedAtEpochMs: Long,
		): TrackingStopCommand? = null

		override fun resolveStart(command: TrackingStartCommand): TrackingStartCommandDisposition {
			events += "resolve"
			return disposition
		}

		override suspend fun <T> withCurrentStart(
			command: TrackingStartCommand,
			action: suspend () -> T,
		) = when (val resolved = resolveStart(command)) {
			TrackingStartCommandDisposition.Allowed -> {
				beforeAction()
				com.adsamcik.tracker.tracker.resilience.LockedTrackingStartResult.Executed(action())
			}
			TrackingStartCommandDisposition.Stale ->
				com.adsamcik.tracker.tracker.resilience.LockedTrackingStartResult.Stale
			is TrackingStartCommandDisposition.BlockedByStop ->
				com.adsamcik.tracker.tracker.resilience.LockedTrackingStartResult.BlockedByStop(
					resolved.stop,
				)
		}

		override fun isStopCurrent(command: TrackingStopCommand): Boolean = false
		override fun isStopActionable(command: TrackingStopCommand): Boolean = false
		override suspend fun markStopHandled(command: TrackingStopCommand): Boolean = false
	}
}
