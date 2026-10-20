package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.api.TRACKING_TURN_OFF_EXIT_PREFIX
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// One durable command-store fixture keeps Start, End, Off and recreation ordering checks comparable.
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPreferencesTrackingLifecycleCommandAuthorityTest {
	private val context: Context = ApplicationProvider.getApplicationContext()

	@Before
	fun setUp() = clearStore()

	@After
	fun tearDown() = clearStore()

	@Test
	fun `Turn off fences concurrent Start until cleanup and STOP are durable`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val exitId = TRACKING_TURN_OFF_EXIT_PREFIX + "test-command"
		val enteredCleanup = CompletableDeferred<Unit>()
		val finishCleanup = CompletableDeferred<Unit>()
		val stopping = async {
			authority.reserveTurnOff(exitId, 1_000L, 500L) {
				enteredCleanup.complete(Unit)
				finishCleanup.await()
				Unit
			}
		}
		enteredCleanup.await()
		val delayedStart = async { authority.reserveStart() }
		withTimeoutOrNull(100L) { delayedStart.await() } shouldBe null
		finishCleanup.complete(Unit)
		val reservation = requireNotNull(stopping.await())
		reservation.stop shouldBe authority.latestUnhandledStop()
		authority.pendingCollectionResumeExitId shouldBe exitId
		authority.resumeCollectionAfterUserExit(exitId) shouldBe false
		authority.recordUserRequestedExit("user-requested:later-process-exit") shouldBe true
		authority.pendingCollectionResumeExitId shouldBe exitId
		val start = requireNotNull(delayedStart.await())
		authority.prepareCollectionResumeForManualStart(exitId, start) shouldBe false
		authority.markStopHandled(reservation.stop) shouldBe true
		val enteredOwnerClose = CompletableDeferred<Unit>()
		val finishOwnerClose = CompletableDeferred<Unit>()
		val closing = async {
			authority.withPendingTurnOff(exitId) {
				enteredOwnerClose.complete(Unit)
				finishOwnerClose.await()
				true
			}
		}
		enteredOwnerClose.await()
		val resumed = async { authority.prepareCollectionResumeForManualStart(exitId, start) }
		withTimeoutOrNull(100L) { resumed.await() } shouldBe null
		finishOwnerClose.complete(Unit)
		closing.await() shouldBe true
		resumed.await() shouldBe true
		authority.pendingCollectionResumeExitId shouldBe exitId
		authority.isPendingCollectionStart(start) shouldBe true
		val recreated = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		recreated.pendingCollectionResumeExitId shouldBe exitId
		recreated.isPendingCollectionStart(start) shouldBe false
		authority.withCurrentStart(start, acceptWhen = { it }) { true } shouldBe LockedTrackingStartResult.Executed(true)
		SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			.pendingCollectionResumeExitId shouldBe null
	}

	@Test
	fun `failed source fence leaves durable Off and actionable session STOP`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val exitId = TRACKING_TURN_OFF_EXIT_PREFIX + "failed-fence"
		val reserved = requireNotNull(authority.reserveTurnOff(exitId, 1_000L, 500L) {
			val afterCommit = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			afterCommit.pendingCollectionResumeExitId shouldBe exitId
			afterCommit.latestUnhandledStop()?.reason shouldBe
				TrackingStopCandidateReason.EXPLICIT_REQUEST
			throw IllegalStateException("Room unavailable")
		})
		val reopened = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		reopened.pendingCollectionResumeExitId shouldBe exitId
		reopened.latestUnhandledStop() shouldBe reserved.stop
		reopened.withPendingTurnOff(exitId) { true } shouldBe null
	}

	@Test
	fun `failed preference commit exposes neither Off nor STOP and never enters Room fence`(): Unit = runBlocking {
		val prefs = mockk<SharedPreferences>()
		val editor = mockk<SharedPreferences.Editor>()
		val fakeContext = mockk<Context>()
		every { fakeContext.getSharedPreferences(any(), any()) } returns prefs
		every { prefs.getLong(any(), any()) } answers { secondArg<Long>() }
		every { prefs.getString(any(), any()) } answers { secondArg<String?>() }
		every { prefs.getBoolean(any(), any()) } answers { secondArg<Boolean>() }
		every { prefs.contains(any()) } returns false
		every { prefs.edit() } returns editor
		every { editor.putLong(any(), any()) } returns editor
		every { editor.putString(any(), any()) } returns editor
		every { editor.putBoolean(any(), any()) } returns editor
		every { editor.remove(any()) } returns editor
		every { editor.commit() } returns false
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(
			fakeContext, Dispatchers.IO, BootClockDomainProvider { "boot" },
		)
		var roomFenceEntered = false
		authority.reserveTurnOff(
			TRACKING_TURN_OFF_EXIT_PREFIX + "failed-commit", 1_000L, 500L,
		) { roomFenceEntered = true; Unit } shouldBe null
		roomFenceEntered shouldBe false
		authority.pendingCollectionResumeExitId shouldBe null
		authority.latestUnhandledStop() shouldBe null
	}

	@Test
	fun `pending cancellation cannot revoke a successor Start or change its durable Off`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val originalExit = TRACKING_TURN_OFF_EXIT_PREFIX + "original"
		val off = requireNotNull(authority.reserveTurnOff(originalExit, 1_000L, 500L) { Unit })
		authority.markStopHandled(off.stop) shouldBe true
		val first = requireNotNull(authority.reserveStart())
		authority.prepareCollectionResumeForManualStart(originalExit, first) shouldBe true
		val successor = requireNotNull(authority.reserveStart())
		authority.prepareCollectionResumeForManualStart(originalExit, successor) shouldBe true
		authority.cancelPendingCollectionStart(first.generation) shouldBe false
		authority.isPendingCollectionStart(successor) shouldBe true
		authority.pendingCollectionResumeExitId shouldBe originalExit
		authority.withCurrentStart(successor, acceptWhen = { it }) { true } shouldBe
			LockedTrackingStartResult.Executed(true)
		authority.cancelPendingCollectionStart(first.generation) shouldBe false
		authority.pendingCollectionResumeExitId shouldBe null
	}

	@Test
	fun `delayed Start reservation requires unchanged durable lifecycle generation`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val firstTapGeneration = authority.latestCommandGeneration()
		val first = requireNotNull(authority.reserveStartIfGeneration(firstTapGeneration))
		authority.reserveStartIfGeneration(firstTapGeneration) shouldBe null
		authority.latestCommandGeneration() shouldBe first.generation

		val beforeStop = authority.latestCommandGeneration()
		val stop = requireNotNull(authority.reserveStop(
			TrackingStopCandidateReason.EXPLICIT_REQUEST,
			requestedAtEpochMs = 1_000L,
		))
		authority.reserveStartIfGeneration(beforeStop) shouldBe null
		authority.latestCommandGeneration() shouldBe stop.generation

		val beforeExit = authority.latestCommandGeneration()
		authority.recordUserRequestedExit("explicit-exit") shouldBe true
		authority.reserveStartIfGeneration(beforeExit) shouldBe null
		val reopened = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		reopened.latestCommandGeneration() shouldBe authority.latestCommandGeneration()
		reopened.reserveStartIfGeneration(beforeExit) shouldBe null
	}

    @Test
    fun `pending manual Start keeps exit and old boundary through the startup fence`(): Unit = runBlocking {
        val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
        authority.recordUserRequestedExit("exit-1") shouldBe true
        authority.resumeCollectionAfterUserExit("exit-1") shouldBe true
        val priorBoundary = authority.collectionResumeBoundary
        authority.recordUserRequestedExit("exit-2") shouldBe true
        val fresh = requireNotNull(authority.reserveStart())
        withTimeout(2_000L) {
            authority.prepareCollectionResumeForManualStart("exit-2", fresh) shouldBe true
            authority.runWithHandledStopGeneration(0L) {
                authority.pendingCollectionResumeExitId shouldBe "exit-2"
                authority.collectionResumeBoundary shouldBe priorBoundary
                authority.isPendingCollectionStart(fresh) shouldBe true
            } shouldBe true
        }
        authority.withCurrentStart(fresh, acceptWhen = { it }) { false } shouldBe LockedTrackingStartResult.Executed(false)
        authority.pendingCollectionResumeExitId shouldBe "exit-2"
        authority.cancelPendingCollectionStart(fresh.generation) shouldBe true
        authority.withCurrentStart(fresh) { error("Cancelled delivery must never execute") } shouldBe LockedTrackingStartResult.Stale
        val reopened = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
        reopened.pendingCollectionResumeExitId shouldBe "exit-2"
        reopened.collectionResumeBoundary shouldBe priorBoundary
    }

    @Test
    fun `new exit or newer Start keeps obsolete manual resume suppressed`(): Unit = runBlocking {
        val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
        authority.recordUserRequestedExit("exit-1") shouldBe true
        val first = requireNotNull(authority.reserveStart())
        requireNotNull(authority.reserveStart())
        authority.prepareCollectionResumeForManualStart("exit-1", first) shouldBe false
        val current = requireNotNull(authority.reserveStart())
        authority.recordUserRequestedExit("exit-2") shouldBe true
        authority.prepareCollectionResumeForManualStart("exit-1", current) shouldBe false
        authority.prepareCollectionResumeForManualStart("exit-2", current) shouldBe false
        authority.pendingCollectionResumeExitId shouldBe "exit-2"
    }

	@Test
	fun `user exit suppression and exact resume boundary survive recreation`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		authority.recordUserRequestedExit("user-requested:100") shouldBe true
		val stopped = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		stopped.pendingCollectionResumeExitId shouldBe "user-requested:100"
		stopped.resumeCollectionAfterUserExit("stale") shouldBe false
		stopped.resumeCollectionAfterUserExit("user-requested:100") shouldBe true
		val boundary = requireNotNull(stopped.collectionResumeBoundary)
		val resumed = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		resumed.pendingCollectionResumeExitId shouldBe null
		resumed.collectionResumeBoundary shouldBe boundary
		resumed.recordUserRequestedExit("user-requested:100") shouldBe true
		resumed.pendingCollectionResumeExitId shouldBe null
		resumed.collectionResumeBoundary shouldBe boundary
		resumed.recordUserRequestedExit("user-requested:200") shouldBe true
		resumed.resumeCollectionAfterUserExit("user-requested:100") shouldBe false
		resumed.pendingCollectionResumeExitId shouldBe "user-requested:200"
	}

	@Test
	fun `stop tombstone is committed across authority recreation before runtime delivery`(): Unit = runBlocking {
		val first = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val queuedStart = requireNotNull(first.reserveStart())
		val stop = requireNotNull(
			first.reserveStop(
				TrackingStopCandidateReason.EXPLICIT_REQUEST,
				1_000L,
				requestedElapsedRealtimeNanos = 555_000L,
			),
		)

		val recreated = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		recreated.resolveStart(queuedStart) shouldBe TrackingStartCommandDisposition.BlockedByStop(stop)
		recreated.latestUnhandledStop() shouldBe stop
		stop.requestedBootId?.isNotBlank() shouldBe true
		stop.requestedElapsedRealtimeNanos shouldBe 555_000L
		recreated.isStopActionable(stop) shouldBe true
		recreated.markStopHandled(stop) shouldBe true

		val afterHandling =
			SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		afterHandling.latestUnhandledStop() shouldBe null
		afterHandling.latestStopGeneration() shouldBe stop.generation
	}

	@Test
	fun `newer reservation cannot suppress reconstructed stop until terminal handling and acceptance`(): Unit =
		runBlocking {
		val first = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val stop = requireNotNull(
			first.reserveStop(
				TrackingStopCandidateReason.AUTOMATIC_ACTIVITY_INCOMPATIBLE,
				4_321L,
			),
		)

		val recreated = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		recreated.latestUnhandledStop() shouldBe stop
		val newerStart = requireNotNull(recreated.reserveStart())

		val afterSecondRecreation =
			SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		afterSecondRecreation.latestUnhandledStop() shouldBe stop
		afterSecondRecreation.resolveStart(newerStart) shouldBe
			TrackingStartCommandDisposition.BlockedByStop(stop)
		afterSecondRecreation.isStopActionable(stop) shouldBe true

		afterSecondRecreation.markStopHandled(stop) shouldBe true
		afterSecondRecreation.withCurrentStart(
			newerStart,
			acceptWhen = { accepted -> accepted },
		) { true } shouldBe LockedTrackingStartResult.Executed(true)
		SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			.latestUnhandledStop() shouldBe null
	}

	@Test
	fun `automatic deadline rejection and cancellation reservations leave older stop actionable`(): Unit = runBlocking {
			listOf("deadline", "rejection", "cancellation").forEach { _ ->
				clearStore()
				val authority =
					SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
				val stop = requireNotNull(
					authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1_000L),
				)
				requireNotNull(authority.reserveStart())

				val recreated =
					SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
				recreated.latestUnhandledStop() shouldBe stop
				recreated.isStopActionable(stop) shouldBe true
			}
		}

	@Test
	fun `manual platform failure reservation leaves older stop actionable after recreation`(): Unit =
		runBlocking {
			val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			val stop = requireNotNull(
				authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1_000L),
			)
			requireNotNull(authority.reserveStart())

			SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
				.latestUnhandledStop() shouldBe stop
		}

	@Test
	fun `only foreground and Room success preserves accepted redelivery beside newer reservation`(): Unit =
		runBlocking {
			val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			val accepted = requireNotNull(authority.reserveStart())
			authority.withCurrentStart(
				accepted,
				acceptWhen = { foregroundAndRoomAccepted -> foregroundAndRoomAccepted },
			) { true } shouldBe LockedTrackingStartResult.Executed(true)
			val newerReservation = requireNotNull(authority.reserveStart())

			val recreated =
				SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			recreated.resolveStart(accepted) shouldBe TrackingStartCommandDisposition.Allowed
			recreated.resolveStart(newerReservation) shouldBe TrackingStartCommandDisposition.Allowed
		}

	@Test
	fun `active redelivery reserves a distinct recovery generation atomically`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val accepted = requireNotNull(authority.reserveStart())
		authority.withCurrentStart(accepted, acceptWhen = { it }) { true } shouldBe
			LockedTrackingStartResult.Executed(true)

		val reservation = authority.reserveRedeliveryRecoveryStart(accepted)
			as TrackingRedeliveryRecoveryReservation.Reserved

		(reservation.command.generation > accepted.generation) shouldBe true
		authority.resolveStart(reservation.command) shouldBe TrackingStartCommandDisposition.Allowed
	}

	@Test
	fun `stop reservation atomically blocks passive redelivery recovery`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val accepted = requireNotNull(authority.reserveStart())
		authority.withCurrentStart(accepted, acceptWhen = { it }) { true } shouldBe
			LockedTrackingStartResult.Executed(true)
		val stop = requireNotNull(
			authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L),
		)

		authority.reserveRedeliveryRecoveryStart(accepted) shouldBe
			TrackingRedeliveryRecoveryReservation.BlockedByStop(stop)
		authority.latestUnhandledStop() shouldBe stop
	}

	@Test
	fun `failed foreground acceptance is not durable beside a newer reservation`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val rejected = requireNotNull(authority.reserveStart())
		authority.withCurrentStart(
			rejected,
			acceptWhen = { foregroundAndRoomAccepted -> foregroundAndRoomAccepted },
		) { false } shouldBe LockedTrackingStartResult.Executed(false)
		val newerReservation = requireNotNull(authority.reserveStart())

		val recreated = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		recreated.resolveStart(rejected) shouldBe TrackingStartCommandDisposition.Stale
		recreated.resolveStart(newerReservation) shouldBe TrackingStartCommandDisposition.Allowed
	}

	@Test
	fun `newer start waits until terminal stop action is durably handled`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val stop = requireNotNull(
			authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L),
		)
		val actionEntered = CompletableDeferred<Unit>()
		val releaseAction = CompletableDeferred<Unit>()
		val completion = async(Dispatchers.Default) {
			authority.completeActionableStop(stop) {
				actionEntered.complete(Unit)
				releaseAction.await()
				TrackingStopActionResult.Terminal("terminal")
			}
		}
		actionEntered.await()

		val newerStart = async(Dispatchers.Default) { authority.reserveStart() }
		withTimeoutOrNull(100L) { newerStart.await() } shouldBe null
		releaseAction.complete(Unit)

		completion.await() shouldBe LockedTrackingStopResult.Handled("terminal")
		requireNotNull(newerStart.await())
		authority.latestUnhandledStop() shouldBe null
	}

	@Test
	fun `cancelled stop action remains durably actionable`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val stop = requireNotNull(
			authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L),
		)

		shouldThrow<CancellationException> {
			authority.completeActionableStop<Unit>(stop) {
				throw CancellationException("cancel exact finalization")
			}
		}

		SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			.latestUnhandledStop() shouldBe stop
	}

	@Test
	fun `foreground acceptance and concurrent stop reservation linearize without blocking readers`(): Unit =
		runBlocking {
			val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			val start = requireNotNull(authority.reserveStart())
			val events = mutableListOf<String>()
			val acceptanceEntered = CompletableDeferred<Unit>()
			val releaseAcceptance = CompletableDeferred<Unit>()
			val acceptance = async(Dispatchers.Default) {
				authority.withCurrentStart(
					start,
					acceptWhen = { result -> result == "accepted" },
				) {
					events += "FGS_ENTERED"
					acceptanceEntered.complete(Unit)
					releaseAcceptance.await()
					events += "ROOM_COMMITTED"
					"accepted"
				}
			}
			acceptanceEntered.await()

			withTimeout(500L) {
				withContext(Dispatchers.Default) { authority.resolveStart(start) }
			} shouldBe TrackingStartCommandDisposition.Allowed
			val stopReservation = async(Dispatchers.Default) {
				authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L).also {
					events += "STOP_RESERVED"
				}
			}
			withTimeoutOrNull(100L) { stopReservation.await() } shouldBe null

			releaseAcceptance.complete(Unit)
			acceptance.await() shouldBe LockedTrackingStartResult.Executed("accepted")
			val stop = requireNotNull(stopReservation.await())
			events shouldBe listOf("FGS_ENTERED", "ROOM_COMMITTED", "STOP_RESERVED")
			authority.resolveStart(start) shouldBe TrackingStartCommandDisposition.BlockedByStop(stop)
		}

	@Test
	fun `single-thread dispatcher stays live and cancelled stop waiter never reserves late`(): Unit =
		runBlocking {
			val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
			try {
				val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, dispatcher)
				val start = withContext(dispatcher) { requireNotNull(authority.reserveStart()) }
				val acceptanceEntered = CompletableDeferred<Unit>()
				val releaseAcceptance = CompletableDeferred<Unit>()
				val acceptance = async(dispatcher) {
					authority.withCurrentStart(start) {
						acceptanceEntered.complete(Unit)
						releaseAcceptance.await()
					}
				}
				acceptanceEntered.await()
				var reservedAfterCancellation = false
				val queuedStop = async(dispatcher) {
					authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 3_000L)
					reservedAfterCancellation = true
				}

				withTimeout(500L) { withContext(dispatcher) { "heartbeat" } } shouldBe "heartbeat"
				queuedStop.cancelAndJoin()
				releaseAcceptance.complete(Unit)
				acceptance.await()
				reservedAfterCancellation shouldBe false
				authority.resolveStart(start) shouldBe TrackingStartCommandDisposition.Allowed
			} finally {
				dispatcher.close()
			}
		}

	@Test
	fun `STOP reservation waits until handled-generation handoff is complete`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val events = mutableListOf<String>()
		val handoffEntered = CompletableDeferred<Unit>()
		val releaseHandoff = CompletableDeferred<Unit>()
		val handoff = async(Dispatchers.Default) {
			authority.runWithHandledStopGeneration(expectedStopGeneration = 0L) {
				events += "HANDOFF_ENTERED"
				handoffEntered.complete(Unit)
				releaseHandoff.await()
				events += "HANDOFF_COMPLETE"
			}
		}
		handoffEntered.await()

		val stopReservation = async(Dispatchers.Default) {
			authority.reserveStop(
				TrackingStopCandidateReason.EXPLICIT_REQUEST,
				2_000L,
			).also { events += "STOP_RESERVED" }
		}
		withTimeoutOrNull(100L) { stopReservation.await() } shouldBe null

		releaseHandoff.complete(Unit)
		handoff.await() shouldBe true
		requireNotNull(stopReservation.await())
		events shouldBe listOf("HANDOFF_ENTERED", "HANDOFF_COMPLETE", "STOP_RESERVED")
	}

	@Test
	fun `timed out guarded start waiter never executes after the owner releases`(): Unit = runBlocking {
		val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
		try {
			val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, dispatcher)
			val first = withContext(dispatcher) { requireNotNull(authority.reserveStart()) }
			val ownerEntered = CompletableDeferred<Unit>()
			val releaseOwner = CompletableDeferred<Unit>()
			val owner = async(dispatcher) {
				authority.withCurrentStart(first) {
					ownerEntered.complete(Unit)
					releaseOwner.await()
				}
			}
			ownerEntered.await()
			var lateSideEffect = false

			withTimeoutOrNull(100L) {
				withContext(dispatcher) {
					authority.withCurrentStart(first) { lateSideEffect = true }
				}
			} shouldBe null
			releaseOwner.complete(Unit)
			owner.await()
			withTimeout(500L) { withContext(dispatcher) { Unit } }
			lateSideEffect shouldBe false
			authority.resolveStart(first) shouldBe TrackingStartCommandDisposition.Allowed
		} finally {
			dispatcher.close()
		}
	}

	@Test
	fun `stop reserved first prevents both FGS and Room acceptance lambdas`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val start = requireNotNull(authority.reserveStart())
		val stop = requireNotNull(
			authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L),
		)
		var foregroundInvoked = false
		var roomInvoked = false

		authority.withCurrentStart(start) {
			foregroundInvoked = true
			roomInvoked = true
		} shouldBe LockedTrackingStartResult.BlockedByStop(stop)
		foregroundInvoked shouldBe false
		roomInvoked shouldBe false
	}

	@Test
	fun `old Off cleanup cannot fence a pending successor preparation and resumes after exact cancel`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val exit = TRACKING_TURN_OFF_EXIT_PREFIX + "old-cleanup"
		val off = requireNotNull(authority.reserveTurnOff(exit, 1_000L, 500L) { Unit })
		authority.markStopHandled(off.stop) shouldBe true
		val start = requireNotNull(authority.reserveStart())
		authority.prepareCollectionResumeForManualStart(exit, start) shouldBe true
		var closureEntered = false
		authority.withPendingTurnOff(exit) { closureEntered = true; true } shouldBe null
		closureEntered shouldBe false
		authority.cancelPendingCollectionStart(start.generation) shouldBe true
		authority.withPendingTurnOff(exit) { closureEntered = true; true } shouldBe true
		closureEntered shouldBe true
		authority.pendingCollectionResumeExitId shouldBe exit
	}

	@Test
	fun `handled Stop signal follows the exact durable terminal receipt and survives recreation`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		authority.handledStopGeneration.value shouldBe 0L
		val first = requireNotNull(authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1_000L))
		authority.handledStopGeneration.value shouldBe 0L
		authority.completeActionableStop(first) {
			TrackingStopActionResult.StillActionable("pending")
		} shouldBe LockedTrackingStopResult.StillActionable("pending")
		authority.handledStopGeneration.value shouldBe 0L

		val current = requireNotNull(authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L))
		authority.markStopHandled(first) shouldBe false
		authority.completeActionableStop<String>(first) {
			error("Superseded STOP must not execute")
		} shouldBe LockedTrackingStopResult.Superseded
		authority.handledStopGeneration.value shouldBe 0L
		authority.completeActionableStop(current) {
			TrackingStopActionResult.Terminal("done")
		} shouldBe LockedTrackingStopResult.Handled("done")
		authority.handledStopGeneration.value shouldBe current.generation
		SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			.handledStopGeneration.value shouldBe current.generation

		val next = requireNotNull(authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 3_000L))
		authority.handledStopGeneration.value shouldBe current.generation
		authority.markStopHandled(next) shouldBe true
		authority.handledStopGeneration.value shouldBe next.generation
	}

	@Test
	fun `accepted Start signal follows durable acceptance rather than reservation`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		authority.acceptedStartGeneration.value shouldBe 0L
		val start = requireNotNull(authority.reserveStart())
		authority.acceptedStartGeneration.value shouldBe 0L
		authority.withCurrentStart(start, acceptWhen = { it }) { false }
		authority.acceptedStartGeneration.value shouldBe 0L
		authority.withCurrentStart(start, acceptWhen = { it }) { true }
		authority.acceptedStartGeneration.value shouldBe start.generation
		SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			.acceptedStartGeneration.value shouldBe start.generation
	}

	@Test
	fun `failed acceptance preference publication restores stopped cache and revokes exact delivery`(): Unit = runBlocking {
		for (throwing in listOf(false, true)) {
			clearStore()
			val actual = context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE)
			var failAcceptance = false
			val proxy = object : SharedPreferences by actual {
				override fun edit(): SharedPreferences.Editor {
					val editor = actual.edit()
					return object : SharedPreferences.Editor by editor {
						override fun putLong(key: String?, value: Long) = apply { editor.putLong(key, value) }
						override fun putString(key: String?, value: String?) = apply { editor.putString(key, value) }
						override fun putBoolean(key: String?, value: Boolean) = apply { editor.putBoolean(key, value) }
						override fun remove(key: String?) = apply { editor.remove(key) }
						override fun commit(): Boolean {
							val committed = editor.commit()
							if (failAcceptance) {
								failAcceptance = false
								if (throwing) {
									throw IllegalStateException("publication failed after cache update")
								}
								return false
							}
							return committed
						}
					}
				}
			}
			val fakeContext = mockk<Context>()
			every { fakeContext.getSharedPreferences(any(), any()) } returns proxy
			val authority = SharedPreferencesTrackingLifecycleCommandAuthority(fakeContext, Dispatchers.IO,
				BootClockDomainProvider { "boot" })
			authority.recordUserRequestedExit("exit-1") shouldBe true
			val start = requireNotNull(authority.reserveStart())
			authority.prepareCollectionResumeForManualStart("exit-1", start) shouldBe true
			failAcceptance = true
			shouldThrow<IllegalStateException> { authority.withCurrentStart(start, acceptWhen = { it }) { true } }
			authority.pendingCollectionResumeExitId shouldBe "exit-1"
			authority.isPendingCollectionStart(start) shouldBe false
			authority.acceptedStartGeneration.value shouldBe 0L
			val reopened = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			reopened.pendingCollectionResumeExitId shouldBe "exit-1"
			reopened.collectionResumeBoundary shouldBe null
			reopened.isPendingCollectionStart(start) shouldBe false
		}
	}

	@Test
	fun `failed pending Start reservation keeps Off and grants no delivery after recreation`(): Unit = runBlocking {
		for (throwing in listOf(false, true)) {
			clearStore()
			val actual = context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE)
			var failReservation = false
			val proxy = object : SharedPreferences by actual {
				override fun edit(): SharedPreferences.Editor {
					val editor = actual.edit()
					return object : SharedPreferences.Editor by editor {
						override fun putLong(key: String?, value: Long) = apply { editor.putLong(key, value) }
						override fun putString(key: String?, value: String?) = apply { editor.putString(key, value) }
						override fun putBoolean(key: String?, value: Boolean) = apply { editor.putBoolean(key, value) }
						override fun remove(key: String?) = apply { editor.remove(key) }
						override fun commit(): Boolean {
							val committed = editor.commit()
							if (failReservation) {
								failReservation = false
								if (throwing) {
									throw IllegalStateException("pending reservation failed after cache update")
								}
								return false
							}
							return committed
						}
					}
				}
			}
			val fakeContext = mockk<Context>()
			every { fakeContext.getSharedPreferences(any(), any()) } returns proxy
			val authority = SharedPreferencesTrackingLifecycleCommandAuthority(fakeContext, Dispatchers.IO,
				BootClockDomainProvider { "boot" })
			authority.recordUserRequestedExit("exit-1") shouldBe true
			val start = requireNotNull(authority.reserveStart())
			failReservation = true
			if (throwing) {
				shouldThrow<IllegalStateException> { authority.prepareCollectionResumeForManualStart("exit-1", start) }
			} else {
				authority.prepareCollectionResumeForManualStart("exit-1", start) shouldBe false
			}
			authority.pendingCollectionResumeExitId shouldBe "exit-1"
			authority.isPendingCollectionStart(start) shouldBe false
			authority.withCurrentStart(start) { error("Failed reservation must not deliver") } shouldBe LockedTrackingStartResult.Stale
			val reopened = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			reopened.pendingCollectionResumeExitId shouldBe "exit-1"
			reopened.collectionResumeBoundary shouldBe null
			reopened.isPendingCollectionStart(start) shouldBe false
			reopened.reserveRedeliveryRecoveryStart(start) shouldBe TrackingRedeliveryRecoveryReservation.Stale
		}
	}

	@Test
	fun `cancelled acceptance and handled newer End leave Off and reject late delivery`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		authority.recordUserRequestedExit("exit-1") shouldBe true
		val start = requireNotNull(authority.reserveStart())
		authority.prepareCollectionResumeForManualStart("exit-1", start) shouldBe true
		shouldThrow<CancellationException> {
			authority.withCurrentStart(start, acceptWhen = { true }) { throw CancellationException("cancelled before Room accepts") }
		}
		authority.pendingCollectionResumeExitId shouldBe "exit-1"
		val stop = requireNotNull(authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L))
		authority.markStopHandled(stop) shouldBe true
		authority.cancelPendingCollectionStart(start.generation) shouldBe false
		authority.withCurrentStart(start) { error("Late foreground must never run") } shouldBe LockedTrackingStartResult.Stale
		authority.pendingCollectionResumeExitId shouldBe "exit-1"
		val recreated = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		recreated.reserveRedeliveryRecoveryStart(start) shouldBe TrackingRedeliveryRecoveryReservation.Stale
	}

	@Test
	fun `manual permission handoff survives another screen only in its original process`(): Unit = runBlocking {
		val process = com.adsamcik.tracker.shared.base.process.ProcessIncarnationIdProvider()
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO,
			BootClockDomainProvider { "boot" }, process)
		val selection = com.adsamcik.tracker.tracker.api.ManualTrackingStartSelection(mapOf(
			com.adsamcik.tracker.tracker.api.TrackingCaptureSource.STEPS to 7L))
		val pending = requireNotNull(authority.reserveManualStartIfGeneration(0L, selection))
		val sameProcess = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO,
			BootClockDomainProvider { "boot" }, process)
		sameProcess.pendingManualStart shouldBe pending
		sameProcess.consumeManualStart(pending.command.generation, selection) shouldBe pending.command
		sameProcess.consumeManualStart(pending.command.generation, selection) shouldBe null
	}

	@Test
	fun `a new process rejects an old manual permission intent and keeps collection Off`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO,
			BootClockDomainProvider { "boot" }, com.adsamcik.tracker.shared.base.process.ProcessIncarnationIdProvider())
		authority.recordUserRequestedExit("off") shouldBe true
		val selection = com.adsamcik.tracker.tracker.api.ManualTrackingStartSelection(mapOf(
			com.adsamcik.tracker.tracker.api.TrackingCaptureSource.WIFI to 9L))
		val pending = requireNotNull(authority.reserveManualStartIfGeneration(authority.latestCommandGeneration(), selection))
		val recreated = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO,
			BootClockDomainProvider { "boot" }, com.adsamcik.tracker.shared.base.process.ProcessIncarnationIdProvider())
		recreated.pendingManualStart shouldBe null
		recreated.consumeManualStart(pending.command.generation, selection) shouldBe null
		recreated.pendingCollectionResumeExitId shouldBe "off"
		recreated.isPendingCollectionStart(pending.command) shouldBe false
	}

	@Test
	fun `automatic observation delivered after handled End cannot reuse its earlier time`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO, BootClockDomainProvider { "boot" })
		val end = requireNotNull(authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1_000L, 100L))
		authority.markStopHandled(end) shouldBe true
		authority.reserveStartIfGeneration(authority.latestCommandGeneration(), automaticTrigger("boot", 99L)) shouldBe null
		authority.latestCommandGeneration() shouldBe end.generation
	}

	@Test
	fun `End during callback wait rejects even a newer observed point with the old generation`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO, BootClockDomainProvider { "boot" })
		val captured = authority.latestCommandGeneration()
		val end = requireNotNull(authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1_000L, 100L))
		authority.markStopHandled(end) shouldBe true
		authority.reserveStartIfGeneration(captured, automaticTrigger("boot", 101L)) shouldBe null
		authority.latestCommandGeneration() shouldBe end.generation
	}

	@Test
	fun `exact End cutoff stays in frozen prefix and only later automatic evidence may reserve`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO, BootClockDomainProvider { "boot" })
		val end = requireNotNull(authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1_000L, 100L))
		authority.markStopHandled(end) shouldBe true
		authority.reserveStartIfGeneration(end.generation, automaticTrigger("boot", 100L)) shouldBe null
		val fresh = requireNotNull(authority.reserveStartIfGeneration(end.generation, automaticTrigger("boot", 101L)))
		fresh.generation shouldBe end.generation + 1L
	}

	@Test
	fun `automatic evidence must use current boot and cannot compare counters across reboot`(): Unit = runBlocking {
		var boot = "boot-old"
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO, BootClockDomainProvider { boot })
		val end = requireNotNull(authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1_000L, 100L))
		authority.markStopHandled(end) shouldBe true
		boot = "boot-new"
		authority.reserveStartIfGeneration(end.generation, automaticTrigger("boot-old", 101L)) shouldBe null
		// New-boot eligibility still needs current CONTROL/registration proof in the source reader.
		requireNotNull(authority.reserveStartIfGeneration(end.generation, automaticTrigger("boot-new", 1L)))
			.generation shouldBe end.generation + 1L
	}

	@Test
	fun `handled Off still blocks newer automatic evidence without changing choices or exit`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO, BootClockDomainProvider { "boot" })
		val exit = TRACKING_TURN_OFF_EXIT_PREFIX + "automatic"
		val off = requireNotNull(authority.reserveTurnOff(exit, 1_000L, 100L) { Unit })
		authority.markStopHandled(off.stop) shouldBe true
		val generation = authority.latestCommandGeneration()
		authority.reserveStartIfGeneration(generation, automaticTrigger("boot", 101L)) shouldBe null
		authority.pendingCollectionResumeExitId shouldBe exit
		authority.latestCommandGeneration() shouldBe generation
	}

	@Test
	fun `unknown Stop clock cannot grant automatic Start and manual Start remains independent`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO, BootClockDomainProvider { "boot" })
		val end = requireNotNull(authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1_000L))
		authority.markStopHandled(end) shouldBe true
		authority.reserveStartIfGeneration(end.generation, automaticTrigger("boot", 101L)) shouldBe null
		requireNotNull(authority.reserveStart()).generation shouldBe end.generation + 1L
	}

	@Test
	fun `duplicate automatic reservation cannot spend one original callback generation twice`(): Unit = runBlocking {
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO, BootClockDomainProvider { "boot" })
		val captured = authority.latestCommandGeneration()
		val trigger = automaticTrigger("boot", 101L)
		val first = requireNotNull(authority.reserveStartIfGeneration(captured, trigger))
		authority.reserveStartIfGeneration(captured, trigger) shouldBe null
		authority.latestCommandGeneration() shouldBe first.generation
	}

	private fun automaticTrigger(boot: String, observed: Long): AutomaticTrackingStartTrigger = mockk {
		every { bootId } returns boot
		every { observedElapsedRealtimeNanos } returns observed
	}

	private fun clearStore() {
		context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE)
			.edit()
			.clear()
			.commit()
	}
}
