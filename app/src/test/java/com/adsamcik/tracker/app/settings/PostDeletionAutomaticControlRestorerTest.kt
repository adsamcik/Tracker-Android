package com.adsamcik.tracker.app.settings

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.app.startup.TrackingStartupDeletionBarrier
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import javax.inject.Provider

class PostDeletionAutomaticControlRestorerTest {
	@Test
	fun `Room writers and arbiter remain paused until full Ready`() = runTest {
		val operations = mutableListOf<String>()

		runPostDeletionRecovery(
			expectedEpoch = 8L,
			currentEpoch = { 8L },
			startupGeneration = 2L,
			currentStartupGeneration = { 2L },
			isDeletionClosed = { false },
			isStartupReady = { false },
			reconcileStartup = {
				TrackingStartupResult.RetryableFailure(
					TrackingStartupStage.LEGACY_V27,
					"NOT_TERMINAL",
				)
			},
			resumeWriters = { operations += "writers" },
			resumeActivityArbiter = { operations += "arbiter" },
			reconcileAutomaticControl = {
				operations += "control"
				AutomaticControlRecoveryResult.ACCEPTED
			},
		) shouldBe PostDeletionRecoveryOutcome.RETRY

		operations shouldBe emptyList()
	}

	@Test
	fun `permanently Blocked startup completes without reopening writers`() = runTest {
		val operations = mutableListOf<String>()

		runPostDeletionRecovery(
			expectedEpoch = 8L,
			currentEpoch = { 8L },
			startupGeneration = 2L,
			currentStartupGeneration = { 2L },
			isDeletionClosed = { false },
			isStartupReady = { false },
			reconcileStartup = {
				TrackingStartupResult.Blocked(
					TrackingStartupStage.LEGACY_V27,
					"PERMANENT_FAILURE",
				)
			},
			resumeWriters = { operations += "writers" },
			resumeActivityArbiter = { operations += "arbiter" },
			reconcileAutomaticControl = {
				operations += "control"
				AutomaticControlRecoveryResult.ACCEPTED
			},
		) shouldBe PostDeletionRecoveryOutcome.COMPLETE

		operations shouldBe emptyList()
	}

	@Test
	fun `superseded collected data epoch is a terminal no-op`() = runTest {
		var startupCount = 0

		runPostDeletionRecovery(
			expectedEpoch = 8L,
			currentEpoch = { 9L },
			startupGeneration = 2L,
			currentStartupGeneration = { 2L },
			isDeletionClosed = { false },
			isStartupReady = { true },
			reconcileStartup = {
				startupCount++
				TrackingStartupResult.Ready(false, 0L)
			},
			resumeWriters = { error("stale work must not resume writers") },
			resumeActivityArbiter = { error("stale work must not resume Activity") },
			reconcileAutomaticControl = { error("stale work must not restore control") },
		) shouldBe PostDeletionRecoveryOutcome.COMPLETE

		startupCount shouldBe 0
	}

	@Test
	fun `Ready generation resumes writers and arbiter before control restoration`() = runTest {
		val operations = mutableListOf<String>()

		runPostDeletionRecovery(
			expectedEpoch = 8L,
			currentEpoch = { 8L },
			startupGeneration = 2L,
			currentStartupGeneration = { 2L },
			isDeletionClosed = { false },
			isStartupReady = { true },
			reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
			resumeWriters = { operations += "writers" },
			resumeActivityArbiter = { operations += "arbiter" },
			reconcileAutomaticControl = {
				operations += "control"
				AutomaticControlRecoveryResult.ACCEPTED
			},
		) shouldBe PostDeletionRecoveryOutcome.COMPLETE

		operations shouldBe listOf("writers", "arbiter", "control")
	}

	@Test
	fun `generation change after reconciliation leaves every writer paused`() = runTest {
		val operations = mutableListOf<String>()
		var currentGeneration = 2L

		runPostDeletionRecovery(
			expectedEpoch = 8L,
			currentEpoch = { 8L },
			startupGeneration = 2L,
			currentStartupGeneration = { currentGeneration },
			isDeletionClosed = { false },
			isStartupReady = { true },
			reconcileStartup = {
				currentGeneration = 3L
				TrackingStartupResult.Ready(false, 0L)
			},
			resumeWriters = { operations += "writers" },
			resumeActivityArbiter = { operations += "arbiter" },
			reconcileAutomaticControl = {
				operations += "control"
				AutomaticControlRecoveryResult.ACCEPTED
			},
		) shouldBe PostDeletionRecoveryOutcome.RETRY

		operations shouldBe emptyList()
	}

