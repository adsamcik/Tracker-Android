package com.adsamcik.tracker.tracker.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.presentation.PresentationQuiescenceResult
import com.adsamcik.tracker.tracker.presentation.SessionPresentationBinding
import com.adsamcik.tracker.tracker.presentation.SessionPresentationLifecycle
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStopOutcome
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationOutboxDispatcher
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjectionLane
import com.adsamcik.tracker.tracker.worker.HistoricalTrajectoryReconstructionWorker
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real session teardown must settle presentation without waiting for unrelated automation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTeardownAutomationIsolationTest {
	@Test
	fun `End settles its presentation while unrelated Activity automation retains the durable hint`() = runTest {
		val service = service()
		val activity = mockk<ActivityAutomationProjectionLane>()
		val effects = mockk<ActivityAutomationOutboxDispatcher>()
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		var drains = 0
		coEvery { activity.drainAvailable() } coAnswers {
			if (++drains == 1) { entered.complete(Unit); release.await() }
			CoordinatorDrainResult.Complete(0L, 0)
		}
		coEvery { effects.drain(any(), any(), any()) } returns 0
		val recovery = SourcePipelineRecovery(mockk(), mockk(), activity, effects, backgroundScope)
		service.sourcePipelineRecovery = recovery
		service.sourceSession = mockk()
		coEvery { service.sourceSession.stop(any(), any(), any()) } returns SourceSessionStopOutcome.Stopped
		service.trackingStartupGate = mockk { every { isReady } returns true }
		every { service.backgroundLocationHost.hasActiveSources } returns true
		service.locationLiveWriterRegistry = mockk(relaxed = true)
		val binding = SessionPresentationBinding("logical", "run", 1L)
		val presentation = mockk<SessionPresentationLifecycle>()
		coEvery { presentation.acknowledgeQuiescedExact(binding, any()) } returns PresentationQuiescenceResult.ACKNOWLEDGED
		val orchestrator = mockk<TrackingOrchestrator>()
		coEvery { orchestrator.shutdown(any()) } returns ShutdownResult(false, false,
			SessionPresentationShutdownReceipt(binding))
		writeField(service, "orchestrator", orchestrator)
		writeField(service, "sessionPresentationLifecycle", presentation)
		writeField(service, "gracefulStopRequested", true)
		val unrelated = backgroundScope.launch { recovery.drainCommittedWork() }
		entered.await()
		mockkObject(HistoricalTrajectoryReconstructionWorker)
		every { HistoricalTrajectoryReconstructionWorker.schedule(any()) } returns Unit
		try {
			val ending = async { teardown(service, ApplicationProvider.getApplicationContext()) }
			runCurrent()
			ending.isCompleted shouldBe true
			ending.await()
			unrelated.isCompleted shouldBe false
			drains shouldBe 1
			coVerifyOrder {
				service.sourceSession.stop(any(), false, any())
				orchestrator.shutdown(any())
				presentation.acknowledgeQuiescedExact(binding, any())
			}
			verify(exactly = 1) { HistoricalTrajectoryReconstructionWorker.schedule(any()) }
			release.complete(Unit)
			runCurrent()
			drains shouldBe 2
			coVerify(exactly = 2) { effects.drain(any(), any(), any()) }
		} finally {
			release.complete(Unit)
			unmockkObject(HistoricalTrajectoryReconstructionWorker)
		}
	}

	private fun service(): TrackerService = spyk(TrackerService(), recordPrivateCalls = true).also {
		it.backgroundLocationHost = mockk()
		writeField(it, "preparedStartRuntime", PreparedStartRuntimeState(
			PreparedTrackingStartToken("old-token"), TrackingStartCommand(1L), applyStarted = true, applied = true))
	}

	private suspend fun teardown(service: TrackerService, context: Context): Unit =
		suspendCoroutineUninterceptedOrReturn { continuation ->
			val method = TrackerService::class.java.getDeclaredMethod("performTeardown", Context::class.java,
				Continuation::class.java).apply { isAccessible = true }
			try { method.invoke(service, context, continuation) } catch (failure: InvocationTargetException) {
				throw failure.targetException
			}
		}

	private fun writeField(service: TrackerService, name: String, value: Any?) {
		TrackerService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
	}
}
