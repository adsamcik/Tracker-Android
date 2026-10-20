package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerStopRoutingTest {
	@Test
	fun `no-service End closes after an independent background host declines session ownership`() = runTest {
		val events = mutableListOf<String>()
		reserveRouteAndAwaitDurableStop(reserve = { stopCommand() },
			finalizeWithoutService = { it shouldBe stopCommand(); events += "provider-close"; true },
			deliverToRunningService = { _, _ -> events += "background-host-declined"; false },
			finalizeInactiveService = { error("Provider session was already closed") },
			isCurrent = { true }, isActionable = { false }) shouldBe TrackingStopQuiescenceResult.HANDLED
		events shouldBe listOf("background-host-declined", "provider-close")
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
			finalizeWithoutService = { error("A physical runtime must receive End before any provider-only Room query") },
			deliverToRunningService = { delivered, _ ->
				delivered shouldBe command
				true
			},
			finalizeInactiveService = { inactiveFinalizations += 1 },
			isCurrent = { it == command },
			isActionable = { actionable },
			pauseBeforeRetry = {
				pauseCount += 1
				if (pauseCount == 2) {
					actionable = false
				}
			},
		) shouldBe TrackingStopQuiescenceResult.HANDLED

		pauseCount shouldBe 2
		inactiveFinalizations shouldBe 0
	}

	@Test
	fun `awaitable End retains exact inactive fallback when a dispatched owner disappears`() = runTest {
		val command = stopCommand()
		var actionable = true
		val finalized = mutableListOf<TrackingStopCommand>()
		reserveRouteAndAwaitDurableStop(
			reserve = { command },
			deliverToRunningService = { delivered, undelivered -> undelivered(delivered); true },
			finalizeWithoutService = { error("An initially delivered End must not preflight provider-only storage") },
			finalizeInactiveService = { finalized += it; actionable = false },
			isCurrent = { it == command }, isActionable = { actionable },
			pauseBeforeRetry = { error("Exact inactive completion must not poll") },
		) shouldBe TrackingStopQuiescenceResult.HANDLED
		finalized shouldBe listOf(command)
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

	private fun stopCommand() = TrackingStopCommand(
		generation = 2L,
		reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
		requestedAtEpochMs = 1_000L,
	)
}
