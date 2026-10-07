package com.adsamcik.tracker.tracker.resilience

import io.kotest.matchers.shouldBe
import com.adsamcik.tracker.shared.base.startup.TrackingCollectionResumeBoundary
import org.junit.jupiter.api.Test

class TrackingLifecycleCommandAuthorityTest {
    @Test
    fun `Start issued before user exit cannot acknowledge that exit`() {
        val (beforeExit, oldStart) = TrackingLifecycleCommandState().issueStart()
        val stopped = beforeExit.observeUserExit("exit-1")
        val boundary = TrackingCollectionResumeBoundary(1_000L, "boot", 100L)
        stopped.prepareCollectionResumeForStart("exit-1", oldStart) shouldBe stopped
        val (fresh, newStart) = stopped.issueStart()
        val pending = fresh.prepareCollectionResumeForStart("exit-1", newStart)
        pending.collectionResumeRequired shouldBe true
        pending.handledUserExitId shouldBe "exit-1"
        pending.collectionResumeBoundary shouldBe null
        pending.isPendingCollectionStart(newStart) shouldBe true
        val accepted = pending.acceptStart(newStart, boundary)
        accepted.collectionResumeRequired shouldBe false
        accepted.collectionResumeBoundary shouldBe boundary
        accepted.pendingCollectionStartGeneration shouldBe null
    }

    @Test
    fun `superseded or stop blocked Start cannot acknowledge user exit`() {
        val stopped = TrackingLifecycleCommandState().observeUserExit("exit-1")
        val (first, firstStart) = stopped.issueStart()
        val (second, _) = first.issueStart()
        second.prepareCollectionResumeForStart("exit-1", firstStart) shouldBe second
        val (withStop, _) = first.issueStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1_000L)
        val (blocked, blockedStart) = withStop.issueStart()
        blocked.prepareCollectionResumeForStart("exit-1", blockedStart) shouldBe blocked
    }

	@Test
	fun `explicit resume keeps bounded exit receipt and stale acknowledgement cannot clear newer stop`() {
		val first = TrackingLifecycleCommandState().observeUserExit("exit-1")
		val boundary = TrackingCollectionResumeBoundary(1_000L, "boot", 100L)
		val resumed = first.resumeCollection("exit-1", boundary)
		resumed.collectionResumeRequired shouldBe false
		resumed.collectionResumeBoundary shouldBe boundary
		resumed.observeUserExit("exit-1") shouldBe resumed
		resumed.resumeCollection("exit-1", TrackingCollectionResumeBoundary(2_000L, "boot", 200L)) shouldBe resumed
		val next = resumed.observeUserExit("exit-2")
		next.resumeCollection("exit-1", boundary) shouldBe next
		next.collectionResumeRequired shouldBe true
		boundary.permitsObservation("boot", 99L, 9_000L) shouldBe false
		boundary.permitsObservation("boot", 100L, 1L) shouldBe true
		boundary.permitsWindow(999L) shouldBe false
		boundary.permitsWindow(1_000L) shouldBe true
	}
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
