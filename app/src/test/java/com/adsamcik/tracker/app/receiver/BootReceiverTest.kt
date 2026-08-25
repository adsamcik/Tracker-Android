package com.adsamcik.tracker.app.receiver

import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryResult
import dagger.hilt.android.EntryPointAccessors
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
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
		) shouldBe BootTrackingRecoveryOutcome.RETRY

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
		) shouldBe BootTrackingRecoveryOutcome.RETRY

		lockCount shouldBe 1
		rearmCount shouldBe 1
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
