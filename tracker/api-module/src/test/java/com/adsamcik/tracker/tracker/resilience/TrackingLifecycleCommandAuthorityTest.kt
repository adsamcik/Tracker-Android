package com.adsamcik.tracker.tracker.resilience

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackingLifecycleCommandAuthorityTest {
	@Test
	fun `stop after a queued start blocks that start before foreground session or providers`() {
		val (startReserved, start) = TrackingLifecycleCommandState().issueStart()
		val (stopReserved, stop) = startReserved.issueStop(
			TrackingStopCandidateReason.EXPLICIT_REQUEST,
			requestedAtEpochMs = 1_000L,
		)

		stopReserved.resolveStart(start) shouldBe
			TrackingStartCommandDisposition.BlockedByStop(stop)
		stopReserved.isActionable(stop) shouldBe true
	}

	@Test
	fun `late queued start is stale after absent finalization consumed its stop fence`() {
		val (startReserved, start) = TrackingLifecycleCommandState().issueStart()
		val (stopReserved, stop) = startReserved.issueStop(
			TrackingStopCandidateReason.EXPLICIT_REQUEST,
			requestedAtEpochMs = 1_000L,
		)
		val handled = stopReserved.markHandled(stop)

		handled.resolveStart(start) shouldBe TrackingStartCommandDisposition.Stale
		handled.isActionable(stop) shouldBe false
		handled.latestUnhandledStop() shouldBe null
	}

	@Test
	fun `latest unhandled stop reconstructs every exact persisted command field`() {
		val (reserved, stop) = TrackingLifecycleCommandState().issueStop(
			TrackingStopCandidateReason.AUTOMATIC_ACTIVITY_INCOMPATIBLE,
			requestedAtEpochMs = 4_321L,
			requestedBootId = "boot-7",
			requestedElapsedRealtimeNanos = 987_654L,
		)

		reserved.latestUnhandledStop() shouldBe stop
	}

	@Test
	fun `a newer reservation cannot supersede a stop until cleanup handles it and start is accepted`() {
		val (stopReserved, stop) = TrackingLifecycleCommandState().issueStop(
			TrackingStopCandidateReason.EXPLICIT_REQUEST,
			requestedAtEpochMs = 1_000L,
		)
		val (newerStartReserved, newerStart) = stopReserved.issueStart()

		newerStartReserved.resolveStart(newerStart) shouldBe
			TrackingStartCommandDisposition.BlockedByStop(stop)
		newerStartReserved.isCurrent(stop) shouldBe true
		newerStartReserved.latestUnhandledStop() shouldBe stop

		val handled = newerStartReserved.markHandled(stop)
		handled.resolveStart(newerStart) shouldBe TrackingStartCommandDisposition.Allowed
		val accepted = handled.acceptStart(newerStart)
		accepted.isCurrent(stop) shouldBe false
		accepted.latestUnhandledStop() shouldBe null
		newerStart.generation shouldBe stop.generation + 1L
	}

	@Test
	fun `latest reservation wins while accepted generation remains redelivery eligible`() {
		val (firstReserved, first) = TrackingLifecycleCommandState().issueStart()
		val (secondReserved, second) = firstReserved.issueStart()

		secondReserved.resolveStart(first) shouldBe TrackingStartCommandDisposition.Stale
		secondReserved.resolveStart(second) shouldBe TrackingStartCommandDisposition.Allowed
		val secondAccepted = secondReserved.acceptStart(second)
		val (thirdReserved, third) = secondAccepted.issueStart()
		thirdReserved.resolveStart(first) shouldBe TrackingStartCommandDisposition.Stale
		thirdReserved.resolveStart(second) shouldBe TrackingStartCommandDisposition.Allowed
		thirdReserved.resolveStart(third) shouldBe TrackingStartCommandDisposition.Allowed
	}

	@Test
	fun `stop blocks latest reservation while older unaccepted reservation is already stale`() {
		val (firstReserved, first) = TrackingLifecycleCommandState().issueStart()
		val (secondReserved, second) = firstReserved.issueStart()
		val (stopReserved, stop) = secondReserved.issueStop(
			TrackingStopCandidateReason.EXPLICIT_REQUEST,
			requestedAtEpochMs = 2_000L,
		)

		stopReserved.resolveStart(first) shouldBe TrackingStartCommandDisposition.Stale
		stopReserved.resolveStart(second) shouldBe TrackingStartCommandDisposition.BlockedByStop(stop)
	}

	@Test
	fun `fresh reservation waits for stop handling then can become authoritative`() {
		val (firstReserved, first) = TrackingLifecycleCommandState().issueStart()
		val (stopReserved, stop) = firstReserved.issueStop(
			TrackingStopCandidateReason.EXPLICIT_REQUEST,
			requestedAtEpochMs = 2_000L,
		)
		val (freshReserved, fresh) = stopReserved.issueStart()

		freshReserved.resolveStart(first) shouldBe TrackingStartCommandDisposition.Stale
		freshReserved.resolveStart(fresh) shouldBe TrackingStartCommandDisposition.BlockedByStop(stop)
		freshReserved.isCurrent(stop) shouldBe true

		val handled = freshReserved.markHandled(stop)
		handled.resolveStart(first) shouldBe TrackingStartCommandDisposition.Stale
		handled.resolveStart(fresh) shouldBe TrackingStartCommandDisposition.Allowed
		handled.acceptStart(fresh).isCurrent(stop) shouldBe false
	}
}
