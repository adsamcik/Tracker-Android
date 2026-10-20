package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStopOutcome
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStopRetryCode
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStopCutoff
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class TrackerServiceStopCommandOrderingTest {
	@Test
	fun `internal teardown adopts pending End with its original cutoff before delivery`() {
		val end = stopCommand(2L, 2_000L, "boot-1", 20_000L)
		val authority = mockk<TrackingLifecycleCommandAuthority> {
			every { latestUnhandledStop() } returns end
			every { isStopActionable(end) } returns true
		}

		val adopted = requireNotNull(authority.pendingStopForTeardown(hasAppliedSources = true))
		adopted shouldBe end
		adopted.reason shouldBe TrackingStopCandidateReason.EXPLICIT_REQUEST
		adopted.toLiveSourceSessionStopCutoff("boot-1", 90_000L) shouldBe
			SourceSessionStopCutoff(2_000L, 20_000L, "boot-1")
	}

	@Test
	fun `providerless shell leaves pending End with the inactive session owner`() {
		val authority = mockk<TrackingLifecycleCommandAuthority>()

		authority.pendingStopForTeardown(hasAppliedSources = false) shouldBe null

		verify(exactly = 0) { authority.latestUnhandledStop() }
	}

	@Test
	fun `latest repeated stop takes ownership and an older delivery cannot reclaim it`() {
		val first = stopCommand(generation = 2L)
		val repeated = stopCommand(generation = 3L)

		val latest = selectLatestExternalStopCommand(first, repeated)

		latest shouldBe repeated
		selectLatestExternalStopCommand(latest, first) shouldBe repeated
	}

	@Test
	fun `latest repeated stop owns acknowledgement while first cutoff remains factual`() {
		val first = stopCommand(
			generation = 2L,
			requestedAtEpochMs = 2_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = 20_000L,
		)
		val repeated = stopCommand(
			generation = 3L,
			requestedAtEpochMs = 3_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = 30_000L,
		)
		val firstCutoff = first.toLiveSourceSessionStopCutoff("boot-1", 25_000L)

		selectLatestExternalStopCommand(first, repeated) shouldBe repeated
		selectEarliestExternalStopCutoff(
			firstCutoff,
			repeated.toLiveSourceSessionStopCutoff("boot-1", 35_000L),
		) shouldBe firstCutoff
	}

	@Test
	fun `same boot preserves requested elapsed cutoff and cross boot uses receipt elapsed`() {
		val command = stopCommand(
			generation = 2L,
			requestedAtEpochMs = 2_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = 20_000L,
		)

		command.toLiveSourceSessionStopCutoff("boot-1", 90_000L) shouldBe
			SourceSessionStopCutoff(2_000L, 20_000L, "boot-1")
		command.toLiveSourceSessionStopCutoff("boot-2", 90_000L) shouldBe
			SourceSessionStopCutoff(2_000L, 90_000L, "boot-2")
	}

	@Test
	fun `captured Activity is re-kicked only after durable terminal settlement`() {
		var drainRequests = 0
		val request: () -> Unit = { drainRequests++ }

		SourceSessionStopOutcome.NotActive
			.requestCapturedActivityProjectionOnSettlement(request)
		SourceSessionStopOutcome.Retryable(SourceSessionStopRetryCode.DRAIN_PENDING)
			.requestCapturedActivityProjectionOnSettlement(request)
		drainRequests shouldBe 0

		SourceSessionStopOutcome.Stopped.requestCapturedActivityProjectionOnSettlement(request)
		drainRequests shouldBe 1
	}

	private fun stopCommand(
		generation: Long,
		requestedAtEpochMs: Long = generation * 1_000L,
		requestedBootId: String? = null,
		requestedElapsedRealtimeNanos: Long? = null,
	) = TrackingStopCommand(
		generation = generation,
		reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
		requestedAtEpochMs = requestedAtEpochMs,
		requestedBootId = requestedBootId,
		requestedElapsedRealtimeNanos = requestedElapsedRealtimeNanos,
	)
}
