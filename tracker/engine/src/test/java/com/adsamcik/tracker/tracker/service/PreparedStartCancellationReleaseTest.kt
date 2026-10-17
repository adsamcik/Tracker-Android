package com.adsamcik.tracker.tracker.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackerServiceContract
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommandDisposition
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.model.SourceKind
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.concurrent.thread

/** Calls the real service cleanup with controlled compensation and Android stop; no provider activation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PreparedStartCancellationReleaseTest {
	private val token = PreparedTrackingStartToken("old-token")
	private val command = TrackingStartCommand(1L)

	@Test
	fun `null Android foreground service result is not acknowledged as enqueued`() {
		val context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
			override fun getApplicationContext(): Context = this
			override fun startForegroundService(service: Intent): ComponentName? = null
		}
		TrackerServiceApi.enqueueBackgroundLocationHost(context, "host-ticket") shouldBe false
	}

	@Test
	fun `foreground accepted shell permits a claim longer than the pre-promotion deadline`() = runTest {
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
			delay(4_000L)
			claimCompleted = true
			TrackingServicePreparedStartClaim.Rejected("CONTROLLED_REJECTION", compensatePreparedState = false)
		}
		mockkObject(TrackerNotificationManager.Companion)
		try {
			every { TrackerNotificationManager.postStartFailedNotification(any()) } returns Unit
			service.onStartCommand(preparedIntent(), 0, 7)
			advanceUntilIdle()
			claimCompleted shouldBe true
			testScheduler.currentTime shouldBe 4_000L
			verify(exactly = 1) { promoteStartup(service) }
			coVerify(exactly = 0) { coordinator.compensate(any(), any(), any()) }
		} finally {
			serviceJob.cancel()
			unmockkObject(TrackerNotificationManager.Companion)
		}
	}

	@Test
	fun `timed out real shell compensates its exact start before releasing local ownership`() = runTest {
		val service = service(PreparedStartRuntimeState(token, command))
		var compensated = false
		coEvery { service.trackingStartRequestCoordinator.compensate(token, command,
			"PREPARED_FOREGROUND_COMPLETION_TIMED_OUT") } coAnswers {
			delay(100L)
			compensated = true
		}
		mockkObject(TrackerNotificationManager.Companion)
		try {
			every { TrackerNotificationManager.postStartFailedNotification(any()) } returns Unit
			val abandoned = launch { abandon(service) }
			runCurrent()
			readRuntime(service).token shouldBe token
			verify(exactly = 0) { service.stopSelfResult(any()) }
			advanceUntilIdle()
			abandoned.isCompleted shouldBe true
			compensated shouldBe true
			readRuntime(service) shouldBe PreparedStartRuntimeState()
			verify(exactly = 1) { service.stopSelfResult(7) }
		} finally {
			unmockkObject(TrackerNotificationManager.Companion)
		}
	}

	@Test
	fun `discarded prepared delivery compensates after Android cancels the service scope`() = runTest {
		val service = service(PreparedStartRuntimeState())
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
		var compensated = false
		coEvery { service.trackingStartRequestCoordinator.compensate(token, command, "START_DELIVERY_CANCELLED") } coAnswers {
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
		coEvery { service.trackingStartRequestCoordinator.compensate(token, command, "START_DELIVERY_CANCELLED") } coAnswers {
			awaitCancellation()
		}
		retire(service)
		testScheduler.currentTime shouldBe 250L
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
			retire(service)
			readRuntime(service) shouldBe runtime
			coVerify(exactly = 0) { service.trackingStartRequestCoordinator.compensate(any(), any(), any()) }
			verify(exactly = 0) { service.stopSelfResult(any()) }
		}
	}

	@Test
	fun `successor delivered while compensation waits retains its real runtime and shell`() = runTest {
		val service = service(PreparedStartRuntimeState(token, command))
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
		every { service.backgroundLocationHost.hasActiveSources } returns true
		every { service.backgroundLocationHost.hostedSources } returns setOf(SourceKind.WIFI)
		writeField(service, "foregroundStarted", true)
		every {
			promoteBackground(service, setOf(SourceKind.WIFI))
		} returns true
		coEvery { service.trackingStartRequestCoordinator.compensate(token, command, "START_DELIVERY_CANCELLED") } returns Unit
		retire(service)
		readRuntime(service) shouldBe PreparedStartRuntimeState()
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
		every { it.backgroundLocationHost.hasActiveSources } returns false
		every { it.stopSelfResult(any()) } returns true
		writeRuntime(it, runtime)
	}

	private fun writeRuntime(service: TrackerService, runtime: PreparedStartRuntimeState) {
		writeField(service, "preparedStartRuntime", runtime)
	}

	private fun promoteBackground(service: TrackerService, sources: Set<SourceKind>): Boolean =
		TrackerService::class.java.getDeclaredMethod("ensureForegroundStarted",
			Set::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType,
			Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
			.apply { isAccessible = true }.invoke(service, sources, false, null, false, true) as Boolean

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
