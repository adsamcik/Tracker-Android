package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPreferencesTrackingLifecycleCommandAuthorityTest {
	private val context: Context = ApplicationProvider.getApplicationContext()

	@Before
	fun setUp() = clearStore()

	@After
	fun tearDown() = clearStore()

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
	fun `automatic deadline rejection and cancellation reservations leave older stop actionable`() =
		runBlocking {
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

	private fun clearStore() {
		context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE)
			.edit()
			.clear()
			.commit()
	}
}
