package com.adsamcik.tracker.tracker.service

import android.app.Service
import android.os.Build
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.api.TrackingStartRequest
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.LockedTrackingStartResult
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommandDisposition
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerServiceRedeliveryRecoveryTest {
	@Test
	fun `Android redelivery and retry flags both require passive exact-token recovery`() {
		isPassivePreparedStartDelivery(0) shouldBe false
		isPassivePreparedStartDelivery(Service.START_FLAG_REDELIVERY) shouldBe true
		isPassivePreparedStartDelivery(Service.START_FLAG_RETRY) shouldBe true
		isPassivePreparedStartDelivery(
			Service.START_FLAG_REDELIVERY or Service.START_FLAG_RETRY,
		) shouldBe true
	}

	@Test
	fun `providerless shell does not own durable STOP finalization`() {
		PreparedStartRuntimeState().applyStarted shouldBe false
		PreparedStartRuntimeState(
			token = PreparedTrackingStartToken("claimed-only"),
			command = TrackingStartCommand(1L),
		).applyStarted shouldBe false
		PreparedStartRuntimeState(
			token = PreparedTrackingStartToken("provider-apply"),
			command = TrackingStartCommand(2L),
			applyStarted = true,
		).applyStarted shouldBe true
	}

	@Test
	fun `discarding a distinct prepared delivery cannot stop an owned service runtime`() {
		shouldStopServiceForDiscardedPreparedStart(
			activeRuntimePresent = false,
			startFlightInProgress = false,
			preparedRuntimePresent = false,
		) shouldBe true
		shouldStopServiceForDiscardedPreparedStart(
			activeRuntimePresent = true,
			startFlightInProgress = false,
			preparedRuntimePresent = false,
		) shouldBe false
		shouldStopServiceForDiscardedPreparedStart(
			activeRuntimePresent = false,
			startFlightInProgress = true,
			preparedRuntimePresent = false,
		) shouldBe false
		shouldStopServiceForDiscardedPreparedStart(
			activeRuntimePresent = false,
			startFlightInProgress = false,
			preparedRuntimePresent = true,
		) shouldBe false
	}

	@Test
	fun `two Android deaths rebase A to B and then B to distinct C`() = runTest {
		val events = mutableListOf<String>()
		val b = PreparedTrackingStartToken("delivery-b") to TrackingStartCommand(2L)
		val c = PreparedTrackingStartToken("delivery-c") to TrackingStartCommand(3L)

		suspend fun rebase(
			replacement: Pair<PreparedTrackingStartToken, TrackingStartCommand>,
		): AndroidRedeliveryRebaseResult = rebasePreparedAndroidStart(
			command = replacement.second,
			validateAndEnqueue = { command, enqueue ->
				events += "validate:${command.generation}"
				LockedTrackingStartResult.Executed(enqueue())
			},
			platformEnqueue = {
				events += "enqueue:${replacement.first.value}"
				true
			},
			markEnqueued = {
				events += "ack:${replacement.first.value}"
				true
			},
		)

		val runtimeB = rebasedRuntime(b, predecessorStartId = 10)
		rebase(b) shouldBe AndroidRedeliveryRebaseResult.Rebased(enqueueAcknowledged = true)
		resolveRebasedStartDelivery(runtimeB, b.first, b.second) {
			TrackingStartCommandDisposition.Allowed
		}.predecessorStartId shouldBe 10
		val runtimeC = rebasedRuntime(c, predecessorStartId = 11)
		rebase(c) shouldBe AndroidRedeliveryRebaseResult.Rebased(enqueueAcknowledged = true)
		resolveRebasedStartDelivery(runtimeC, c.first, c.second) {
			TrackingStartCommandDisposition.Allowed
		}.predecessorStartId shouldBe 11

		b.first shouldNotBe c.first
		b.second shouldNotBe c.second
		events shouldBe listOf(
			"validate:2",
			"enqueue:delivery-b",
			"ack:delivery-b",
			"validate:3",
			"enqueue:delivery-c",
			"ack:delivery-c",
		)
	}

	@Test
	fun `replacement delivery before enqueue ack retires predecessor once and stays single flight`() =
		runTest {
		val token = PreparedTrackingStartToken("delivery-b")
		val command = TrackingStartCommand(2L)
		val runtime = rebasedRuntime(token to command, predecessorStartId = 10)
		val allowAcknowledgement = CompletableDeferred<Unit>()
		val rebase = async {
			rebasePreparedAndroidStart(
				command = command,
				validateAndEnqueue = { _, enqueue ->
					LockedTrackingStartResult.Executed(enqueue())
				},
				platformEnqueue = { true },
				markEnqueued = {
					allowAcknowledgement.await()
					true
				},
			)
		}
		runCurrent()

		runtime.matches(token, command) shouldBe true
		runtime.consumeRebasedPredecessor(token, command) shouldBe 10
		runtime.consumeRebasedPredecessor(token, command) shouldBe null
		runtime.matches(
			PreparedTrackingStartToken("delivery-c"),
			TrackingStartCommand(3L),
		) shouldBe false
		allowAcknowledgement.complete(Unit)
		rebase.await() shouldBe AndroidRedeliveryRebaseResult.Rebased(enqueueAcknowledged = true)
	}

	@Test
	fun `rejected enqueue acknowledgement remains explicit after Android accepts B`() = runTest {
		rebasePreparedAndroidStart(
			command = TrackingStartCommand(2L),
			validateAndEnqueue = { _, enqueue ->
				LockedTrackingStartResult.Executed(enqueue())
			},
			platformEnqueue = { true },
			markEnqueued = { false },
		) shouldBe AndroidRedeliveryRebaseResult.Rebased(enqueueAcknowledged = false)
	}

	@Test
	fun `matching B retires A before stale delivery handling`() {
		val token = PreparedTrackingStartToken("delivery-b")
		val command = TrackingStartCommand(2L)
		val runtime = rebasedRuntime(token to command, predecessorStartId = 10)

		val delivered = resolveRebasedStartDelivery(runtime, token, command) {
			// Consumption is factual Android ordering and must already have happened even though
			// command policy will reject this B delivery.
			runtime.consumeRebasedPredecessor(token, command) shouldBe null
			TrackingStartCommandDisposition.Stale
		}

		delivered.predecessorStartId shouldBe 10
		delivered.commandDisposition shouldBe TrackingStartCommandDisposition.Stale
	}

	@Test
	fun `STOP superseding recovery prevents replacement enqueue and predecessor retirement`() = runTest {
		val stop = TrackingStopCommand(
			generation = 3L,
			reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
			requestedAtEpochMs = 3_000L,
		)
		var enqueueAttempts = 0
		var acknowledgements = 0

		rebasePreparedAndroidStart(
			command = TrackingStartCommand(2L),
			validateAndEnqueue = { _, _ -> LockedTrackingStartResult.BlockedByStop(stop) },
			platformEnqueue = {
				enqueueAttempts += 1
				true
			},
			markEnqueued = {
				acknowledgements += 1
				true
			},
		) shouldBe AndroidRedeliveryRebaseResult.BlockedByStop(stop)

		enqueueAttempts shouldBe 0
		acknowledgements shouldBe 0
	}

	@Test
	fun `visible manual continuation keeps manual Android origin and logical identity`() {
		val stored = activeManualDescriptor()

		val resolution = resolveTrackingStartDescriptor(
			request = TrackingStartRequest(
				command = TrackingStartCommand(2L),
				isUserInitiated = true,
				isAmbient = false,
			),
			stored = stored,
			bootId = BOOT_ID,
			changedAtEpochMs = 2_000L,
		).shouldBeInstanceOf<TrackingStartDescriptorResolution.Resolved>()

		resolution.origin shouldBe SessionStartOrigin.MANUAL_FOREGROUND_START
		resolution.descriptor.logicalTrackingId shouldBe stored.logicalTrackingId
		resolution.descriptor.serviceRunId shouldNotBe stored.serviceRunId
		resolution.continuationAuthority?.previousServiceRunId shouldBe stored.serviceRunId
	}

	@Test
	fun `passive Android recovery is distinct from visible manual continuation`() {
		val stored = activeManualDescriptor()

		val resolution = resolveTrackingStartDescriptor(
			request = TrackingStartRequest(
				command = TrackingStartCommand(2L),
				isUserInitiated = true,
				isAmbient = false,
				recoveryDescriptor = stored,
			),
			stored = stored,
			bootId = BOOT_ID,
			changedAtEpochMs = 2_000L,
		).shouldBeInstanceOf<TrackingStartDescriptorResolution.Resolved>()

		resolution.origin shouldBe SessionStartOrigin.RECOVERY
		resolution.descriptor.logicalTrackingId shouldBe stored.logicalTrackingId
		resolution.descriptor.serviceRunId shouldNotBe stored.serviceRunId
	}

	@Test
	fun `API 34 foreground-only Location is legal for visible continuation but not redelivery`() {
		val stored = activeManualDescriptor()
		val visible = resolveTrackingStartDescriptor(
			TrackingStartRequest(TrackingStartCommand(2L), true, false),
			stored,
			BOOT_ID,
			2_000L,
		).shouldBeInstanceOf<TrackingStartDescriptorResolution.Resolved>()
		val passive = resolveTrackingStartDescriptor(
			TrackingStartRequest(
				TrackingStartCommand(3L),
				true,
				false,
				recoveryDescriptor = stored,
			),
			stored,
			BOOT_ID,
			2_000L,
		).shouldBeInstanceOf<TrackingStartDescriptorResolution.Resolved>()

		acceptedForegroundSources(setOf(SourceKind.LOCATION), capabilities(visible.origin)) shouldBe
			setOf(SourceKind.LOCATION)
		acceptedForegroundSources(setOf(SourceKind.LOCATION), capabilities(passive.origin)) shouldBe
			emptySet()
	}

	@Test
	fun `passive recovery cannot carry same-boot authority across reboot`() {
		val stored = activeManualDescriptor()

		resolveTrackingStartDescriptor(
			TrackingStartRequest(
				TrackingStartCommand(2L),
				true,
				false,
				recoveryDescriptor = stored,
			),
			stored,
			"boot-2",
			2_000L,
		) shouldBe TrackingStartDescriptorResolution.Failure("RECOVERY_DESCRIPTOR_STALE")
	}

	private fun capabilities(origin: SessionStartOrigin) = ForegroundSourceCapabilities(
		sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
		startOrigin = origin,
		hasForegroundLocationPermission = true,
		hasBackgroundLocationPermission = false,
		locationHardwareAvailable = true,
		activity = false,
		steps = false,
		pressure = false,
		wifi = false,
		cell = false,
	)

	private fun rebasedRuntime(
		replacement: Pair<PreparedTrackingStartToken, TrackingStartCommand>,
		predecessorStartId: Int,
	) = PreparedStartRuntimeState(
		token = replacement.first,
		command = replacement.second,
		platformOwned = true,
		pendingPredecessor = RebasedPredecessorDelivery(
			replacementToken = replacement.first,
			replacementCommandGeneration = replacement.second.generation,
			predecessorStartId = predecessorStartId,
		),
	)

	private fun activeManualDescriptor() = ActiveTrackingSessionDescriptor(
		isUserInitiated = true,
		isAmbient = false,
		policyTier = PolicyTier.PRECISION,
		logicalTrackingId = "logical-session",
		serviceRunId = "old-service-run",
		restartBootId = BOOT_ID,
		restartToken = "restart-token",
	)

	private companion object {
		const val BOOT_ID = "boot-1"
	}
}
