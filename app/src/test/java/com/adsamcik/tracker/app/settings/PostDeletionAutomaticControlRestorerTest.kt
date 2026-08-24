package com.adsamcik.tracker.app.settings

import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

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
			reconcileAutomaticControl = { operations += "control"; true },
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
			reconcileAutomaticControl = { operations += "control"; true },
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
			reconcileAutomaticControl = { operations += "control"; true },
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
			reconcileAutomaticControl = { operations += "control"; true },
		) shouldBe PostDeletionRecoveryOutcome.RETRY

		operations shouldBe emptyList()
	}

	@Test
	fun `unaccepted automatic demand remains durable retry work`() = runTest {
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
			reconcileAutomaticControl = { false },
		) shouldBe PostDeletionRecoveryOutcome.RETRY
	}
}