	@Test
	fun `epoch rotation before protected resume returns retry without reopening writers`() = runTest {
		val operations = mutableListOf<String>()
		var currentEpoch = 8L
		val protectedOperation = LatchingReadyGenerationOperation()
		val recovery = async {
			runPostDeletionRecovery(
				expectedEpoch = 8L,
				currentEpoch = { currentEpoch },
				startupGeneration = 2L,
				currentStartupGeneration = { 2L },
				isDeletionClosed = { false },
				isStartupReady = { true },
				reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
				withReadyGenerationOperation = protectedOperation::run,
				resumeWriters = { operations += "writers" },
				resumeActivityArbiter = { operations += "arbiter" },
				reconcileAutomaticControl = {
					operations += "control"
					AutomaticControlRecoveryResult.ACCEPTED
				},
			)
		}

		protectedOperation.requested.await()
		currentEpoch = 9L
		protectedOperation.allowEntry.complete(Unit)

		recovery.await() shouldBe PostDeletionRecoveryOutcome.RETRY
		operations shouldBe emptyList()
	}

	@Test
	fun `transient automatic demand failure remains durable retry work`() = runTest {
		runPostDeletionRecovery(
			expectedEpoch = 8L,
			currentEpoch = { 8L },
			startupGeneration = 2L,
			currentStartupGeneration = { 2L },
			isDeletionClosed = { false },
			isStartupReady = { true },
			reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
			resumeWriters = {},
			resumeActivityArbiter = {},
			reconcileAutomaticControl = { AutomaticControlRecoveryResult.RETRYABLE },
		) shouldBe PostDeletionRecoveryOutcome.RETRY
	}

	@Test
	fun `disabled or rollout-contained optional control does not retry forever`() = runTest {
		runPostDeletionRecovery(
			expectedEpoch = 8L,
			currentEpoch = { 8L },
			startupGeneration = 2L,
			currentStartupGeneration = { 2L },
			isDeletionClosed = { false },
			isStartupReady = { true },
			reconcileStartup = { TrackingStartupResult.Ready(false, 0L) },
			resumeWriters = {},
			resumeActivityArbiter = {},
			reconcileAutomaticControl = {
				AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED
			},
		) shouldBe PostDeletionRecoveryOutcome.COMPLETE
	}

	@Test
	fun `worker retains its unique epoch fenced retry owner after repeated transient failure`() = runTest {
		val context = mockk<Context>(relaxed = true)
		val lifecycleStore = mockk<CollectedDataLifecycleStore>()
		coEvery { lifecycleStore.snapshot() } throws
			IllegalStateException("transient lifecycle storage failure")

		fun worker(attemptIndex: Int) = PostDeletionRecoveryWorker(
			appContext = context,
			params = mockk<WorkerParameters>(relaxed = true) {
				every { inputData } returns workDataOf(
					PostDeletionRecoveryWorker.COLLECTED_DATA_EPOCH_KEY to 8L,
				)
				every { runAttemptCount } returns attemptIndex
			},
			startupGate = mockk<TrackingStartupGate>(relaxed = true),
			deletionBarrier = mockk<TrackingStartupDeletionBarrier>(relaxed = true),
			lifecycleStore = lifecycleStore,
			writerQuiescer = mockk<CollectedDataWriterQuiescer>(relaxed = true),
			activityRegistrationArbiterProvider = Provider {
				mockk<ActivityRegistrationArbiter>(relaxed = true)
			},
		)

		worker(2).doWork() shouldBe
			ListenableWorker.Result.retry()
		worker(30).doWork() shouldBe
			ListenableWorker.Result.retry()
	}
}

private class LatchingReadyGenerationOperation {
	val requested = CompletableDeferred<Unit>()
	val allowEntry = CompletableDeferred<Unit>()

	suspend fun run(
		@Suppress("UNUSED_PARAMETER") expectedGeneration: Long,
		operation: suspend () -> AutomaticControlRecoveryResult?,
	): AutomaticControlRecoveryResult? {
		requested.complete(Unit)
		allowEntry.await()
		return operation()
	}
}
