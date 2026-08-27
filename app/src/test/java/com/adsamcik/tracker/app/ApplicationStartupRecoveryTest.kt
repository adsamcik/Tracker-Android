package com.adsamcik.tracker.app

import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ApplicationStartupRecoveryTest {
	@Test
	fun `main process exit is selected even when a newer handler exit is first`() {
		val mainProcess = "com.example.tracker"
		val records = listOf(
			HistoricalProcessExit("$mainProcess:tracebox_handler", reason = 1, timestampMs = 3_000L),
			HistoricalProcessExit(mainProcess, reason = 2, timestampMs = 2_000L),
			HistoricalProcessExit(mainProcess, reason = 3, timestampMs = 1_000L),
		)

		mostRecentMainProcessExit(records, mainProcess) shouldBe records[1]
	}

	@Test
	fun `recent repeated low-memory exits count only the main process and bounded window`() {
		val mainProcess = "com.example.tracker"
		val nowMs = 100_000L
		val lowMemoryReason = 3
		val records = listOf(
			HistoricalProcessExit(mainProcess, lowMemoryReason, 99_000L),
			HistoricalProcessExit(mainProcess, lowMemoryReason, 90_000L),
			HistoricalProcessExit("$mainProcess:tracebox_handler", lowMemoryReason, 99_500L),
			HistoricalProcessExit(mainProcess, reason = 6, timestampMs = 99_700L),
			HistoricalProcessExit(mainProcess, lowMemoryReason, 70_000L),
			HistoricalProcessExit(mainProcess, lowMemoryReason, 101_000L),
		)

		recentMainProcessExitCount(
			records = records,
			mainProcessName = mainProcess,
			reason = lowMemoryReason,
			nowMs = nowMs,
			windowMs = 20_000L,
		) shouldBe 2
	}

	@Test
	fun `positive force-stop evidence is exclusive and uses the main exit timestamp`() {
		val exit = HistoricalProcessExit(
			processName = "com.example.tracker",
			reason = AMBIGUOUS_USER_REQUESTED_REASON,
			timestampMs = 2_000L,
		)

		applicationStartupRecoveryAction(
			confirmedForceStop = true,
			mainProcessExit = exit,
			fallbackTimestampMs = 9_000L,
		) shouldBe ApplicationStartupRecoveryAction.ConfirmedForceStop(completedAtMs = 2_000L)
	}

	@Test
	fun `API 30 to 34 user requested exit stays ambiguous without positive force-stop evidence`() {
		applicationStartupRecoveryAction(
			confirmedForceStop = false,
			mainProcessExit = HistoricalProcessExit(
				processName = "com.example.tracker",
				reason = AMBIGUOUS_USER_REQUESTED_REASON,
				timestampMs = 2_000L,
			),
			fallbackTimestampMs = 9_000L,
		) shouldBe ApplicationStartupRecoveryAction.PreviousExit(
			reason = AMBIGUOUS_USER_REQUESTED_REASON,
			completedAtMs = 2_000L,
		)
	}

	@Test
	fun `API 26 to 29 missing exit evidence remains unknown ordinary startup`() {
		applicationStartupRecoveryAction(
			confirmedForceStop = false,
			mainProcessExit = null,
			fallbackTimestampMs = 9_000L,
		) shouldBe ApplicationStartupRecoveryAction.None
	}

	@Test
	fun `application retry owner keeps awaiters live beyond four failures without external stimulus`() = runTest {
		val delays = mutableListOf<Long>()
		val visibleFailures = mutableListOf<TrackingStartupResult.RetryableFailure>()
		var attempts = 0
		val ready = TrackingStartupResult.Ready(false, 6L)

		val result = driveTrackingStartup(
			reconcile = {
				attempts++
				if (attempts <= 5) TrackingStartupResult.RetryableFailure(
					TrackingStartupStage.LIVE_V2,
					"LEASE",
				) else ready
			},
			waitBeforeRetry = { delays += it },
			onRetryableVisible = { visibleFailures += it },
		)

		result shouldBe ready
		attempts shouldBe 6
		delays shouldBe listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)
		visibleFailures shouldBe listOf(
			TrackingStartupResult.RetryableFailure(TrackingStartupStage.LIVE_V2, "LEASE"),
		)
	}

	@Test
	fun `application retry owner does not poll a permanent block`() = runTest {
		var attempts = 0
		val blocked = TrackingStartupResult.Blocked(
			TrackingStartupStage.LEGACY_V27,
			"UNKNOWN_WRITER",
		)

		driveTrackingStartup(
			reconcile = { attempts++; blocked },
			waitBeforeRetry = { error("Blocked startup must not schedule a retry") },
		) shouldBe blocked
		attempts shouldBe 1
	}

	private companion object {
		const val AMBIGUOUS_USER_REQUESTED_REASON = 10
	}
}
