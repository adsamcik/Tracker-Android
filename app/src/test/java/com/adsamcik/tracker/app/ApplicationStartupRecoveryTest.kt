package com.adsamcik.tracker.app

import io.kotest.matchers.shouldBe
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
	fun `exit reason is only reconciled without positive force-stop evidence`() {
		applicationStartupRecoveryAction(
			confirmedForceStop = false,
			mainProcessExit = HistoricalProcessExit(
				processName = "com.example.tracker",
				reason = AMBIGUOUS_USER_REQUESTED_REASON,
				timestampMs = 2_000L,
			),
			fallbackTimestampMs = 9_000L,
		) shouldBe ApplicationStartupRecoveryAction.PreviousExit(AMBIGUOUS_USER_REQUESTED_REASON)
	}

	private companion object {
		const val AMBIGUOUS_USER_REQUESTED_REASON = 10
	}
}
