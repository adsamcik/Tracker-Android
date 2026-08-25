package com.adsamcik.tracker.app.receiver

import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryResult
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainResult
import dagger.hilt.android.EntryPointAccessors
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BootReceiverTest {
	@AfterEach
	fun tearDown() = unmockkAll()

	@Test
	fun `non boot broadcasts do not schedule recovery`() {
		val scheduler = mockk<BootTrackingRecoveryScheduler>(relaxed = true)
		val context = configuredContext(scheduler)
		val intent = mockk<Intent> {
			every { action } returns Intent.ACTION_POWER_CONNECTED
		}

		BootReceiver().onReceive(context, intent)

		verify(exactly = 0) { scheduler.enqueue() }
	}

	@Test
	fun `boot receiver only enqueues unique durable recovery`() {
		val scheduler = mockk<BootTrackingRecoveryScheduler>(relaxed = true)
		val context = configuredContext(scheduler)
		val intent = mockk<Intent> {
			every { action } returns Intent.ACTION_BOOT_COMPLETED
		}

		BootReceiver().onReceive(context, intent)

		verify(exactly = 1) { scheduler.enqueue() }
	}

	@Test
	fun `boot recovery does not touch locks or Activity before full Ready`() = runTest {
		var lockCount = 0
		var rearmCount = 0

		runBootTrackingRecovery(
			startupGeneration = 3L,
			currentGeneration = { 3L },
			isReady = { false },
			isSuppressed = { false },
			reconcileStartup = {
				TrackingStartupResult.RetryableFailure(
					TrackingStartupStage.LEGACY_V27,
					"NOT_TERMINAL",
				)
			},
			initializeLocks = { lockCount++ },
			rearmAutomaticControl = {
				rearmCount++
				AutomaticControlRecoveryResult.ACCEPTED
			},
		) shouldBe BootTrackingRecoveryOutcome.DURABLE_RETRY

		lockCount shouldBe 0
		rearmCount shouldBe 0
	}

	@Test
	fun `permanently Blocked boot recovery completes without touching locks or Activity`() = runTest {
		var lockCount = 0
		var rearmCount = 0

		runBootTrackingRecovery(
			startupGeneration = 3L,
			currentGeneration = { 3L },
			isReady = { false },
			isSuppressed = { false },
			reconcileStartup = {
				TrackingStartupResult.Blocked(
					TrackingStartupStage.LEGACY_V27,
					"PERMANENT_FAILURE",
				)
			},
			initializeLocks = { lockCount++ },
			rearmAutomaticControl = {
				rearmCount++
				AutomaticControlRecoveryResult.ACCEPTED
			},
		) shouldBe BootTrackingRecoveryOutcome.COMPLETE

		lockCount shouldBe 0
		rearmCount shouldBe 0
	}

	@Test
	fun `Ready boot recovery retries an explicitly transient Activity rearm`() = runTest {
		var lockCount = 0
		var rearmCount = 0
		var drainCount = 0

		runBootTrackingRecovery(
			startupGeneration = 3L,
			currentGeneration = { 3L },
			isReady = { true },
			isSuppressed = { false },
			reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
			initializeLocks = { lockCount++ },
			rearmAutomaticControl = {
				rearmCount++
				AutomaticControlRecoveryResult.RETRYABLE
			},
			drainActivityAutomationEffects = {
				drainCount++
				ActivityAutomationDrainResult.Complete(0, 0)
			},
		) shouldBe BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY

		lockCount shouldBe 1
		rearmCount shouldBe 1
		drainCount shouldBe 1
	}

	@Test
	fun `deletion closing after gate precheck prevents old generation control mutation`() = runTest {
		var generation = 3L
		var ready = true
		var demandMutations = 0
		var providerMutations = 0
		var durableWrites = 0
		val gatePrecheckPassed = CompletableDeferred<Unit>()
		val allowFinalControlMutation = CompletableDeferred<Unit>()

		val recovery = async {
			runBootTrackingRecovery(
				startupGeneration = generation,
				currentGeneration = { generation },
				isReady = { ready },
				isSuppressed = { false },
				reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
				initializeLocks = {},
				rearmAutomaticControl = {
					demandMutations++
					providerMutations++
					durableWrites++
					AutomaticControlRecoveryResult.ACCEPTED
				},
				withReadyGenerationOperation = { expectedGeneration, operation ->
					if (!ready || generation != expectedGeneration) {
						null
					} else {
						gatePrecheckPassed.complete(Unit)
						allowFinalControlMutation.await()
						operation()
					}
				},
			)
		}
		gatePrecheckPassed.await()
		ready = false
		generation = 4L
		allowFinalControlMutation.complete(Unit)

		recovery.await() shouldBe BootTrackingRecoveryOutcome.COMPLETE
		demandMutations shouldBe 0
		providerMutations shouldBe 0
		durableWrites shouldBe 0
	}

	@Test
	fun `rearm exception terminates at the last optional attempt`() = runTest {
		val outcome = runBootTrackingRecovery(
			startupGeneration = 3L,
			currentGeneration = { 3L },
			isReady = { true },
			isSuppressed = { false },
			reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
			initializeLocks = {},
			rearmAutomaticControl = { error("transient optional-control failure") },
		)

		outcome shouldBe BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY
		outcome.shouldRetry(runAttemptCount = 2) shouldBe false
	}

	@Test
	fun `optional Activity projection exception exhausts a bounded wake budget`() = runTest {
		val outcome = runBootTrackingRecovery(
			startupGeneration = 3L,
			currentGeneration = { 3L },
			isReady = { true },
			isSuppressed = { false },
			reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
			initializeLocks = {},
			rearmAutomaticControl = { AutomaticControlRecoveryResult.ACCEPTED },
			drainActivityAutomationEffects = { error("transient projection failure") },
		)

		outcome shouldBe BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY
		outcome.shouldRetry(runAttemptCount = 2) shouldBe false
	}

	@Test
	fun `unfinished Activity projection dispositions use the optional wake budget`() = runTest {
		listOf<ActivityAutomationDrainResult>(
			ActivityAutomationDrainResult.ProjectionDeferred(),
			ActivityAutomationDrainResult.Retryable(0, 0),
			ActivityAutomationDrainResult.MorePending(1, 0),
		).forEach { pending ->
			runBootTrackingRecovery(
				startupGeneration = 3L,
				currentGeneration = { 3L },
				isReady = { true },
				isSuppressed = { false },
				reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
				initializeLocks = {},
				rearmAutomaticControl = { AutomaticControlRecoveryResult.ACCEPTED },
				drainActivityAutomationEffects = { pending },
			) shouldBe BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY
		}
	}

	@Test
	fun `optional control retry is bounded while durable recovery is not`() {
		BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY.shouldRetry(0) shouldBe true
		BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY.shouldRetry(1) shouldBe true
		BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY.shouldRetry(2) shouldBe false
		BootTrackingRecoveryOutcome.OPTIONAL_CONTROL_RETRY.shouldRetry(30) shouldBe false
		BootTrackingRecoveryOutcome.DURABLE_RETRY.shouldRetry(30) shouldBe true
	}

	@Test
	fun `duplicate enqueue keeps the unfinished worker retry budget`() {
		val context = mockk<Context>(relaxed = true)
		val workManager = mockk<WorkManager>(relaxed = true)
		mockkObject(WorkManager.Companion)
		every { WorkManager.getInstance(context) } returns workManager
		val scheduler = BootTrackingRecoveryScheduler(context)

		scheduler.enqueue()
		scheduler.enqueue()

		verify(exactly = 2) {
			workManager.enqueueUniqueWork(
				BootTrackingRecoveryScheduler.UNIQUE_WORK_NAME,
				ExistingWorkPolicy.KEEP,
				any<OneTimeWorkRequest>(),
			)
		}
	}

	@Test
	fun `Ready boot recovery completes for disabled or rollout-contained optional control`() = runTest {
		var lockCount = 0
		var rearmCount = 0

		runBootTrackingRecovery(
			startupGeneration = 3L,
			currentGeneration = { 3L },
			isReady = { true },
			isSuppressed = { false },
			reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
			initializeLocks = { lockCount++ },
			rearmAutomaticControl = {
				rearmCount++
				AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED
			},
		) shouldBe BootTrackingRecoveryOutcome.COMPLETE

		lockCount shouldBe 1
		rearmCount shouldBe 1
	}

	@Test
	fun `superseded recovery is a no-op`() = runTest {
		var reconcileCount = 0

		runBootTrackingRecovery(
			startupGeneration = 3L,
			currentGeneration = { 4L },
			isReady = { true },
			isSuppressed = { false },
			reconcileStartup = {
				reconcileCount++
				TrackingStartupResult.Ready(false, 0L)
			},
			initializeLocks = { error("stale work must not initialize locks") },
			rearmAutomaticControl = { error("stale work must not rearm Activity") },
		) shouldBe BootTrackingRecoveryOutcome.COMPLETE

		reconcileCount shouldBe 0
	}

	@Test
	fun `worker retains durable retry ownership after arbitrarily many transient attempts`() = runTest {
		val context = mockk<Context>(relaxed = true)
		val startupGate = mockk<com.adsamcik.tracker.shared.base.startup.TrackingStartupGate> {
			every { currentGeneration } throws IllegalStateException("transient storage failure")
		}
		val startupGuard = mockk<TrackingStartupGuard>(relaxed = true)
		val lockManager = mockk<LockManager>(relaxed = true)

		fun worker(attemptIndex: Int) = BootTrackingRecoveryWorker(
			appContext = context,
			params = mockk<WorkerParameters>(relaxed = true) {
				every { runAttemptCount } returns attemptIndex
			},
			trackingStartupGate = startupGate,
			trackingStartupGuard = startupGuard,
			lockManager = lockManager,
			sourcePipelineRecovery = mockk(relaxed = true),
		)

		worker(0).doWork() shouldBe ListenableWorker.Result.retry()
		worker(30).doWork() shouldBe ListenableWorker.Result.retry()
	}

	private fun configuredContext(scheduler: BootTrackingRecoveryScheduler): Context {
		val context = mockk<Context>()
		val entryPoint = mockk<BootReceiver.BootReceiverEntryPoint>()
		every { context.applicationContext } returns context
		every { entryPoint.bootTrackingRecoveryScheduler() } returns scheduler
		mockkStatic(EntryPointAccessors::class)
		every {
			EntryPointAccessors.fromApplication(
				context,
				BootReceiver.BootReceiverEntryPoint::class.java,
			)
		} returns entryPoint
		return context
	}
}
