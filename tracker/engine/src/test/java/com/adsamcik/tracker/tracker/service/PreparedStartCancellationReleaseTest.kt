package com.adsamcik.tracker.tracker.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackerServiceContract
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.SourcePlanIdentity
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommandDisposition
import com.adsamcik.tracker.tracker.source.coordinator.DesiredPlanStatus
import com.adsamcik.tracker.tracker.source.coordinator.SessionReconfigureResult
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionPlanInputs
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionReconfigureOutcome
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith

/** Calls the real service cleanup with controlled compensation and Android stop; no provider activation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PreparedStartCancellationReleaseTest {
	private val token = PreparedTrackingStartToken("old-token")
	private val command = TrackingStartCommand(1L)

	@Test
	fun `concurrent service reconfigurations publish each exact descriptor before the next apply`() = runTest {
		val service = service(PreparedStartRuntimeState(token, command))
		val sourceSession = mockk<TrackerServiceSourceSession>()
		service.sourceSession = sourceSession
		val firstInputs = mockk<SourceSessionPlanInputs>()
		val nextInputs = mockk<SourceSessionPlanInputs>()
		val firstReference = SourceCallerReplayReference("first-reference")
		val nextReference = SourceCallerReplayReference("next-reference")
		val initial = ActiveTrackingSessionDescriptor(true, false, PolicyTier.ACTIVE,
			logicalTrackingId = "logical", serviceRunId = "run")
		val firstDescriptor = initial.copy(sourceCallerAuthorityReference = firstReference)
		val nextDescriptor = initial.copy(sourceCallerAuthorityReference = nextReference)
		writeField(service, "activeSessionDescriptor", initial)
		var stored = initial
		val firstApplied = SourceSessionReconfigureOutcome.Applied(
			SessionReconfigureResult.Applied(1L, emptyList(), DesiredPlanStatus.EFFECTIVE, firstReference))
		val nextApplied = SourceSessionReconfigureOutcome.Applied(
			SessionReconfigureResult.Applied(2L, emptyList(), DesiredPlanStatus.EFFECTIVE, nextReference))
		coEvery { sourceSession.reconfigure(firstInputs) } coAnswers { stored = firstDescriptor; firstApplied }
		coEvery { sourceSession.reconfigure(nextInputs) } coAnswers { stored = nextDescriptor; nextApplied }
		val readingFirst = CompletableDeferred<Unit>()
		val releaseFirst = CompletableDeferred<Unit>()
		coEvery { sourceSession.persistedDescriptorForActiveSession(firstReference) } coAnswers {
			readingFirst.complete(Unit)
			releaseFirst.await()
			stored.takeIf { it.sourceCallerAuthorityReference == firstReference }
		}
		coEvery { sourceSession.persistedDescriptorForActiveSession(nextReference) } coAnswers { stored }
		val first = async { reconfigure(service, firstInputs) }
		readingFirst.await()
		val next = async { reconfigure(service, nextInputs) }
		runCurrent()
		coVerify(exactly = 0) { sourceSession.reconfigure(nextInputs) }
		stored shouldBe firstDescriptor
		releaseFirst.complete(Unit)
		first.await() shouldBe firstApplied
		next.await() shouldBe nextApplied
		readField(service, "activeSessionDescriptor") shouldBe nextDescriptor
		shouldStopAfterRuntimePermissionReconfigure(firstApplied, 2) shouldBe false
		shouldStopAfterRuntimePermissionReconfigure(nextApplied, 2) shouldBe false
	}

	@Test
	fun `runtime permission changes defer source reconfiguration until presentation is published`() = runTest {
		val service = service(PreparedStartRuntimeState(token, command, applyStarted = true, applied = true))
		val sourceSession = mockk<TrackerServiceSourceSession>()
		service.sourceSession = sourceSession
		writeField(service, "activeSessionDescriptor", ActiveTrackingSessionDescriptor(
			true, false, PolicyTier.ACTIVE, logicalTrackingId = "logical", serviceRunId = "run"))
		(readField(service, "startSingleFlight") as AtomicBoolean).set(true)
		ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
			.set(service, ApplicationProvider.getApplicationContext<Context>())
		val host = service.backgroundLocationHost
		every { host.hostedSources } returns emptySet()
		every { host.leases } returns MutableStateFlow(emptyMap())
		every { host.active } returns MutableStateFlow(null)
		every { service["resolveAcceptedForegroundSources"](
			emptySet<SourceKind>(), SessionStartOrigin.POLICY_RECONCILIATION) } returns emptySet<SourceKind>()
		every { service.controller.isServiceRunning } returns true
		coEvery { service["reconcileLocationMotionScope"]() } returns Unit
		invokeServiceSuspend(service, "onRuntimePermissionChanged")
		(readField(service, "runtimePermissionReconfigurePending") as AtomicBoolean).get() shouldBe true
		coVerify(exactly = 0) { sourceSession.reconfigure(any()) }
		readField(service, "sessionInfo") shouldBe null
		(readField(service, "startSingleFlight") as AtomicBoolean).get() shouldBe true
	}

	@Test
	fun `null Android foreground service result is not acknowledged as enqueued`() {
		val context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
			override fun getApplicationContext(): Context = this
			override fun startForegroundService(service: Intent): ComponentName? = null
		}
		TrackerServiceApi.enqueueBackgroundLocationHost(context, "host-ticket") shouldBe false
	}

	@Test
	fun `background host promotes its startup shell before waiting for authority and rejects cancelled tickets`() = runTest {
		val service = service(PreparedStartRuntimeState())
		val host = service.backgroundLocationHost
		val controller = service.controller
		val coordinator = service.trackingStartRequestCoordinator
		val serviceJob = Job()
		every { service.coroutineContext } returns StandardTestDispatcher(testScheduler) + serviceJob
		service.bootClockDomainProvider = BootClockDomainProvider { "boot" }
		val pendingTickets = mutableSetOf("host-ticket")
		every { host.hasPendingForegroundWork } answers { pendingTickets.isNotEmpty() }
		var promoted = false
		every { acknowledgeForegroundDelivery(service) } answers { promoted = true; true }
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		coEvery { host.acceptDelivery("host-ticket", service, any(), true, any()) } coAnswers {
			promoted shouldBe true
			entered.complete(Unit)
			release.await()
			pendingTickets.remove("host-ticket")
			false
		}
		coEvery { host.acceptDelivery("cancelled-ticket", service, any(), true, any()) } returns false
		try {
			val intent = Intent(TrackerServiceContract.ACTION_ACQUIRE_BACKGROUND_LOCATION_HOST)
				.putExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET, "host-ticket")
			service.onStartCommand(intent, 0, 7)
			promoted shouldBe true
			runCurrent()
			entered.await()
			service.onStartCommand(intent.putExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET,
				"cancelled-ticket"), 0, 8)
			runCurrent()
			verify(exactly = 0) { service.stopSelfResult(any()) }
			release.complete(Unit)
			runCurrent()
			verify(exactly = 2) { acknowledgeForegroundDelivery(service) }
			verify(exactly = 0) { service.stopSelfResult(7) }
			verify(exactly = 1) { service.stopSelfResult(8) }
			verify(exactly = 0) { controller.updateServiceRunning(true) }
			coVerify(exactly = 0) { coordinator.apply(any(), any()) }
			coVerify(exactly = 1) { host.acceptDelivery("cancelled-ticket", service, any(), true, any()) }
		} finally { serviceJob.cancel() }
	}

	@Test
	fun `missing background ticket acknowledges foreground before stopping`() {
		val service = service(PreparedStartRuntimeState())
		var cleanupPromoted = false
		every { acknowledgeForegroundDelivery(service) } answers { cleanupPromoted = true; true }
		every { service.stopSelfResult(7) } answers { cleanupPromoted shouldBe true; true }
		val intent = Intent(TrackerServiceContract.ACTION_ACQUIRE_BACKGROUND_LOCATION_HOST)
		service.onStartCommand(intent, 0, 7)
		verify(exactly = 1) { acknowledgeForegroundDelivery(service) }
		verify(exactly = 1) { service.stopSelfResult(7) }
	}

	@Test
	fun `stale manual Start acknowledges foreground before rejecting its exact delivery`() = runTest {
		val service = service(PreparedStartRuntimeState())
		val coordinator = service.trackingStartRequestCoordinator
		val host = service.backgroundLocationHost
		var pendingHost = true
		every { host.hasPendingForegroundWork } answers { pendingHost }
		service.applicationScope = this
		service.trackingLifecycleCommandAuthority = mockk()
		every { service.trackingLifecycleCommandAuthority.resolveStart(command) } returns TrackingStartCommandDisposition.Stale
		var acknowledged = false
		every { acknowledgeForegroundDelivery(service) } answers { acknowledged = true; true }
		every { service.stopSelfResult(7) } answers { acknowledged shouldBe true; true }
		coEvery { coordinator.compensate(token, command, "START_COMMAND_STALE_ON_DELIVERY") } coAnswers {
			acknowledged shouldBe true
			Unit
		}
		service.onStartCommand(preparedIntent(), 0, 7)
		runCurrent()
		verify(exactly = 1) { acknowledgeForegroundDelivery(service) }
		verify(exactly = 0) { service.stopSelfResult(any()) }
		coVerify(exactly = 1) { coordinator.compensate(token, command, "START_COMMAND_STALE_ON_DELIVERY") }
		coVerify(exactly = 0) { coordinator.apply(any(), any()) }
		pendingHost = false
		TrackerService::class.java.getDeclaredMethod("discardBackgroundHostStart", Boolean::class.javaPrimitiveType)
			.apply { isAccessible = true }.invoke(service, false)
		verify(exactly = 1) { service.stopSelfResult(7) }
	}

	@Test
	fun `session End retains the service while an independent background host request is pending`() = runTest {
		for (info in listOf(null, TrackerSessionInfo(true))) {
			val service = service(PreparedStartRuntimeState())
			val host = service.backgroundLocationHost
			every { host.hasPendingForegroundWork } returns true
			writeField(service, "sessionInfo", info)
			coEvery { service["finishLiveSessionKeepingBackground"]() } returns Unit
			releaseStopped(service)
			coVerify(exactly = 1) { service["finishLiveSessionKeepingBackground"]() }
			verify(exactly = 0) { service.stopSelfResult(any()) }
			verify(exactly = 0) { service.stopSelf() }
		}
	}

	@Test
	fun `fresh manual foreground shell permits a claim longer than the recovery completion deadline`() = runTest {
		val service = service(PreparedStartRuntimeState())
		val coordinator = service.trackingStartRequestCoordinator
		val serviceJob = Job()
		every { service.coroutineContext } returns StandardTestDispatcher(testScheduler) + serviceJob
		service.trackingLifecycleCommandAuthority = mockk(relaxed = true)
		every { service.trackingLifecycleCommandAuthority.resolveStart(command) } returns TrackingStartCommandDisposition.Allowed
		service.trackingStartupGate = mockk()
		every { service.trackingStartupGate.isReady } returns true
		every { service.trackingStartupGate.currentGeneration } returns 1L
		coEvery { service.trackingStartupGate.awaitReady() } returns TrackingStartupResult.Ready(false, 0L)
		coEvery { service.trackingStartupGate.withReadyGenerationWork<Unit>(1L, any()) } coAnswers {
			secondArg<suspend () -> Unit>().invoke()
		}
		every { promoteStartup(service) } returns true
		var claimCompleted = false
		coEvery { service.trackingStartRequestCoordinator.claimForService(token, command.generation) } coAnswers {
			delay(45_000L)
			claimCompleted = true
			TrackingServicePreparedStartClaim.Rejected("CONTROLLED_REJECTION", compensatePreparedState = false)
		}
		mockkObject(TrackerNotificationManager.Companion)
		try {
			every { TrackerNotificationManager.postStartFailedNotification(any()) } returns Unit
			service.onStartCommand(preparedIntent(), 0, 7)
			advanceUntilIdle()
			claimCompleted shouldBe true
			testScheduler.currentTime shouldBe 45_000L
			verify(exactly = 1) { promoteStartup(service) }
			coVerify(exactly = 0) { coordinator.compensate(any(), any(), any()) }
		} finally {
			serviceJob.cancel()
			unmockkObject(TrackerNotificationManager.Companion)
		}
	}

	@Test
	fun `normal shell timeout keeps its exact Start pending beyond the callback cleanup reserve`() = runTest {
		val service = service(PreparedStartRuntimeState(token, command))
		val controller = service.controller
		val coordinator = service.trackingStartRequestCoordinator
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		var compensated = false
		coEvery { coordinator.compensate(token, command,
			"PREPARED_FOREGROUND_COMPLETION_TIMED_OUT") } coAnswers {
			entered.complete(Unit)
			release.await()
			compensated = true
		}
		mockkObject(TrackerNotificationManager.Companion)
		try {
			every { TrackerNotificationManager.postStartFailedNotification(any()) } returns Unit
			val abandoned = launch { abandon(service) }
			runCurrent()
			entered.isCompleted shouldBe true
			advanceTimeBy(500L)
			runCurrent()
			abandoned.isActive shouldBe true
			compensated shouldBe false
			readRuntime(service).token shouldBe token
			verify(exactly = 0) { controller.updateSessionStart(command.generation, starting = false) }
			verify(exactly = 0) { service.stopSelfResult(any()) }
			release.complete(Unit)
			advanceUntilIdle()
			abandoned.isCompleted shouldBe true
			compensated shouldBe true
			readRuntime(service) shouldBe PreparedStartRuntimeState()
			verify(exactly = 1) { controller.updateSessionStart(command.generation, starting = false) }
			verify(exactly = 1) { service.stopSelfResult(7) }
			coVerify(exactly = 1) { coordinator.compensate(token, command,
				"PREPARED_FOREGROUND_COMPLETION_TIMED_OUT") }
		} finally {
			unmockkObject(TrackerNotificationManager.Companion)
		}
	}

	@Test
	fun `discarded prepared delivery compensates after Android cancels the service scope`() = runTest {
		val service = service(PreparedStartRuntimeState())
		val controller = service.controller
		val serviceJob = Job()
		every { service.coroutineContext } returns StandardTestDispatcher(testScheduler) + serviceJob
		service.applicationScope = backgroundScope
		every { service.stopSelfResult(7) } answers { serviceJob.cancel(); true }
		var compensated = false
		coEvery { service.trackingStartRequestCoordinator.compensate(token, command, "CONTROLLED_REJECTION") } coAnswers {
			delay(100L)
			compensated = true
		}
		discard(service, "CONTROLLED_REJECTION")
		serviceJob.isCancelled shouldBe true
		runCurrent()
		advanceTimeBy(100L)
		runCurrent()
		compensated shouldBe true
		verify(atLeast = 1) { controller.updateSessionStart(command.generation, starting = false) }
		coVerify(exactly = 1) {
			service.trackingStartRequestCoordinator.compensate(token, command, "CONTROLLED_REJECTION")
		}
	}

	@Test
	fun `compensation storage failure releases the shell without crashing either cleanup owner`() = runTest {
		val service = service(PreparedStartRuntimeState(token, command))
		service.applicationScope = backgroundScope
		coEvery { service.trackingStartRequestCoordinator.compensate(token, command, any()) } throws
			SQLiteException("Controlled compensation failure")
		mockkObject(TrackerNotificationManager.Companion)
		try {
			every { TrackerNotificationManager.postStartFailedNotification(any()) } returns Unit
			val abandoned = launch { abandon(service) }
			advanceUntilIdle()
			abandoned.isCancelled shouldBe false
			readRuntime(service) shouldBe PreparedStartRuntimeState()
			verify(exactly = 1) { service.stopSelfResult(7) }
			discard(service, "CONTROLLED_REJECTION")
			runCurrent()
			coVerify(exactly = 2) { service.trackingStartRequestCoordinator.compensate(token, command, any()) }
		} finally {
			unmockkObject(TrackerNotificationManager.Companion)
		}
	}

	@Test
	fun `cancelled real shell finishes bounded compensation before releasing its exact runtime`() = runTest {
		val service = service(PreparedStartRuntimeState(token, command))
		val controller = service.controller
		var compensated = false
		coEvery { service.trackingStartRequestCoordinator.compensate(token, command, "START_DELIVERY_CANCELLED") } coAnswers {
			verify(exactly = 1) { controller.updateSessionStart(command.generation, starting = false) }
			delay(100L)
			compensated = true
		}
		val job = launch {
			try { awaitCancellation() } catch (cancelled: CancellationException) {
				retire(service)
				throw cancelled
			}
		}
		runCurrent()
		job.cancel()
		runCurrent()
		readRuntime(service).token shouldBe token
		verify(exactly = 0) { service.stopSelfResult(any()) }
		advanceUntilIdle()
		compensated shouldBe true
		readRuntime(service) shouldBe PreparedStartRuntimeState()
		verify(exactly = 1) { service.stopSelfResult(7) }
		job.isCancelled shouldBe true
	}

	@Test
	fun `stuck compensation reaches existing 250ms bound then releases the real shell`() = runTest {
		val service = service(PreparedStartRuntimeState(token, command))
		val controller = service.controller
		coEvery { service.trackingStartRequestCoordinator.compensate(token, command, "START_DELIVERY_CANCELLED") } coAnswers {
			awaitCancellation()
		}
		retire(service)
		testScheduler.currentTime shouldBe 250L
		verify(exactly = 1) { controller.updateSessionStart(command.generation, starting = false) }
		readRuntime(service) shouldBe PreparedStartRuntimeState()
		verify(exactly = 1) { service.stopSelfResult(7) }
	}

	@Test
	fun `real cancellation cannot release an Android owned or applied successor`() = runTest {
		for (runtime in listOf(
			PreparedStartRuntimeState(token, command, platformOwned = true),
			PreparedStartRuntimeState(token, command, applyStarted = true),
			PreparedStartRuntimeState(token, command, applied = true),
			PreparedStartRuntimeState(PreparedTrackingStartToken("successor"), TrackingStartCommand(2L)),
		)) {
			val service = service(runtime)
			val controller = service.controller
			retire(service)
			readRuntime(service) shouldBe runtime
			verify(exactly = 1) { controller.updateSessionStart(command.generation, starting = false) }
			verify(exactly = 0) { controller.updateSessionStart(2L, starting = false) }
			coVerify(exactly = 0) { service.trackingStartRequestCoordinator.compensate(any(), any(), any()) }
			verify(exactly = 0) { service.stopSelfResult(any()) }
		}
	}

	@Test
	fun `successor delivered while compensation waits retains its real runtime and shell`() = runTest {
		val service = service(PreparedStartRuntimeState(token, command))
		val controller = service.controller
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		coEvery { service.trackingStartRequestCoordinator.compensate(token, command, "START_DELIVERY_CANCELLED") } coAnswers {
			entered.complete(Unit)
			release.await()
		}
		val job = launch { retire(service) }
		entered.await()
		val successor = PreparedStartRuntimeState(PreparedTrackingStartToken("successor"), TrackingStartCommand(2L))
		adopt(service, requireNotNull(successor.token), requireNotNull(successor.command))
		release.complete(Unit)
		job.join()
		readRuntime(service) shouldBe successor
		verify(exactly = 1) { controller.updateSessionStart(command.generation, starting = false) }
		verify(exactly = 0) { controller.updateSessionStart(2L, starting = false) }
		verify(exactly = 0) { service.stopSelfResult(any()) }
	}

	// Keep both competing threads and latch deadlines together to prove final release cannot erase successor state.
	@Suppress("LongMethod")
	@Test
	fun `actual final match and shell release cannot interleave with successor adoption`() {
		val finalMatchEntered = CountDownLatch(1)
		val allowOldRelease = CountDownLatch(1)
		val successorAttempted = CountDownLatch(1)
		val successorInstalled = CountDownLatch(1)
		val failure = AtomicReference<Throwable?>(null)
		val oldRuntime = spyk(PreparedStartRuntimeState(token, command))
		every { oldRuntime.matches(token, command) } answers {
			finalMatchEntered.countDown()
			check(allowOldRelease.await(10L, TimeUnit.SECONDS))
			callOriginal()
		}
		val service = service(oldRuntime)
		val newToken = PreparedTrackingStartToken("successor")
		val newCommand = TrackingStartCommand(2L)
		val newDescriptor = mockk<ActiveTrackingSessionDescriptor>()
		val newInfo = TrackerSessionInfo(true)
		val monitor = readField(service, "preparedStartOwnershipMonitor")
		val oldThread = thread(name = "old-start-release") {
			try { rollback(service) shouldBe true } catch (caught: Throwable) { failure.set(caught) }
		}
		var successorThread: Thread? = null
		try {
			finalMatchEntered.await(2L, TimeUnit.SECONDS) shouldBe true
			val competing = thread(name = "new-start-adoption") {
				try {
					successorAttempted.countDown()
					adopt(service, newToken, newCommand)
					updateOwned(service, newToken, newCommand) {
						writeField(service, "activeSessionDescriptor", newDescriptor)
						writeField(service, "sessionInfo", newInfo)
						writeField(service, "sessionStartOrigin", SessionStartOrigin.MANUAL_FOREGROUND_START)
						writeField(service, "foregroundStarted", true)
						writeField(service, "foregroundIsStartupShell", true)
					}
					successorInstalled.countDown()
				} catch (caught: Throwable) { failure.set(caught) }
			}
			successorThread = competing
			successorAttempted.await(2L, TimeUnit.SECONDS) shouldBe true
			val bean = ManagementFactory.getThreadMXBean()
			val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
			while (System.nanoTime() < deadline) {
				val state = bean.getThreadInfo(competing.id)
				if (state?.threadState == Thread.State.BLOCKED &&
					state.lockInfo?.identityHashCode == System.identityHashCode(monitor)) {
					break
				}
				if (successorInstalled.count == 0L) {
					break
				}
				Thread.yield()
			}
			val blocked = bean.getThreadInfo(competing.id)
			blocked?.threadState shouldBe Thread.State.BLOCKED
			blocked?.lockInfo?.identityHashCode shouldBe System.identityHashCode(monitor)
			successorInstalled.count shouldBe 1L
		} finally {
			allowOldRelease.countDown()
			oldThread.join(2_000L)
			successorThread?.join(2_000L)
		}
		oldThread.isAlive shouldBe false
		successorThread?.isAlive shouldBe false
		failure.get()?.let { throw it }
		successorInstalled.count shouldBe 0L
		readRuntime(service) shouldBe PreparedStartRuntimeState(newToken, newCommand)
		readField(service, "activeSessionDescriptor") shouldBe newDescriptor
		readField(service, "sessionInfo") shouldBe newInfo
		readField(service, "sessionStartOrigin") shouldBe SessionStartOrigin.MANUAL_FOREGROUND_START
		readField(service, "foregroundStarted") shouldBe true
		readField(service, "foregroundIsStartupShell") shouldBe true
		verify(exactly = 1) { service.stopSelfResult(7) }
	}

	@Test
	fun `real matched shell rollback retains independently hosted foreground collection`() = runTest {
		val service = service(PreparedStartRuntimeState(token, command))
		val controller = service.controller
		every { service.backgroundLocationHost.hasActiveSources } returns true
		every { service.backgroundLocationHost.hostedSources } returns setOf(SourceKind.WIFI)
		writeField(service, "foregroundStarted", true)
		every {
			promoteBackground(service, setOf(SourceKind.WIFI))
		} returns true
		coEvery { service.trackingStartRequestCoordinator.compensate(token, command, "START_DELIVERY_CANCELLED") } returns Unit
		retire(service)
		readRuntime(service) shouldBe PreparedStartRuntimeState()
		verify(exactly = 1) { controller.updateSessionStart(command.generation, starting = false) }
		readField(service, "foregroundStarted") shouldBe true
		verify(exactly = 1) {
			promoteBackground(service, setOf(SourceKind.WIFI))
		}
		verify(exactly = 0) { service.stopSelfResult(any()) }
		verify(exactly = 0) { service.stopForeground(any<Int>()) }
	}

	@Test
	fun `old acceptance or APPLY publication cannot mutate a successor through actual ownership helper`() {
		val service = service(PreparedStartRuntimeState(token, command))
		val newToken = PreparedTrackingStartToken("successor")
		val newCommand = TrackingStartCommand(2L)
		adopt(service, newToken, newCommand)
		var published = false
		updateOwned(service, token, command) {
			published = true
			writeRuntime(service, readRuntime(service).copy(applyStarted = true, applied = true))
			writeField(service, "sessionInfo", TrackerSessionInfo(true))
		} shouldBe null
		published shouldBe false
		readRuntime(service) shouldBe PreparedStartRuntimeState(newToken, newCommand)
		readField(service, "sessionInfo") shouldBe null
	}

	private fun service(runtime: PreparedStartRuntimeState): TrackerService = spyk(TrackerService(), recordPrivateCalls = true).also {
		it.trackingStartRequestCoordinator = mockk()
		it.backgroundLocationHost = mockk()
		it.controller = mockk()
		every { it.controller.sessionInfoFlow } returns MutableStateFlow<TrackerSessionInfo?>(null)
		every { it.controller.updateSessionStart(command.generation, starting = false) } returns Unit
		every { it.backgroundLocationHost.hasActiveSources } returns false
		every { it.backgroundLocationHost.hasPendingForegroundWork } returns false
		every { it.stopSelfResult(any()) } returns true
		every { acknowledgeForegroundDelivery(it) } returns true
		writeRuntime(it, runtime)
	}

	private fun writeRuntime(service: TrackerService, runtime: PreparedStartRuntimeState) {
		writeField(service, "preparedStartRuntime", runtime)
	}

	private suspend fun reconfigure(
		service: TrackerService,
		inputs: SourceSessionPlanInputs,
	): SourceSessionReconfigureOutcome =
		suspendCoroutineUninterceptedOrReturn { continuation ->
			val method = TrackerService::class.java.getDeclaredMethod("reconfigureSourceSession",
				SourceSessionPlanInputs::class.java, Continuation::class.java).apply { isAccessible = true }
			try { method.invoke(service, inputs, continuation) } catch (failure: InvocationTargetException) {
				throw failure.targetException
			}
		}

	private suspend fun invokeServiceSuspend(service: TrackerService, name: String): Unit =
		suspendCoroutineUninterceptedOrReturn { continuation ->
			val method = TrackerService::class.java.getDeclaredMethod(name, Continuation::class.java)
				.apply { isAccessible = true }
			try { method.invoke(service, continuation) } catch (failure: InvocationTargetException) {
				throw failure.targetException
			}
		}

	private fun promoteBackground(service: TrackerService, sources: Set<SourceKind>): Boolean =
		TrackerService::class.java.getDeclaredMethod("ensureForegroundStarted",
			Set::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType,
			Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
			.apply { isAccessible = true }.invoke(service, sources, false, null, false, true) as Boolean

	private fun acknowledgeForegroundDelivery(service: TrackerService): Boolean =
		TrackerService::class.java.getDeclaredMethod("acknowledgeForegroundDelivery")
			.apply { isAccessible = true }.invoke(service) as Boolean

	private fun promoteStartup(service: TrackerService): Boolean =
		TrackerService::class.java.getDeclaredMethod("ensureForegroundStarted",
			Set::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType,
			Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
			.apply { isAccessible = true }.invoke(service, setOf(SourceKind.LOCATION), false, true, true, false) as Boolean

	private fun preparedIntent() = Intent(TrackerServiceContract.ACTION_PREPARED_START)
		.putExtra(TrackerServiceContract.ARG_PREPARED_START_TOKEN, token.value)
		.putExtra(TrackerServiceContract.ARG_LIFECYCLE_COMMAND_GENERATION, command.generation)
		.putExtra(TrackerServiceContract.ARG_PREPARED_SOURCE_MASK_HINT, 1L)
		.putExtra(TrackerServiceContract.ARG_PREPARED_USER_INITIATED_HINT, true)
		.putExtra(TrackerServiceContract.ARG_PREPARED_MANUAL_COMPLETION_WAIT_HINT, true)

	private fun discard(service: TrackerService, failureCode: String): Int =
		TrackerService::class.java.getDeclaredMethod("discardPreparedStart",
			PreparedTrackingStartToken::class.java, TrackingStartCommand::class.java,
			Int::class.javaPrimitiveType, String::class.java)
			.apply { isAccessible = true }.invoke(service, token, command, 7, failureCode) as Int

	private suspend fun abandon(service: TrackerService): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
		val method = TrackerService::class.java.getDeclaredMethod("abandonPreparedStartShell",
			PreparedTrackingStartToken::class.java, TrackingStartCommand::class.java,
			Int::class.javaPrimitiveType, Continuation::class.java).apply { isAccessible = true }
		try { method.invoke(service, token, command, 7, continuation) } catch (failure: InvocationTargetException) {
			throw failure.targetException
		}
	}

	private suspend fun releaseStopped(service: TrackerService): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
		val method = TrackerService::class.java.getDeclaredMethod("releaseStoppedService",
			Int::class.javaObjectType, Continuation::class.java).apply { isAccessible = true }
		try { method.invoke(service, null, continuation) } catch (failure: InvocationTargetException) {
			throw failure.targetException
		}
	}

	private fun writeField(service: TrackerService, name: String, value: Any?) {
		TrackerService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
	}

	private fun readField(service: TrackerService, name: String): Any? =
		TrackerService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

	private fun adopt(service: TrackerService, token: PreparedTrackingStartToken, command: TrackingStartCommand) {
		TrackerService::class.java.getDeclaredMethod("adoptPreparedStartRuntime",
			PreparedTrackingStartToken::class.java, TrackingStartCommand::class.java)
			.apply { isAccessible = true }.invoke(service, token, command)
	}

	private fun rollback(service: TrackerService): Boolean =
		TrackerService::class.java.getDeclaredMethod("rollbackRejectedPreparedStartRuntime",
			PreparedTrackingStartToken::class.java, TrackingStartCommand::class.java, Int::class.javaPrimitiveType)
			.apply { isAccessible = true }.invoke(service, token, command, 7) as Boolean

	private fun updateOwned(service: TrackerService, token: PreparedTrackingStartToken,
		command: TrackingStartCommand, action: () -> Unit): Any? =
		TrackerService::class.java.getDeclaredMethod("withPreparedStartOwnership",
			PreparedTrackingStartToken::class.java, TrackingStartCommand::class.java,
			kotlin.jvm.functions.Function0::class.java)
			.apply { isAccessible = true }.invoke(service, token, command, action)

	private fun readRuntime(service: TrackerService): PreparedStartRuntimeState =
		TrackerService::class.java.getDeclaredField("preparedStartRuntime").apply { isAccessible = true }
			.get(service) as PreparedStartRuntimeState

	/** Keeps the production cleanup private rather than adding a test-only service interface. */
	private suspend fun retire(service: TrackerService): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
		val method = TrackerService::class.java.getDeclaredMethod(
			"retireCancelledPreparedStart", PreparedTrackingStartToken::class.java, TrackingStartCommand::class.java,
			Int::class.javaPrimitiveType, Continuation::class.java,
		).apply { isAccessible = true }
		try { method.invoke(service, token, command, 7, continuation) } catch (failure: InvocationTargetException) {
			throw failure.targetException
		}
	}
}

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedForegroundCompletionTest {
	@Test
	fun `automatic foreground completion keeps its finite recovery budget`() = runTest {
		var finished = false
		val completed = withPreparedForegroundCompletion(false, false, 30_000L) {
			delay(45_000L)
			finished = true
		}
		completed shouldBe null
		finished shouldBe false
		testScheduler.currentTime shouldBe 30_000L
	}

	@Test
	fun `passive foreground redelivery keeps its finite budget despite a user initiated hint`() = runTest {
		var finished = false
		val completed = withPreparedForegroundCompletion(true, true, 30_000L) {
			delay(45_000L)
			finished = true
		}
		completed shouldBe null
		finished shouldBe false
		testScheduler.currentTime shouldBe 30_000L
	}

	@Test
	fun `fresh manual completion remains cancellable by its owning End or service lifetime`() = runTest {
		val entered = CompletableDeferred<Unit>()
		var released = false
		val pending = async {
			withPreparedForegroundCompletion<Boolean>(false, true, 30_000L) {
				try {
					entered.complete(Unit)
					awaitCancellation()
				} finally {
					released = true
				}
			}
		}
		entered.await()
		val end = CancellationException("Exact Start was ended")
		pending.cancel(end)
		assertFailsWith<CancellationException> { pending.await() }.message shouldBe end.message
		released shouldBe true
	}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class DescriptorTierPublicationTest {
	@Test
	fun `tier update waits for caller and plan publication then preserves the new descriptor`() = runTest {
		val service = spyk(TrackerService())
		val serviceJob = Job()
		every { service.coroutineContext } returns StandardTestDispatcher(testScheduler) + serviceJob
		service.controller = mockk()
		val tiers = MutableStateFlow(PolicyTier.ACTIVE)
		every { service.controller.policyTierFlow } returns tiers
		service.activeTrackingSessionStore = mockk()
		val store = service.activeTrackingSessionStore
		service.sourceSession = mockk()
		val sourceSession = service.sourceSession
		val inputs = mockk<SourceSessionPlanInputs>()
		val initial = ActiveTrackingSessionDescriptor(true, false, PolicyTier.ACTIVE,
			logicalTrackingId = "logical", serviceRunId = "run",
			sourceCallerAuthorityReference = SourceCallerReplayReference("original"))
		val reference = SourceCallerReplayReference("replacement")
		val identity = SourcePlanIdentity(generation = 2L, inputsFingerprint = "a".repeat(64),
			planFingerprint = "b".repeat(64))
		val replacement = initial.copy(sourceCallerAuthorityReference = reference,
			appliedSourcePlanIdentity = identity, desiredSourcePlanIdentity = identity)
		var stored = initial
		writeDescriptor(service, initial)
		coEvery { store.replaceExact(any(), any()) } coAnswers {
			if (stored == firstArg<ActiveTrackingSessionDescriptor>()) {
				stored = secondArg()
			}
			ActiveTrackingSessionStoreResult.Success(stored)
		}
		val applied = SourceSessionReconfigureOutcome.Applied(
			SessionReconfigureResult.Applied(2L, emptyList(), DesiredPlanStatus.EFFECTIVE, reference))
		coEvery { sourceSession.reconfigure(inputs) } coAnswers { stored = replacement; applied }
		val publishing = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		coEvery { sourceSession.persistedDescriptorForActiveSession(reference) } coAnswers {
			publishing.complete(Unit)
			release.await()
			stored
		}
		try {
			TrackerService::class.java.getDeclaredMethod("observeDescriptorTierChanges",
				ActiveTrackingSessionDescriptor::class.java).apply { isAccessible = true }.invoke(service, initial)
			runCurrent()
			val reconfiguration = async { reconfigure(service, inputs) }
			publishing.await()
			tiers.value = PolicyTier.PRECISION
			runCurrent()
			coVerify(exactly = 0) { store.replaceExact(any(), any()) }
			stored shouldBe replacement
			release.complete(Unit)
			reconfiguration.await() shouldBe applied
			runCurrent()
			val updated = replacement.copy(policyTier = PolicyTier.PRECISION)
			stored shouldBe updated
			readDescriptor(service) shouldBe updated
			coVerify(exactly = 1) { store.replaceExact(replacement, updated) }
		} finally { serviceJob.cancel() }
	}

	private suspend fun reconfigure(
		service: TrackerService,
		inputs: SourceSessionPlanInputs,
	): SourceSessionReconfigureOutcome =
		suspendCoroutineUninterceptedOrReturn { continuation ->
			val method = TrackerService::class.java.getDeclaredMethod("reconfigureSourceSession",
				SourceSessionPlanInputs::class.java, Continuation::class.java).apply { isAccessible = true }
			try { method.invoke(service, inputs, continuation) } catch (failure: InvocationTargetException) {
				throw failure.targetException
			}
		}

	private fun writeDescriptor(service: TrackerService, descriptor: ActiveTrackingSessionDescriptor) {
		TrackerService::class.java.getDeclaredField("activeSessionDescriptor")
			.apply { isAccessible = true }.set(service, descriptor)
	}

	private fun readDescriptor(service: TrackerService): ActiveTrackingSessionDescriptor =
		TrackerService::class.java.getDeclaredField("activeSessionDescriptor")
			.apply { isAccessible = true }.get(service) as ActiveTrackingSessionDescriptor
}
