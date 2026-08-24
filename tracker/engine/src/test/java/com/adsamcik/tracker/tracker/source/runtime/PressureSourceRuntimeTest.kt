package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PressureSourceRuntimeTest {
	@Test
	fun `suspended authorization refresh latches post-boundary callback and replays revoke without second listener`() =
		runTest {
			ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
			val initialPlan = plan(revision = 1L)
			val refreshedPlan = plan(revision = 2L)
			val refreshEntered = CompletableDeferred<Long>()
			val releaseRefresh = CompletableDeferred<Unit>()
			val fixture = fixture(
				scope = this,
				registrationsToReturn = listOf(
					registration(initialPlan, 1L, requiresAcceptance = true, effectiveElapsedNanos = 1L),
				),
				compatibleRefresh = { effectiveElapsedNanos ->
					refreshEntered.complete(effectiveElapsedNanos)
					releaseRefresh.await()
					registration(
						refreshedPlan,
						authorizationRevision = 2L,
						effectiveElapsedNanos = effectiveElapsedNanos,
						purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
					)
				},
			)
			val oldSink = RecordingPressureSink()
			val revokedSink = RecordingPressureSink()
			assertTrue(fixture.runtime.start(initialPlan, oldSink) is SourceStartResult.Started)

			val refresh = async { fixture.runtime.reconfigure(refreshedPlan, revokedSink) }
			val boundary = refreshEntered.await()
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, boundary - 1L, 1_000f))
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, boundary, 1_001f))
			releaseRefresh.complete(Unit)
			assertTrue(refresh.await() is SourceApplyResult.Applied)
			fixture.runtime.close()

			assertEquals(listOf(1L), oldSink.candidates.map { it.authorizationRevision })
			assertEquals(listOf(2L), revokedSink.candidates.map { it.authorizationRevision })
			assertTrue(oldSink.windows.single().windowEndElapsedRealtimeNanos < boundary)
			assertTrue(revokedSink.windows.single().windowStartElapsedRealtimeNanos >= boundary)
			verify(exactly = 1) {
				fixture.sensorManager.registerListener(
					any<SensorEventListener>(), fixture.sensor, any<Int>(), any<Int>(),
				)
			}
		}

	@Test
	fun `failed suspended authorization refresh returns buffered callback to old authority`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
		val initialPlan = plan(revision = 1L)
		val refreshedPlan = plan(revision = 2L)
		val refreshEntered = CompletableDeferred<Long>()
		val releaseRefresh = CompletableDeferred<Unit>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, 1L, requiresAcceptance = true, effectiveElapsedNanos = 1L),
			),
			compatibleRefresh = { effectiveElapsedNanos ->
				refreshEntered.complete(effectiveElapsedNanos)
				releaseRefresh.await()
				throw IllegalStateException("Room unavailable")
			},
		)
		val oldSink = RecordingPressureSink()
		val unusedSink = RecordingPressureSink()
		assertTrue(fixture.runtime.start(initialPlan, oldSink) is SourceStartResult.Started)

		val refresh = async { fixture.runtime.reconfigure(refreshedPlan, unusedSink) }
		val boundary = refreshEntered.await()
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, boundary - 1L, 1_000f))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, boundary, 1_001f))
		releaseRefresh.complete(Unit)
		assertTrue(refresh.await() is SourceApplyResult.Failed)
		fixture.runtime.close()

		assertEquals(listOf(1L, 1L), oldSink.candidates.map { it.authorizationRevision })
		assertTrue(oldSink.windows[0].windowEndElapsedRealtimeNanos < boundary)
		assertTrue(oldSink.windows[1].windowStartElapsedRealtimeNanos >= boundary)
		assertTrue(unusedSink.candidates.isEmpty())
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(
				any<SensorEventListener>(), fixture.sensor, any<Int>(), any<Int>(),
			)
		}
	}

	@Test
	fun `observed time keeps delayed sample old and splits first refreshed window`() {
		val oldPlan = plan(revision = 1L)
		val refreshedPlan = plan(revision = 2L)
		val oldRegistration = registration(
			oldPlan,
			authorizationRevision = 1L,
			effectiveElapsedNanos = 100L,
		)
		val refreshedRegistration = registration(
			refreshedPlan,
			authorizationRevision = 2L,
			effectiveElapsedNanos = 300L,
		)
		val oldSink = mockk<SourceEventSink>()
		val refreshedSink = mockk<SourceEventSink>()
		val timeline = PressureObservedAuthorizationTimeline(oldRegistration, oldSink)
		timeline.refresh(refreshedRegistration, refreshedSink)

		val delayed = requireNotNull(timeline.atObservedTime(250L))
		assertSame(oldRegistration, delayed.registration)
		assertSame(oldSink, delayed.sink)
		val oldAccumulator = PressureWindowAccumulator(1_000L, oldRegistration.pressureAccumulatorBoundary())
		oldAccumulator.add(1_000f, 250L, 1L)

		val refreshed = requireNotNull(timeline.atObservedTime(300L))
		assertSame(refreshedRegistration, refreshed.registration)
		assertFalse(delayed.samePressureBoundary(refreshed))
		val oldWindow = requireNotNull(oldAccumulator.drain())
		val refreshedAccumulator = PressureWindowAccumulator(
			1_000L,
			refreshedRegistration.pressureAccumulatorBoundary(),
		)
		refreshedAccumulator.add(1_001f, 300L, 2L)
		val refreshedWindow = requireNotNull(refreshedAccumulator.drain())

		assertEquals(250L, oldWindow.windowEndElapsedRealtimeNanos)
		assertEquals(300L, refreshedWindow.windowStartElapsedRealtimeNanos)
		assertNull(timeline.atObservedTime(299L))
	}

	@Test
	fun `process restart gap checkpoint failure prevents provider registration`() = runTest {
		val activePlan = plan(revision = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, authorizationRevision = 1L)),
			failRuntimeCheckpoint = true,
		)

		val result = fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(result is SourceStartResult.Failed)
		verify(exactly = 0) {
			fixture.sensorManager.registerListener(
				any<SensorEventListener>(), fixture.sensor, any<Int>(), any<Int>(),
			)
		}
	}

	@Test
	fun `initial registration exception performs defensive listener cleanup`() = runTest {
		val activePlan = plan(revision = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, authorizationRevision = 1L, requiresAcceptance = true),
			),
			registerFailure = IllegalStateException("sensor service unavailable"),
		)

		val result = fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(result is SourceStartResult.Failed)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
	}

	@Test
	fun `acceptance failure retains exact listener and blocks replacement until removal retry completes`() = runTest {
		val activePlan = plan(revision = 1L)
		val activeRegistration = registration(
			activePlan,
			authorizationRevision = 1L,
			requiresAcceptance = true,
		)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				activeRegistration,
				registration(activePlan, authorizationRevision = 2L, requiresAcceptance = true, generation = 10L),
			),
			markAcceptanceFailure = IllegalStateException("acceptance stale"),
			unregisterFailures = listOf(IllegalStateException("SensorManager removal failed"), null),
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Failed)
		val retainedListener = fixture.listeners.single()
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)

		verify(exactly = 2) {
			fixture.sensorManager.registerListener(
				any<SensorEventListener>(), fixture.sensor, any<Int>(), any<Int>(),
			)
		}
		verify(exactly = 2) { fixture.sensorManager.unregisterListener(retainedListener) }
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		fixture.runtime.close()
	}

	@Test
	fun `terminal retirement is durable before exact listener removal and token completion`() = runTest {
		val events = mutableListOf<String>()
		val activePlan = plan(1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			operationEvents = events,
		)
		assertTrue(fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) } is SourceStartResult.Started)
		events.clear()

		fixture.runtime.close()

		assertEquals(listOf("begin-retirement", "unregister", "complete-retirement"), events)
	}

	@Test
	fun `stop acknowledgement reports provider failed while exact removal remains incomplete`() = runTest {
		val activePlan = plan(1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			unregisterFailures = listOf(IllegalStateException("removal unavailable")),
		)
		assertTrue(fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) } is SourceStartResult.Started)
		val now = android.os.SystemClock.elapsedRealtimeNanos()

		val ack = fixture.runtime.quiesce(
			SessionCutoff("pressure-test", Long.MAX_VALUE, 1L, now + 1_000_000_000L),
		)

		assertEquals(RegistrationRemovalOutcome.FAILED, ack.registrationRemovalOutcome)
		assertEquals(SourceStopStatus.PROVIDER_FAILED, ack.status)
		coVerify(exactly = 0) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `ambiguous completion retries the same immutable token without removing listener twice`() = runTest {
		val activePlan = plan(1L)
		val replacementPlan = activePlan.copy(revision = 2L)
		var completions = 0
		val completionTokens = mutableListOf<SourceRegistrationRetirementToken>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, 1L, requiresAcceptance = true),
				registration(replacementPlan, 2L, requiresAcceptance = true, generation = 10L),
			),
			completeRetirement = { token ->
				completionTokens += token
				completions++
				if (completions == 1) throw IllegalStateException("commit acknowledgement lost")
				true
			},
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		val exactListener = fixture.listeners.single()
		fixture.runtime.close()

		assertTrue(fixture.runtime.start(replacementPlan, sink) is SourceStartResult.Started)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 2) { fixture.registrations.completeRetirement(any()) }
		assertSame(completionTokens.first(), completionTokens.last())
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactListener) }
		fixture.runtime.close()
	}

	@Test
	fun `retirement persistence failure fences refresh and replacement and preserves boundary`() = runTest {
		val activePlan = plan(1L)
		val attemptedBoundaries = mutableListOf<Long>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			beginRetirement = { _, _, _, boundary ->
				attemptedBoundaries += boundary
				throw IllegalStateException("retirement store unavailable")
			},
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		fixture.runtime.close()

		assertTrue(fixture.runtime.reconfigure(activePlan.copy(revision = 2L), sink) is SourceApplyResult.Failed)

		assertEquals(2, attemptedBoundaries.size)
		assertEquals(attemptedBoundaries.first(), attemptedBoundaries.last())
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}
		verify(exactly = 0) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		coVerify(exactly = 0) {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		}
	}

	@Test
	fun `hung retirement begin times out before listener removal and retries retained intent`() = runTest {
		val activePlan = plan(1L)
		val replacementPlan = activePlan.copy(revision = 2L)
		var beginCalls = 0
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, 1L, requiresAcceptance = true),
				registration(replacementPlan, 2L, requiresAcceptance = true, generation = 10L),
			),
			beginRetirement = { registration, reason, retiredAtMs, boundary ->
				if (beginCalls++ == 0) awaitCancellation()
				retirementToken(registration, reason, retiredAtMs, boundary)
			},
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		val exactListener = fixture.listener()
		val now = android.os.SystemClock.elapsedRealtimeNanos()

		val ack = fixture.runtime.quiesce(
			SessionCutoff("pressure-hung-begin", Long.MAX_VALUE, 1L, now + 10_000_000L),
		)

		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		verify(exactly = 0) { fixture.sensorManager.unregisterListener(exactListener) }
		assertTrue(fixture.runtime.start(replacementPlan, sink) is SourceStartResult.Started)
		coVerify(exactly = 2) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactListener) }
		fixture.runtime.close()
	}

	@Test
	fun `hung retirement completion retries exact token without removing listener twice`() = runTest {
		val activePlan = plan(1L)
		val replacementPlan = activePlan.copy(revision = 2L)
		var completionCalls = 0
		val completionTokens = mutableListOf<SourceRegistrationRetirementToken>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, 1L, requiresAcceptance = true),
				registration(replacementPlan, 2L, requiresAcceptance = true, generation = 10L),
			),
			completeRetirement = { token ->
				completionTokens += token
				if (completionCalls++ == 0) awaitCancellation()
				true
			},
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		val exactListener = fixture.listener()
		val now = android.os.SystemClock.elapsedRealtimeNanos()

		val ack = fixture.runtime.quiesce(
			SessionCutoff("pressure-hung-complete", Long.MAX_VALUE, 1L, now + 10_000_000L),
		)

		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		assertTrue(fixture.runtime.start(replacementPlan, sink) is SourceStartResult.Started)
		assertEquals(2, completionTokens.size)
		assertSame(completionTokens.first(), completionTokens.last())
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactListener) }
		fixture.runtime.close()
	}

	@Test
	fun `non cooperative sink times out and fences replacement until retained actor settles`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val replacementPlan = activePlan.copy(revision = 2L)
		val admissionEntered = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, 1L, requiresAcceptance = true),
				registration(replacementPlan, 2L, requiresAcceptance = true, generation = 10L),
			),
		)
		val blockingSink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure must use atomic admission")

			override suspend fun admit(
				candidate: SourceEvidenceCandidate<*>,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceAdmissionHandoff {
				withContext(NonCancellable) {
					admissionEntered.complete(Unit)
					releaseAdmission.await()
				}
				return SourceAdmissionHandoff.Durable(candidate.sourceSequence)
			}
		}
		val replacementSink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		assertTrue(fixture.runtime.start(activePlan, blockingSink) is SourceStartResult.Started)
		fixture.listener().onSensorChanged(
			pressureEvent(fixture.sensor, android.os.SystemClock.elapsedRealtimeNanos(), 1_000f),
		)
		val now = android.os.SystemClock.elapsedRealtimeNanos()
		val stopping = async {
			fixture.runtime.quiesce(
				SessionCutoff("pressure-blocked-sink", Long.MAX_VALUE, 1L, now + 10_000_000L),
			)
		}
		admissionEntered.await()

		val ack = stopping.await()

		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		assertFalse(ack.appDrainComplete)
		assertTrue(fixture.runtime.start(replacementPlan, replacementSink) is SourceStartResult.Failed)
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}

		releaseAdmission.complete(Unit)
		testScheduler.runCurrent()
		assertTrue(fixture.runtime.start(replacementPlan, replacementSink) is SourceStartResult.Started)
		fixture.runtime.close()
	}

	@Test
	fun `fatal and cancellation registration failures clean up durably then rethrow`() = runTest {
		val activePlan = plan(1L)
		for (failure in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
			val fixture = fixture(
				scope = this,
				registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
				registerFailure = failure,
			)
			if (failure is CancellationException) {
				assertFailsWith<CancellationException> {
					fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) }
				}
			} else {
				assertFailsWith<AssertionError> {
					fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) }
				}
			}
			coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
			verify(exactly = 1) { fixture.sensorManager.unregisterListener(fixture.listeners.single()) }
			coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		}
	}

	@Test
	fun `fatal and actor local cancellation durably contain the failed actor and replay terminal ack`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		for (failure in listOf(AssertionError("fatal actor"), CancellationException("actor-local"))) {
			val sourceJob = SupervisorJob()
			val uncaught = mutableListOf<Throwable>()
			val sourceScope = CoroutineScope(
				sourceJob + StandardTestDispatcher(testScheduler) +
					CoroutineExceptionHandler { _, thrown -> uncaught += thrown },
			)
			val activePlan = plan(1L, aggregationWindowMs = 1L)
			val activeRegistration = registration(activePlan, 1L, requiresAcceptance = true)
			val fixture = fixture(
				scope = sourceScope,
				registrationsToReturn = listOf(activeRegistration),
			)
			val sink = object : SourceEventSink {
				override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
					error("Pressure must use atomic admission")

				override suspend fun admit(
					candidate: SourceEvidenceCandidate<*>,
					checkpoint: SensorAdmissionCheckpoint,
				): SourceAdmissionHandoff = throw failure
			}
			assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
			val exactListener = fixture.listener()
			val now = android.os.SystemClock.elapsedRealtimeNanos()
			exactListener.onSensorChanged(pressureEvent(fixture.sensor, now - 2_000_000L, 1_000f))
			exactListener.onSensorChanged(pressureEvent(fixture.sensor, now, 1_001f))

			testScheduler.runCurrent()

			coVerify(exactly = 1) {
				fixture.registrations.beginRetirement(activeRegistration, any(), any(), any())
			}
			verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactListener) }
			coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
			val cutoff = SessionCutoff("pressure-actor-failure", Long.MAX_VALUE, 1L, now + 1_000_000_000L)
			val firstAck = fixture.runtime.quiesce(cutoff)
			ShadowSystemClock.advanceBy(Duration.ofMillis(1))
			exactListener.onSensorChanged(
				pressureEvent(fixture.sensor, android.os.SystemClock.elapsedRealtimeNanos(), 1_002f),
			)
			val replayedAck = fixture.runtime.quiesce(cutoff)
			assertSame(firstAck, replayedAck)
			assertEquals(2L, firstAck.callbackEntryBarrierSequence)
			assertEquals(2L, firstAck.failedAdmissionCount)
			assertFalse(firstAck.appDrainComplete)
			assertEquals(SourceStopStatus.TIMED_OUT, firstAck.status)
			if (failure is AssertionError) {
				assertEquals(1, uncaught.size)
				assertSame(failure, uncaught.single())
			} else assertTrue(uncaught.isEmpty())
			sourceScope.cancel()
		}
	}

	@Test
	fun `exact failure coverage neither double counts overlap nor omits sparse holes`() {
		val overlapping = PressureFailureRangeCoverage()
		assertEquals(listOf(4L..4L), overlapping.recordAndReturnUnaccounted(4L..4L))
		assertEquals(listOf(2L..3L, 5L..5L), overlapping.recordAndReturnUnaccounted(2L..5L))
		assertTrue(overlapping.recordAndReturnUnaccounted(2L..5L).isEmpty())

		val sparse = PressureFailureRangeCoverage()
		assertEquals(listOf(2L..2L), sparse.recordAndReturnUnaccounted(2L..2L))
		assertEquals(listOf(5L..5L), sparse.recordAndReturnUnaccounted(5L..5L))
		assertEquals(listOf(3L..4L), sparse.recordAndReturnUnaccounted(2L..5L))
		assertTrue(sparse.recordAndReturnUnaccounted(2L..5L).isEmpty())
	}

	@Test
	fun `capacity backpressure pause is nonterminal and never retires repository registration`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 1L)
		val releaseAdmission = CompletableDeferred<Unit>()
		val admitted = mutableListOf<SourceEvidenceCandidate<*>>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
		)
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure must use atomic admission")

			override suspend fun admit(
				candidate: SourceEvidenceCandidate<*>,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceAdmissionHandoff {
				releaseAdmission.await()
				admitted += candidate
				return SourceAdmissionHandoff.Durable(candidate.sourceSequence)
			}
		}
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		val exactListener = fixture.listener()
		repeat(64) { index ->
			exactListener.onSensorChanged(
				pressureEvent(fixture.sensor, (index + 1L) * 1_000_001L, 1_000f),
			)
		}

		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactListener) }
		coVerify(exactly = 0) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.registrations.completeRetirement(any()) }

		releaseAdmission.complete(Unit)
		testScheduler.runCurrent()
		assertEquals(2, fixture.listeners.size)
		val resumedListener = fixture.listener()
		exactListener.onSensorChanged(
			pressureEvent(fixture.sensor, 65L * 1_000_001L, 1_001f),
		)
		resumedListener.onSensorChanged(
			pressureEvent(fixture.sensor, 66L * 1_000_001L, 1_002f),
		)
		fixture.runtime.close()

		assertEquals(65, admitted.size)
	}

	@Test
	fun `failed resume cleanup retains exact provisional listener and blocks another candidate`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			registerResults = listOf(true, false),
			unregisterFailures = listOf(null, IllegalStateException("candidate removal uncertain"), null),
		)
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure must use atomic admission")

			override suspend fun admit(
				candidate: SourceEvidenceCandidate<*>,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceAdmissionHandoff = SourceAdmissionHandoff.Durable(candidate.sourceSequence)
		}
		assertTrue(
			fixture.runtime.start(activePlan, sink) is SourceStartResult.Started,
		)
		val initialListener = fixture.listener()
		repeat(64) { index ->
			initialListener.onSensorChanged(
				pressureEvent(fixture.sensor, (index + 1L) * 1_000_001L, 1_000f),
			)
		}
		testScheduler.runCurrent()

		assertEquals(2, fixture.listeners.size)
		val retainedCandidate = fixture.listeners.last()
		verify(exactly = 2) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(retainedCandidate) }
		coVerify(exactly = 0) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }

		fixture.runtime.close()

		verify(exactly = 2) { fixture.sensorManager.unregisterListener(retainedCandidate) }
	}

	@Test
	fun `stop during successful failed resume cleanup retires exact owner and permits replacement`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 1L)
		val replacementPlan = activePlan.copy(revision = 2L)
		val activeRegistration = registration(activePlan, 1L, requiresAcceptance = true)
		val replacementRegistration = registration(
			replacementPlan,
			2L,
			requiresAcceptance = true,
			generation = 10L,
		)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(activeRegistration, replacementRegistration),
			registerResults = listOf(true, false),
			unregisterFailures = listOf(null, null),
		)
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure must use atomic admission")

			override suspend fun admit(
				candidate: SourceEvidenceCandidate<*>,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceAdmissionHandoff = SourceAdmissionHandoff.Durable(candidate.sourceSequence)
		}
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		val initialListener = fixture.listener()
		repeat(64) { index ->
			initialListener.onSensorChanged(
				pressureEvent(fixture.sensor, (index + 1L) * 1_000_001L, 1_000f),
			)
		}
		testScheduler.runCurrent()

		assertEquals(2, fixture.listeners.size)
		val exactRemovedCandidate = fixture.listeners.last()
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactRemovedCandidate) }
		val now = android.os.SystemClock.elapsedRealtimeNanos()
		val ack = fixture.runtime.quiesce(
			SessionCutoff("pressure-failed-resume-stop", Long.MAX_VALUE, 1L, now + 1_000_000_000L),
		)

		assertEquals(activeRegistration.state.sourceInstanceId, ack.sourceInstanceId.value)
		assertEquals(activeRegistration.state.registrationGeneration, ack.registrationGeneration)
		assertEquals(65L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(RegistrationRemovalOutcome.REMOVED, ack.registrationRemovalOutcome)
		coVerify(exactly = 1) {
			fixture.registrations.beginRetirement(activeRegistration, any(), any(), any())
		}
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		verify(exactly = 2) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactRemovedCandidate) }

		assertTrue(fixture.runtime.start(replacementPlan, sink) is SourceStartResult.Started)
		verify(exactly = 3) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}
		fixture.runtime.close()
	}

	@Test
	fun `compatible authorization refresh keeps one SensorManager registration`() = runTest {
		val initialPlan = plan(revision = 1L, aggregationWindowMs = 5_000L)
		val refreshedPlan = plan(revision = 2L, aggregationWindowMs = 1_000L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, authorizationRevision = 1L, requiresAcceptance = true),
				registration(refreshedPlan, authorizationRevision = 2L),
			),
		)
		val initialSink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		val refreshedSink = SourceEventSink { SourceAdmissionHandoff.Durable(2L) }

		assertTrue(fixture.runtime.start(initialPlan, initialSink) is SourceStartResult.Started)
		assertTrue(fixture.runtime.reconfigure(refreshedPlan, refreshedSink) is SourceApplyResult.Applied)

		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, 50_000, 1_000_000)
		}
		verify(exactly = 0) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		coVerify(exactly = 1) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 1) {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		}

		fixture.runtime.close()
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
	}

	@Test
	fun `physical provider change performs normal stop and replacement start`() = runTest {
		val initialPlan = plan(revision = 1L, hardwareSamplePeriodMicros = 50_000)
		val replacementPlan = plan(revision = 2L, hardwareSamplePeriodMicros = 200_000)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, authorizationRevision = 1L, requiresAcceptance = true),
				registration(
					replacementPlan,
					authorizationRevision = 2L,
					requiresAcceptance = true,
					generation = 10L,
				),
			),
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(fixture.runtime.start(initialPlan, sink) is SourceStartResult.Started)
		assertTrue(fixture.runtime.reconfigure(replacementPlan, sink) is SourceApplyResult.Applied)

		verify(exactly = 2) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any(), 1_000_000)
		}
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }

		fixture.runtime.close()
		verify(exactly = 2) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
	}

	@Test
	fun `incompatible authorization check reserves only one pressure replacement`() = runTest {
		val initialPlan = plan(revision = 1L, aggregationWindowMs = 5_000L)
		val replacementPlan = plan(revision = 2L, aggregationWindowMs = 1_000L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, authorizationRevision = 1L, requiresAcceptance = true),
				registration(
					replacementPlan,
					authorizationRevision = 2L,
					requiresAcceptance = true,
					generation = 10L,
				),
			),
			compatibleRefresh = { null },
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(fixture.runtime.start(initialPlan, sink) is SourceStartResult.Started)
		assertTrue(fixture.runtime.reconfigure(replacementPlan, sink) is SourceApplyResult.Applied)

		coVerify(exactly = 1) {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		}
		// Initial acquisition plus the orderly post-stop replacement; no speculative reservation.
		coVerify(exactly = 2) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		verify(exactly = 2) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		fixture.runtime.close()
	}

	private data class Fixture(
		val runtime: PressureSourceRuntime,
		val registrations: SourceRegistrationRepository,
		val sensorManager: SensorManager,
		val sensor: Sensor,
		val listener: () -> SensorEventListener,
		val listeners: List<SensorEventListener>,
	)

	private fun fixture(
		scope: kotlinx.coroutines.CoroutineScope,
		registrationsToReturn: List<SourceRegistration>,
		registerFailure: Throwable? = null,
		registerResults: List<Boolean> = emptyList(),
		failRuntimeCheckpoint: Boolean = false,
		markAcceptanceFailure: Throwable? = null,
		unregisterFailures: List<Throwable?> = emptyList(),
		compatibleRefresh: (suspend (Long) -> SourceRegistration?)? = null,
		beginRetirement: suspend (SourceRegistration, String, Long, Long) -> SourceRegistrationRetirementToken =
			{ registration, reason, retiredAtMs, retiredElapsedNanos ->
				retirementToken(registration, reason, retiredAtMs, retiredElapsedNanos)
			},
		completeRetirement: suspend (SourceRegistrationRetirementToken) -> Boolean = { true },
		operationEvents: MutableList<String>? = null,
	): Fixture {
		val context = mockk<Context>()
		val sensorManager = mockk<SensorManager>()
		val sensor = mockk<Sensor>()
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val listenerSlot = slot<SensorEventListener>()
		val listeners = mutableListOf<SensorEventListener>()
		var registerIndex = 0
		every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
		every { sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) } returns sensor
		every { sensor.type } returns Sensor.TYPE_PRESSURE
		every { sensor.fifoMaxEventCount } returns 0
		every { sensor.minDelay } returns 50_000
		val registrationCall = every {
			sensorManager.registerListener(
				capture(listenerSlot),
				sensor,
				any<Int>(),
				any<Int>(),
			)
		}
		registrationCall answers {
			listeners += listenerSlot.captured
			operationEvents?.add("register")
			registerFailure?.let { throw it }
			registerResults.getOrNull(registerIndex++) ?: true
		}
		var unregisterIndex = 0
		every { sensorManager.unregisterListener(any<SensorEventListener>()) } answers {
			operationEvents?.add("unregister")
			unregisterFailures.getOrNull(unregisterIndex++)?.let { throw it }
		}
		var beginCallIndex = 0
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } coAnswers {
			val callIndex = beginCallIndex++
			registrationsToReturn[callIndex.coerceAtMost(registrationsToReturn.lastIndex)]
		}
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} coAnswers {
			if (compatibleRefresh != null) {
				compatibleRefresh.invoke(arg<Long>(5))
			} else {
				registrationsToReturn.getOrNull(1)
			}
		}
		coEvery { registrations.loadRuntimeState(any()) } returns null
		coEvery { registrations.beginRetirement(any(), any(), any(), any()) } coAnswers {
			operationEvents?.add("begin-retirement")
			beginRetirement(firstArg(), secondArg(), thirdArg(), arg(3))
		}
		coEvery { registrations.completeRetirement(any()) } coAnswers {
			operationEvents?.add("complete-retirement")
			completeRetirement(firstArg())
		}
		var allocatedSequence = 0L
		coEvery { registrations.allocateSequence(any(), any()) } answers { ++allocatedSequence }
		var acceptanceCalls = 0
		coEvery { registrations.markAccepted(any(), any(), any()) } coAnswers {
			acceptanceCalls++
			if (acceptanceCalls == 1) markAcceptanceFailure?.let { throw it }
			null
		}
		if (failRuntimeCheckpoint) {
			coEvery {
				registrations.saveRuntimeState(any(), any(), any(), any(), any(), any(), any())
			} throws IllegalStateException("checkpoint unavailable")
		}
		return Fixture(
			PressureSourceRuntime(context, scope, registrations),
			registrations,
			sensorManager,
			sensor,
			listener = { listenerSlot.captured },
			listeners = listeners,
		)
	}

	private fun plan(
		revision: Long,
		hardwareSamplePeriodMicros: Int = 50_000,
		aggregationWindowMs: Long = 5_000L,
	) = PressurePlan(
		revision = revision,
		enabled = true,
		hardwareSamplePeriodMicros = hardwareSamplePeriodMicros,
		maximumReportLatencyMicros = 1_000_000,
		aggregationWindowMs = aggregationWindowMs,
		movementGatedBurst = false,
	)

	private fun registration(
		plan: PressurePlan,
		authorizationRevision: Long,
		requiresAcceptance: Boolean = false,
		generation: Long = 9L,
		effectiveElapsedNanos: Long = authorizationRevision,
		purpose: String = SourceBrokerPurpose.SESSION_CAPTURE,
	): SourceRegistration {
		val capturedPurpose = purpose in setOf(
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceBrokerPurpose.CONTROL_CONTINUATION,
		)
		val demand = SourceDemandEntity(
			demandId = "pressure-demand-$authorizationRevision",
			consumerId = "session:pressure-test",
			sourceKind = SourceKind.PRESSURE.stableCode,
			purpose = purpose,
			logicalTrackingId = "pressure-test".takeIf { capturedPurpose },
			serviceRunId = "run-1".takeIf { capturedPurpose },
			manifestRevision = authorizationRevision.takeIf { capturedPurpose },
			lifecycleLeaseGeneration = 1L.takeIf { capturedPurpose },
			sourcePolicyRevision = authorizationRevision,
			consentEpoch = 1L,
			persistenceEligible = capturedPurpose,
			qosCode = 0,
			maximumAgeMs = 60_000L,
			desiredLatencyMs = 5_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = authorizationRevision,
			requestedAtMs = 1L,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
		)
		val authorization = requireNotNull(
			SourceBrokerAuthorization.rows(
				SourceKind.PRESSURE.stableCode,
				registrationGeneration = generation,
				authorizationRevision = authorizationRevision,
				demands = listOf(demand),
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = effectiveElapsedNanos,
				effectiveWallTimeMs = 1L,
			).toAuthorizationSnapshotOrNull(),
		)
		val ownerScope = "source-broker:${SourceKind.PRESSURE.stableCode}"
		return SourceRegistration(
			ownerScope = ownerScope,
			state = SourceRegistrationStateEntity(
				sourceKind = SourceKind.PRESSURE.stableCode,
				ownerScope = ownerScope,
				sourceInstanceId = "pressure-1",
				clockDomainId = "boot-1",
				registrationGeneration = generation,
				nextSequence = 0L,
				appliedRevision = plan.revision,
				collectedDataEpoch = 1L,
				updatedAtMs = 1L,
			),
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint(),
			authorization = authorization,
			requiresProviderAcceptance = requiresAcceptance,
		)
	}

	private fun retirementToken(
		registration: SourceRegistration,
		reason: String,
		retiredAtMs: Long,
		retiredElapsedRealtimeNanos: Long,
	) = SourceRegistrationRetirementToken(
		source = SourceKind.PRESSURE,
		sourceInstanceId = com.adsamcik.tracker.tracker.source.model.SourceInstanceId(
			registration.state.sourceInstanceId,
		),
		registrationGeneration = registration.state.registrationGeneration,
		processIncarnationId = "pressure-test-process",
		retiredAtMs = retiredAtMs,
		retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
		reason = reason,
	)

	private class RecordingPressureSink : SourceEventSink {
		val candidates = mutableListOf<SourceEvidenceCandidate<*>>()
		val windows: List<PressureWindowPayload>
			get() = candidates.map { candidate -> candidate.payload as PressureWindowPayload }
		private var nextOrdinal = 1L

		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
			error("Pressure must not fall back to non-atomic admission")

		override suspend fun admit(
			candidate: SourceEvidenceCandidate<*>,
			checkpoint: SensorAdmissionCheckpoint,
		): SourceAdmissionHandoff {
			candidates += candidate
			return SourceAdmissionHandoff.Durable(nextOrdinal++)
		}
	}

	private fun pressureEvent(sensor: Sensor, observedElapsedNanos: Long, pressure: Float): SensorEvent {
		val constructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType!!)
		constructor.isAccessible = true
		return constructor.newInstance(1).also { event ->
			event.sensor = sensor
			event.timestamp = observedElapsedNanos
			event.values[0] = pressure
		}
	}
}
