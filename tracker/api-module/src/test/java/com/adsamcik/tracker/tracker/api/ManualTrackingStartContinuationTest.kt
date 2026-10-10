package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandState
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

internal fun testManualStartPreflight(
	readiness: ManualTrackingStartReadiness,
	selection: ManualTrackingStartSelection = ManualTrackingStartSelection(mapOf(TrackingCaptureSource.CELL to 1L)),
) = ManualTrackingStartPreflight(readiness, selection)

/** The real command-state model exercises API orchestration; persistent gate coverage is in engine. */
internal class ManualStartTestAuthority : TrackingLifecycleCommandAuthority by mockk(relaxed = true) {
	var state = TrackingLifecycleCommandState()
	var reservations = 0
	override val pendingManualStart get() = state.pendingManualStart
	override fun latestCommandGeneration() = state.lastGeneration
	override suspend fun reserveManualStartIfGeneration(
		expectedGeneration: Long, selection: ManualTrackingStartSelection,
	): ManualTrackingStartContinuation? {
		if (state.lastGeneration != expectedGeneration) return null
		reservations++
		state = state.issueManualStart(selection)
		return state.pendingManualStart
	}
	override suspend fun consumeManualStart(generation: Long, selection: ManualTrackingStartSelection): TrackingStartCommand? {
		if (!state.isPendingManualStart(generation, selection)) return null
		val command = requireNotNull(state.pendingManualStart).command
		state = state.copy(pendingManualStart = null)
		return command
	}
	override suspend fun cancelManualStart(generation: Long): Boolean {
		if (state.pendingManualStart?.command?.generation != generation) return false
		state = state.copy(pendingManualStart = null)
		return true
	}
}

internal suspend fun testManualStartEnqueue(
	authority: TrackingLifecycleCommandAuthority,
	continuation: ManualTrackingStartContinuation,
): TrackingStartDispatchResult = if (authority.consumeManualStart(continuation.command.generation,
	continuation.selection) == continuation.command) TrackingStartDispatchResult.Enqueued
	else TrackingStartDispatchResult.Terminal("MANUAL_START_CONTINUATION_SUPERSEDED")

class ManualTrackingStartContinuationTest {
	private val repair = ManualTrackingStartReadiness.RepairRequired(ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION)
	private val ready = ManualTrackingStartReadiness.Ready(1L)

	@Test
	fun `two permissions continue the original Start and dispatch it once`() = runTest {
		val authority = ManualStartTestAuthority()
		var enqueued: ManualTrackingStartContinuation? = null
		suspend fun request(generation: Long?, readiness: ManualTrackingStartReadiness) =
			executeManualTrackingStartContinuation(generation, authority, { testManualStartPreflight(readiness) }) {
				enqueued = it; testManualStartEnqueue(authority, it)
			}
		request(null, repair) shouldBe ManualTrackingStartResult.RepairRequired(repair.prerequisite, 1L)
		request(1L, ManualTrackingStartReadiness.RepairRequired(ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION))
			.shouldBe(ManualTrackingStartResult.RepairRequired(ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION, 1L))
		request(1L, ready) shouldBe ManualTrackingStartResult.ENQUEUED
		enqueued?.command?.generation shouldBe 1L
		authority.reservations shouldBe 1
		request(1L, ready) shouldBe ManualTrackingStartResult.TRACKING_UNAVAILABLE
	}

	@Test
	fun `source off and on cannot reuse the old Start consent`() = runTest {
		val authority = ManualStartTestAuthority()
		executeManualTrackingStartContinuation(null, authority, { testManualStartPreflight(repair) }) { error("repair") }
		val changed = ManualTrackingStartSelection(mapOf(TrackingCaptureSource.CELL to 2L))
		executeManualTrackingStartContinuation(1L, authority, { testManualStartPreflight(ready, changed) }) {
			error("Changed choices must never enqueue")
		} shouldBe ManualTrackingStartResult.TRACKING_UNAVAILABLE
		authority.pendingManualStart shouldBe null
	}

	@Test
	fun `End during original preflight prevents a reservation after the End`() = runTest {
		val authority = ManualStartTestAuthority()
		val reading = CompletableDeferred<Unit>()
		val complete = CompletableDeferred<Unit>()
		val request = async {
			executeManualTrackingStartContinuation(null, authority, {
				reading.complete(Unit); complete.await(); testManualStartPreflight(ready)
			}) { error("End fence must win") }
		}
		reading.await()
		authority.state = authority.state.issueStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1L).first
		complete.complete(Unit)
		request.await() shouldBe ManualTrackingStartResult.TRACKING_UNAVAILABLE
		authority.reservations shouldBe 0
	}

	@Test
	fun `End cancels a suspended permission continuation before its queued Stop is reserved`() = runTest {
		val authority = ManualStartTestAuthority()
		executeManualTrackingStartContinuation(null, authority, { testManualStartPreflight(repair) }) {
			error("Repair must not enqueue")
		}
		val reading = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		var enqueued = false
		val request = async {
			executeManualTrackingStartContinuation(1L, authority, {
				reading.complete(Unit)
				release.await()
				testManualStartPreflight(ready)
			}) { enqueued = true; testManualStartEnqueue(authority, it) }
		}
		reading.await()
		// A screen invalidates/cancels its Start immediately, before asynchronous Stop dispatch.
		request.cancelAndJoin()
		authority.cancelManualStart(1L)
		release.complete(Unit)
		authority.state = authority.state.issueStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2L).first
		enqueued shouldBe false
		authority.pendingManualStart shouldBe null
		executeManualTrackingStartContinuation(1L, authority, { testManualStartPreflight(ready) }) {
			error("The old callback must remain fenced")
		} shouldBe ManualTrackingStartResult.TRACKING_UNAVAILABLE
	}

	@Test
	fun `End Off user exit and another Start cannot revive a repaired Start`() = runTest {
		val fences: List<(TrackingLifecycleCommandState) -> TrackingLifecycleCommandState> = listOf(
			{ it.issueStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1L).first },
			{ it.observeUserExit(TRACKING_TURN_OFF_EXIT_PREFIX + "off") },
			{ it.observeUserExit("user-exit") },
			{ it.issueStart().first },
		)
		for (fence in fences) {
			val authority = ManualStartTestAuthority()
			executeManualTrackingStartContinuation(null, authority, { testManualStartPreflight(repair) }) { error("repair") }
			authority.state = fence(authority.state)
			executeManualTrackingStartContinuation(1L, authority, { testManualStartPreflight(ready) }) { error("fenced") }
				.shouldBe(ManualTrackingStartResult.TRACKING_UNAVAILABLE)
		}
	}

	@Test
	fun `concurrent duplicate callbacks consume once even while enqueue waits`() = runTest {
		val authority = ManualStartTestAuthority()
		executeManualTrackingStartContinuation(null, authority, { testManualStartPreflight(repair) }) { error("repair") }
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		val first = async {
			executeManualTrackingStartContinuation(1L, authority, { testManualStartPreflight(ready) }) {
				val delivered = testManualStartEnqueue(authority, it)
				entered.complete(Unit); release.await(); delivered
			}
		}
		entered.await()
		executeManualTrackingStartContinuation(1L, authority, { testManualStartPreflight(ready) }) { error("duplicate") }
			.shouldBe(ManualTrackingStartResult.TRACKING_UNAVAILABLE)
		release.complete(Unit)
		first.await() shouldBe ManualTrackingStartResult.ENQUEUED
	}

	@Test
	fun `each solo source and every nonempty subset can continue without extra source demand`() = runTest {
		val sources = TrackingCaptureSource.entries
		for (mask in 1 until (1 shl sources.size)) {
			val selection = ManualTrackingStartSelection(sources.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
				.associateWith { 7L })
			ManualTrackingStartSelection.decode(selection.encode()) shouldBe selection
			val authority = ManualStartTestAuthority()
			executeManualTrackingStartContinuation(null, authority, { testManualStartPreflight(repair, selection) }) { error("repair") }
			executeManualTrackingStartContinuation(1L, authority, { testManualStartPreflight(ready, selection) }) {
				it.selection shouldBe selection; testManualStartEnqueue(authority, it)
			} shouldBe ManualTrackingStartResult.ENQUEUED
		}
	}

	@Test
	fun `setup completion keeps the original Start and unchanged source consent`() = runTest {
		val authority = ManualStartTestAuthority()
		executeManualTrackingStartContinuation(null, authority, { testManualStartPreflight(ManualTrackingStartReadiness.SetupPending) }) {
			error("Pending setup must not prepare Android delivery")
		} shouldBe ManualTrackingStartResult.SetupPending(1L)
		executeManualTrackingStartContinuation(1L, authority, { testManualStartPreflight(ready) }) {
			it.command.generation shouldBe 1L
			testManualStartEnqueue(authority, it)
		} shouldBe ManualTrackingStartResult.ENQUEUED
		authority.reservations shouldBe 1
		authority.pendingManualStart shouldBe null
	}

	@Test
	fun `late preparation quality lag keeps original Start for the next real completion event`() = runTest {
		val authority = ManualStartTestAuthority()
		executeManualTrackingStartContinuation(null, authority, { testManualStartPreflight(ready) }) {
			TrackingStartDispatchResult.SetupPending
		} shouldBe ManualTrackingStartResult.SetupPending(1L)
		authority.pendingManualStart?.command?.generation shouldBe 1L
		executeManualTrackingStartContinuation(1L, authority, { testManualStartPreflight(ready) }) {
			testManualStartEnqueue(authority, it)
		} shouldBe ManualTrackingStartResult.ENQUEUED
		authority.reservations shouldBe 1
	}

	@Test
	fun `pending setup cannot survive source consent End Off or user exit changes`() = runTest {
		val fences: List<(TrackingLifecycleCommandState) -> TrackingLifecycleCommandState> = listOf(
			{ it.issueStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1L).first },
			{ it.observeUserExit(TRACKING_TURN_OFF_EXIT_PREFIX + "pending-off") },
			{ it.observeUserExit("pending-user-exit") },
		)
		for (fence in fences) {
			val authority = ManualStartTestAuthority()
			executeManualTrackingStartContinuation(null, authority, { testManualStartPreflight(ManualTrackingStartReadiness.SetupPending) }) {
				error("Pending")
			} shouldBe ManualTrackingStartResult.SetupPending(1L)
			authority.state = fence(authority.state)
			executeManualTrackingStartContinuation(1L, authority, { testManualStartPreflight(ready) }) { error("Fenced") }
				.shouldBe(ManualTrackingStartResult.TRACKING_UNAVAILABLE)
		}
		val changed = ManualStartTestAuthority()
		executeManualTrackingStartContinuation(null, changed, { testManualStartPreflight(ManualTrackingStartReadiness.SetupPending) }) { error("Pending") }
		executeManualTrackingStartContinuation(1L, changed, { testManualStartPreflight(ready,
			ManualTrackingStartSelection(mapOf(TrackingCaptureSource.CELL to 2L))) }) { error("Different consent") }
			.shouldBe(ManualTrackingStartResult.TRACKING_UNAVAILABLE)
		changed.pendingManualStart shouldBe null
	}

	@Test
	fun `concurrent prepared sibling result does not cancel first one-use delivery`() = runTest {
		val authority = ManualStartTestAuthority()
		executeManualTrackingStartContinuation(null, authority, { testManualStartPreflight(ManualTrackingStartReadiness.SetupPending) }) { error("Pending") }
		val prepared = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		val first = async {
			executeManualTrackingStartContinuation(1L, authority, { testManualStartPreflight(ready) }) {
				prepared.complete(Unit); release.await(); testManualStartEnqueue(authority, it)
			}
		}
		prepared.await()
		executeManualTrackingStartContinuation(1L, authority, { testManualStartPreflight(ready) }) {
			TrackingStartDispatchResult.AlreadyActive
		} shouldBe ManualTrackingStartResult.TRACKING_UNAVAILABLE
		authority.pendingManualStart?.command?.generation shouldBe 1L
		release.complete(Unit)
		first.await() shouldBe ManualTrackingStartResult.ENQUEUED
	}

	@Test
	fun `malformed saved source choices and absent original requests fail closed`() = runTest {
		listOf("CELL:1,CELL:2", "UNKNOWN:1", "CELL:-1", "CELL:abc", "CELL:1:2").forEach {
			ManualTrackingStartSelection.decode(it) shouldBe null
		}
		executeManualTrackingStartContinuation(12L, ManualStartTestAuthority(), { error("no request") }) {
			error("no request")
		} shouldBe ManualTrackingStartResult.TRACKING_UNAVAILABLE
	}
}
