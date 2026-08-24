package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class LocationSourceRuntimeTest {
	@Test
	fun `callbacks before durable provider acceptance are not admitted`() = runTest {
		val fixture = locationRuntimeFixture(
			backgroundScope,
			callbacksDuringStart = 65,
			requiresProviderAcceptance = true,
		)

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		runCurrent()
		assertTrue(fixture.sink.deliveries.isEmpty())

		fixture.providerCallback()(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertEquals(1, fixture.sink.deliveries.size)
		assertEquals(0L, ack.failedAdmissionCount)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
	}

	@Test
	fun `partial provider start cleans its exact retained registration before returning failure`() = runTest {
		val fixture = failingStartFixture(backgroundScope, startFailure = null)

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Failed)

		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		coVerify(exactly = 0) { fixture.registrations.markFailed(any(), any(), any(), any()) }
	}

	@Test
	fun `provider start exception cleans any partial registration before returning failure`() = runTest {
		val fixture = failingStartFixture(backgroundScope, startFailure = IllegalStateException("provider"))

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Failed)

		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		coVerify(exactly = 0) { fixture.registrations.markFailed(any(), any(), any(), any()) }
	}

	@Test
	fun `provider start fatal error retires exact handle and propagates`() = runTest {
		val fixture = failingStartFixture(backgroundScope, startFailure = AssertionError("fatal provider"))

		assertFailsWith<AssertionError> {
			fixture.runtime.start(fixture.plan, fixture.sink)
		}

		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `provider acceptance fatal error retires exact handle and propagates`() = runTest {
		val fixture = locationRuntimeFixture(
			backgroundScope,
			requiresProviderAcceptance = true,
		)
		coEvery { fixture.registrations.markAccepted(any(), any(), any()) } throws
			AssertionError("fatal acceptance")

		assertFailsWith<AssertionError> {
			fixture.runtime.start(fixture.plan, fixture.sink)
		}

		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `stale provider acceptance exception retires exact handle and fails closed`() = runTest {
		val fixture = locationRuntimeFixture(
			backgroundScope,
			requiresProviderAcceptance = true,
		)
		coEvery { fixture.registrations.markAccepted(any(), any(), any()) } throws
			IllegalStateException("stale acceptance")

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Failed)

		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `retirement is durable before provider removal and completes after removal`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		val events = mutableListOf<String>()
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			events += "RETIRING"
			retirementToken(
				firstArg<SourceRegistration>(),
				secondArg<String>(),
				thirdArg<Long>(),
				arg<Long>(3),
			)
		}
		coEvery { fixture.fusedBackend.stop() } answers {
			events += "REMOVE"
			RegistrationRemovalOutcome.REMOVED
		}
		coEvery { fixture.registrations.completeRetirement(any()) } answers {
			events += "COMPLETE"
			true
		}

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(listOf("RETIRING", "REMOVE", "COMPLETE"), events)
	}

	@Test
	fun `failed removal is retried before cross backend replacement starts`() = runTest {
		val initialPlan = locationPlan(revision = 1L)
		val replacementPlan = initialPlan.copy(revision = 2L, backend = LocationBackend.FRAMEWORK)
		val initialRegistration = registration(initialPlan, authorizationRevision = 1L)
		val replacementRegistration = registration(
			replacementPlan,
			authorizationRevision = 2L,
			generation = 10L,
			requiresProviderAcceptance = true,
		)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		stubRetirementRepository(registrations)
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returnsMany
			listOf(initialRegistration, replacementRegistration)
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns null
		coEvery { registrations.markAccepted(any(), any(), any()) } returns null
		coEvery { fusedBackend.start(any(), any()) } returns LocationBackendStartOutcome.STARTED
		coEvery { frameworkBackend.start(any(), any()) } returns LocationBackendStartOutcome.STARTED
		coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { fusedBackend.stop() } returnsMany listOf(
			RegistrationRemovalOutcome.FAILED,
			RegistrationRemovalOutcome.REMOVED,
		)
		val runtime = locationRuntime(
			backgroundScope,
			registrations,
			fusedBackend,
			frameworkBackend,
		)
		val sink = RecordingLocationSink()

		assertTrue(runtime.start(initialPlan, sink) is SourceStartResult.Started)
		assertTrue(runtime.reconfigure(replacementPlan, sink) is SourceApplyResult.Failed)
		coVerify(exactly = 0) { frameworkBackend.start(any(), any()) }

		assertTrue(runtime.reconfigure(replacementPlan, sink) is SourceApplyResult.Applied)

		coVerify(exactly = 2) { fusedBackend.stop() }
		coVerify(exactly = 1) { registrations.beginRetirement(initialRegistration, any(), any(), any()) }
		coVerify(exactly = 1) { registrations.completeRetirement(any()) }
		coVerify(exactly = 1) { frameworkBackend.start(any(), any()) }
	}

	@Test
	fun `one pending token with cross backend handles fails closed`() = runTest {
		val plan = locationPlan(revision = 1L)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		val pending = retirementToken(registration(plan, authorizationRevision = 1L))
		coEvery { registrations.pendingRetirements(SourceKind.LOCATION) } returns listOf(pending)
		every { fusedBackend.hasRetainedRegistration } returns true
		every { frameworkBackend.hasRetainedRegistration } returns true
		val runtime = locationRuntime(
			backgroundScope,
			registrations,
			fusedBackend,
			frameworkBackend,
		)

		assertTrue(runtime.start(plan, RecordingLocationSink()) is SourceStartResult.Failed)

		coVerify(exactly = 0) { registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 0) { fusedBackend.stop() }
		coVerify(exactly = 0) { frameworkBackend.stop() }
		coVerify(exactly = 0) { registrations.completeRetirement(any()) }
	}

	@Test
	fun `compatible revision refreshes callback authorization without provider restart and revoke stays fenced`() =
		runTest {
			val initialPlan = locationPlan(revision = 1L)
			val refreshedPlan = initialPlan.copy(revision = 2L)
			val initialRegistration = registration(initialPlan, authorizationRevision = 1L)
			val refreshedRegistration = registration(refreshedPlan, authorizationRevision = 2L)
			val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
			val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
			val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
			var deviceState = locationState()
			val stateProvider = object : LocationDeviceStateProvider {
				override fun snapshot(): LocationDeviceState = deviceState
			}
			var callback: ((List<Location>) -> Unit)? = null
			coEvery { registrations.begin(any(), any(), any(), any(), any()) } returns initialRegistration
			coEvery {
				registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
			} returns refreshedRegistration
			coEvery { registrations.markAccepted(any(), any(), any()) } returns null
			coEvery { fusedBackend.start(any(), any()) } answers {
				callback = secondArg()
				LocationBackendStartOutcome.STARTED
			}
			coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
			coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.REMOVED
			stubRetirementRepository(registrations)
			val runtime = LocationSourceRuntime(
				context = mockk<Context>(relaxed = true),
				applicationScope = backgroundScope,
				registrations = registrations,
				fusedBackend = fusedBackend,
				frameworkBackend = frameworkBackend,
				prerequisiteEvaluator = LocationPrerequisiteEvaluator(),
				deviceStateProvider = stateProvider,
			)
			val initialSink = RecordingLocationSink()
			val refreshedSink = RecordingLocationSink()

			assertTrue(runtime.start(initialPlan, initialSink) is SourceStartResult.Started)
			val providerCallback = requireNotNull(callback)
			providerCallback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))

			assertTrue(runtime.reconfigure(refreshedPlan, refreshedSink) is SourceApplyResult.Applied)
			providerCallback(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
			runCurrent()

			coVerify(exactly = 1) { fusedBackend.start(any(), any()) }
			coVerify(exactly = 0) { fusedBackend.stop() }
			coVerify(exactly = 1) { registrations.begin(any(), any(), any(), any(), any()) }
			coVerify(exactly = 1) {
				registrations.refreshActiveAuthorization(any(), initialRegistration, any(), any(), any(), any())
			}
			assertEquals(1L, initialSink.singleEvidence().authorizationRevision)
			assertEquals(1L, initialSink.singleEvidence().configRevision)
			assertEquals(2L, refreshedSink.singleEvidence().authorizationRevision)
			assertEquals(2L, refreshedSink.singleEvidence().configRevision)

			deviceState = locationState(coarsePermission = false, finePermission = false)
			providerCallback(listOf(location(52.0, SystemClock.elapsedRealtimeNanos())))
			runCurrent()

			assertEquals(1, refreshedSink.deliveries.size)

			deviceState = locationState()
			providerCallback(
				listOf(
					location(54.0, Long.MAX_VALUE),
					location(53.0, SystemClock.elapsedRealtimeNanos()),
				),
			)
			runCurrent()

			assertEquals(2, refreshedSink.deliveries.size)
			assertEquals(
				53.0,
				(refreshedSink.deliveries.last().units.single().evidence.payload as
					com.adsamcik.tracker.tracker.source.model.LocationFixPayload).latitudeDegrees,
				0.0,
			)
			val stopNow = SystemClock.elapsedRealtimeNanos()
			val ack = runtime.quiesce(
				SessionCutoff(
					logicalTrackingId = "location-test",
					elapsedRealtimeNanos = Long.MAX_VALUE,
					wallTimeMs = System.currentTimeMillis(),
					deadlineElapsedRealtimeNanos = stopNow + 1_000_000_000L,
				),
			)

			assertTrue(ack.appDrainComplete)
			assertEquals(1L, ack.failedAdmissionCount)
			assertEquals(3L, ack.unresolvedSequenceStart)
			assertEquals(3L, ack.unresolvedSequenceEndInclusive)
			assertEquals(3L, ack.lastDurablyAdmittedSequence)
			coVerify(exactly = 1) { fusedBackend.stop() }
		}

	@Test
	fun `compatible refresh fences callbacks until durable context swap`() = runTest {
		val initialPlan = locationPlan(revision = 1L)
		val refreshedPlan = initialPlan.copy(revision = 2L)
		val initialRegistration = registration(initialPlan, authorizationRevision = 1L)
		val refreshedRegistration = registration(refreshedPlan, authorizationRevision = 2L)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		val refreshCommitted = CompletableDeferred<Unit>()
		val releaseRefresh = CompletableDeferred<Unit>()
		var callback: ((List<Location>) -> Unit)? = null
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returns initialRegistration
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} coAnswers {
			refreshCommitted.complete(Unit)
			releaseRefresh.await()
			refreshedRegistration
		}
		coEvery { registrations.markAccepted(any(), any(), any()) } returns null
		coEvery { fusedBackend.start(any(), any()) } answers {
			callback = secondArg()
			LocationBackendStartOutcome.STARTED
		}
		coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.REMOVED
		stubRetirementRepository(registrations)
		val runtime = locationRuntime(backgroundScope, registrations, fusedBackend, frameworkBackend)
		val initialSink = RecordingLocationSink()
		val refreshedSink = RecordingLocationSink()

		assertTrue(runtime.start(initialPlan, initialSink) is SourceStartResult.Started)
		val providerCallback = requireNotNull(callback)
		providerCallback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()

		val refresh = async { runtime.reconfigure(refreshedPlan, refreshedSink) }
		refreshCommitted.await()
		providerCallback(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		assertEquals(1, initialSink.deliveries.size)
		assertTrue(refreshedSink.deliveries.isEmpty())

		releaseRefresh.complete(Unit)
		assertTrue(refresh.await() is SourceApplyResult.Applied)
		providerCallback(listOf(location(52.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()

		assertEquals(1L, initialSink.singleEvidence().configRevision)
		assertEquals(2L, refreshedSink.singleEvidence().authorizationRevision)
		assertEquals(2L, refreshedSink.singleEvidence().configRevision)
		coVerify(exactly = 1) { fusedBackend.start(any(), any()) }
		coVerify(exactly = 0) { fusedBackend.stop() }
		runtime.close()
	}

	@Test
	fun `incompatible authorization check reserves only the orderly replacement generation`() = runTest {
		val initialPlan = locationPlan(revision = 1L)
		val replacementPlan = initialPlan.copy(revision = 2L)
		val initialRegistration = registration(initialPlan, authorizationRevision = 1L)
		val replacementRegistration = registration(
			replacementPlan,
			authorizationRevision = 2L,
			generation = 10L,
			requiresProviderAcceptance = true,
		)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returnsMany
			listOf(initialRegistration, replacementRegistration)
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns null
		coEvery { registrations.markAccepted(any(), any(), any()) } returns null
		coEvery { fusedBackend.start(any(), any()) } returns LocationBackendStartOutcome.STARTED
		coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.REMOVED
		stubRetirementRepository(registrations)
		val runtime = LocationSourceRuntime(
			context = mockk<Context>(relaxed = true),
			applicationScope = backgroundScope,
			registrations = registrations,
			fusedBackend = fusedBackend,
			frameworkBackend = frameworkBackend,
			prerequisiteEvaluator = LocationPrerequisiteEvaluator(),
			deviceStateProvider = object : LocationDeviceStateProvider {
				override fun snapshot(): LocationDeviceState = locationState()
			},
		)
		val sink = RecordingLocationSink()

		assertTrue(runtime.start(initialPlan, sink) is SourceStartResult.Started)
		assertTrue(runtime.reconfigure(replacementPlan, sink) is SourceApplyResult.Applied)

		coVerify(exactly = 1) {
			registrations.refreshActiveAuthorization(any(), initialRegistration, any(), any(), any(), any())
		}
		// Initial acquisition plus one post-removal replacement; no speculative N+1 reservation.
		coVerify(exactly = 2) { registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 2) { fusedBackend.start(any(), any()) }
		coVerify(exactly = 1) { fusedBackend.stop() }
		runtime.close()
	}

	@Test
	fun `cancelled drain settles completed retirement before allowing replacement`() = runTest {
		val admissionEntered = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val sink = RecordingLocationSink { _, count ->
			admissionEntered.complete(Unit)
			releaseAdmission.await()
			SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()))
		}
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		admissionEntered.await()
		val stop = async { fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE)) }
		runCurrent()
		stop.cancel(CancellationException("cancel drain"))
		releaseAdmission.complete(Unit)
		advanceUntilIdle()

		assertFailsWith<CancellationException> { stop.await() }
		assertTrue(
			fixture.runtime.reconfigure(fixture.plan.copy(revision = 2L), sink) is SourceApplyResult.Applied,
		)
		coVerify(exactly = 1) {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		}
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		coVerify(exactly = 2) { fixture.fusedBackend.start(any(), any()) }
		fixture.runtime.close()
	}

	@Test
	fun `fatal callback fences admission cleans provider and reports queued tail through replayed ack`() = runTest {
		val fatal = AssertionError("fatal location admission")
		val observedFailure = CompletableDeferred<Throwable>()
		val providerStopEntered = CompletableDeferred<Unit>()
		val releaseProviderStop = CompletableDeferred<Unit>()
		val sourceScope = CoroutineScope(
			SupervisorJob() + StandardTestDispatcher(testScheduler) +
				CoroutineExceptionHandler { _, failure -> observedFailure.complete(failure) },
		)
		val sink = RecordingLocationSink { _, _ -> throw fatal }
		val fixture = locationRuntimeFixture(sourceScope, sink = sink)
		coEvery { fixture.fusedBackend.stop() } coAnswers {
			providerStopEntered.complete(Unit)
			releaseProviderStop.await()
			RegistrationRemovalOutcome.REMOVED
		}
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		val providerCallback = fixture.providerCallback()
		providerCallback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		providerCallback(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))

		runCurrent()
		assertSame(fatal, observedFailure.await())
		providerStopEntered.await()
		// Cleanup is deliberately held in provider removal. Admission must already be fenced.
		providerCallback(listOf(location(52.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		assertEquals(1, sink.deliveries.size)
		releaseProviderStop.complete(Unit)
		advanceUntilIdle()
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertTrue(!ack.appDrainComplete)
		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		assertEquals(2L, ack.callbackEntryBarrierSequence)
		assertEquals(2L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(2L, ack.unresolvedSequenceEndInclusive)
		assertEquals(1, sink.deliveries.size)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		sourceScope.cancel()
	}

	@Test
	fun `actor local cancellation fences callbacks retires once and replays terminal ack`() = runTest {
		val providerStopEntered = CompletableDeferred<Unit>()
		val releaseProviderStop = CompletableDeferred<Unit>()
		val sourceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
		val sink = RecordingLocationSink { _, _ ->
			throw CancellationException("local location actor cancellation")
		}
		val fixture = locationRuntimeFixture(sourceScope, sink = sink)
		coEvery { fixture.fusedBackend.stop() } coAnswers {
			providerStopEntered.complete(Unit)
			releaseProviderStop.await()
			RegistrationRemovalOutcome.REMOVED
		}
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		val providerCallback = fixture.providerCallback()
		providerCallback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))

		runCurrent()
		providerStopEntered.await()
		providerCallback(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		assertEquals(1, sink.deliveries.size)
		releaseProviderStop.complete(Unit)
		advanceUntilIdle()
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))
		val replayedAck = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertEquals(ack, replayedAck)
		assertTrue(ack.appDrainComplete)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		sourceScope.cancel()
	}

	@Test
	fun `completed retirement blocks replacement until non cooperative actor settles`() = runTest {
		val admissionEntered = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val sourceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
		val sink = RecordingLocationSink { _, count ->
			admissionEntered.complete(Unit)
			withContext(NonCancellable) { releaseAdmission.await() }
			SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()))
		}
		val fixture = locationRuntimeFixture(sourceScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		admissionEntered.await()

		val stop = async { fixture.runtime.quiesce(immediateSessionCutoff()) }
		advanceUntilIdle()

		assertTrue(stop.isCompleted)
		val ack = stop.await()
		assertTrue(!ack.appDrainComplete)
		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		val replacementPlan = fixture.plan.copy(revision = 2L)
		val replacementSink = RecordingLocationSink()
		assertTrue(fixture.runtime.reconfigure(replacementPlan, replacementSink) is SourceApplyResult.Failed)
		coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }

		releaseAdmission.complete(Unit)
		advanceUntilIdle()
		assertTrue(fixture.runtime.reconfigure(replacementPlan, replacementSink) is SourceApplyResult.Applied)
		coVerify(exactly = 2) { fixture.fusedBackend.start(any(), any()) }
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		fixture.runtime.close()
		sourceScope.cancel()
	}

	@Test
	fun `pending retirement can complete but replacement still waits for retained actor`() = runTest {
		val admissionEntered = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val sourceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
		val sink = RecordingLocationSink { _, count ->
			admissionEntered.complete(Unit)
			withContext(NonCancellable) { releaseAdmission.await() }
			SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()))
		}
		val fixture = locationRuntimeFixture(sourceScope, sink = sink)
		lateinit var exactRetirementToken: SourceRegistrationRetirementToken
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			retirementToken(
				firstArg<SourceRegistration>(),
				secondArg<String>(),
				thirdArg<Long>(),
				arg<Long>(3),
			).also { exactRetirementToken = it }
		}
		coEvery { fixture.registrations.completeRetirement(any()) } answers {
			assertSame(exactRetirementToken, firstArg())
			true
		}
		coEvery { fixture.fusedBackend.stop() } returnsMany listOf(
			RegistrationRemovalOutcome.FAILED,
			RegistrationRemovalOutcome.REMOVED,
		)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		admissionEntered.await()

		val stop = async { fixture.runtime.quiesce(immediateSessionCutoff()) }
		advanceUntilIdle()
		val ack = stop.await()
		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		assertEquals(RegistrationRemovalOutcome.FAILED, ack.registrationRemovalOutcome)

		val replacementPlan = fixture.plan.copy(revision = 2L)
		val replacementSink = RecordingLocationSink()
		assertTrue(fixture.runtime.reconfigure(replacementPlan, replacementSink) is SourceApplyResult.Failed)
		val replayedAck = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))
		assertEquals(SourceStopStatus.TIMED_OUT, replayedAck.status)
		assertEquals(RegistrationRemovalOutcome.REMOVED, replayedAck.registrationRemovalOutcome)
		assertEquals(ack.callbackEntryBarrierSequence, replayedAck.callbackEntryBarrierSequence)
		// Retirement is now complete, but the exact old actor is still fenced and blocks replacement.
		assertTrue(fixture.runtime.reconfigure(replacementPlan, replacementSink) is SourceApplyResult.Failed)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 2) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(exactRetirementToken) }
		coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }

		releaseAdmission.complete(Unit)
		advanceUntilIdle()
		assertTrue(fixture.runtime.reconfigure(replacementPlan, replacementSink) is SourceApplyResult.Applied)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 2) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(exactRetirementToken) }
		coVerify(exactly = 2) { fixture.fusedBackend.start(any(), any()) }
		fixture.runtime.close()
		sourceScope.cancel()
	}

	@Test
	fun `drain cancellation records one failure for one callback`() = runTest {
		val admissionEntered = CompletableDeferred<Unit>()
		val neverReleased = CompletableDeferred<Unit>()
		val sink = RecordingLocationSink { _, count ->
			admissionEntered.complete(Unit)
			neverReleased.await()
			SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()))
		}
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		admissionEntered.await()

		val stop = async { fixture.runtime.quiesce(immediateSessionCutoff()) }
		advanceUntilIdle()
		val ack = stop.await()

		assertTrue(!ack.appDrainComplete)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
	}

	@Test
	fun `accounted overflow tail does not falsely fail a completed drain`() = runTest {
		val firstAdmissionEntered = CompletableDeferred<Unit>()
		val releaseFirstAdmission = CompletableDeferred<Unit>()
		val sink = RecordingLocationSink { _, count ->
			if (count == 1) {
				firstAdmissionEntered.complete(Unit)
				releaseFirstAdmission.await()
			}
			SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()))
		}
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		val callback = fixture.providerCallback()
		callback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		firstAdmissionEntered.await()
		repeat(65) { index ->
			callback(
				listOf(location(50.001 + index / 10_000.0, SystemClock.elapsedRealtimeNanos())),
			)
		}

		releaseFirstAdmission.complete(Unit)
		advanceUntilIdle()
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertTrue(ack.appDrainComplete)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(66L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(66L, ack.unresolvedSequenceStart)
		assertEquals(66L, ack.unresolvedSequenceEndInclusive)
		assertEquals(65, sink.deliveries.size)
	}

	@Test
	fun `mixed valid and poison callback admits valid subset and reports one unresolved sequence`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		fixture.providerCallback()(
			listOf(
				location(50.0, SystemClock.elapsedRealtimeNanos()),
				location(51.0, Long.MAX_VALUE),
			),
		)
		runCurrent()

		assertEquals(1, fixture.sink.deliveries.size)
		val admitted = fixture.sink.deliveries.single().units.single().evidence.payload as
			LocationFixPayload
		assertEquals(50.0, admitted.latitudeDegrees, 0.0)

		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertTrue(ack.appDrainComplete)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		assertEquals(1L, ack.lastDurablyAdmittedSequence)
		assertEquals(1L, ack.lastAdmissionOrdinal)
	}

	@Test
	fun `all poison callback admits nothing and reports one unresolved sequence`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		fixture.providerCallback()(listOf(location(50.0, Long.MAX_VALUE)))
		runCurrent()

		assertTrue(fixture.sink.deliveries.isEmpty())

		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertTrue(ack.appDrainComplete)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		assertNull(ack.lastDurablyAdmittedSequence)
		assertNull(ack.lastAdmissionOrdinal)
	}

	@Test
	fun `flush callback wholly after cutoff admits nothing without a false gap`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		runCurrent()
		val observedElapsedNanos = SystemClock.elapsedRealtimeNanos()
		require(observedElapsedNanos > 0L)
		val cutoffElapsedNanos = observedElapsedNanos - 1L
		coEvery { fixture.fusedBackend.flush() } answers {
			fixture.providerCallback()(listOf(location(50.0, observedElapsedNanos)))
			ProviderFlushOutcome.COMPLETE
		}

		val ack = fixture.runtime.quiesce(sessionCutoff(cutoffElapsedNanos))

		assertTrue(ack.appDrainComplete)
		assertTrue(fixture.sink.deliveries.isEmpty())
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(0L, ack.failedAdmissionCount)
		assertNull(ack.unresolvedSequenceStart)
		assertNull(ack.unresolvedSequenceEndInclusive)
		assertNull(ack.lastDurablyAdmittedSequence)
		assertNull(ack.lastAdmissionOrdinal)
	}

	@Test
	fun `retry rechecks a cutoff installed after the first admission attempt`() = runTest {
		val sink = RecordingLocationSink { _, attempt ->
			if (attempt == 1) {
				SourceDeliveryAdmissionHandoff.RetryableFailure(
					SourceAdmissionFailureCode.STORAGE_UNAVAILABLE,
				)
			} else {
				SourceDeliveryAdmissionHandoff.Durable(listOf(attempt.toLong()))
			}
		}
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		val observedElapsedNanos = SystemClock.elapsedRealtimeNanos()
		require(observedElapsedNanos > 0L)
		fixture.providerCallback()(listOf(location(50.0, observedElapsedNanos)))
		runCurrent()
		assertEquals(1, sink.deliveries.size)

		val stop = async {
			fixture.runtime.quiesce(sessionCutoff(observedElapsedNanos - 1L))
		}
		runCurrent()
		advanceUntilIdle()
		val ack = stop.await()

		assertEquals(1, sink.deliveries.size)
		assertNull(ack.lastDurablyAdmittedSequence)
		assertNull(ack.lastAdmissionOrdinal)
	}

	private class RecordingLocationSink(
		private val response: suspend (SourceDeliveryCandidate, Int) -> SourceDeliveryAdmissionHandoff =
			{ _, count -> SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong())) },
	) : SourceEventSink {
		val deliveries = mutableListOf<SourceDeliveryCandidate>()

		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
			SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.INVALID_EVIDENCE)

		override suspend fun admit(delivery: SourceDeliveryCandidate): SourceDeliveryAdmissionHandoff {
			deliveries += delivery
			return response(delivery, deliveries.size)
		}

		fun singleEvidence(): SourceEvidenceCandidate<*> = deliveries.single().units.single().evidence
	}

	private data class LocationRuntimeFixture(
		val runtime: LocationSourceRuntime,
		val plan: LocationPlan,
		val sink: RecordingLocationSink,
		val registrations: SourceRegistrationRepository,
		val fusedBackend: FusedLocationSourceBackend,
		val providerCallback: () -> ((List<Location>) -> Unit),
	)

	private data class FailingStartFixture(
		val runtime: LocationSourceRuntime,
		val plan: LocationPlan,
		val sink: RecordingLocationSink,
		val registrations: SourceRegistrationRepository,
		val fusedBackend: FusedLocationSourceBackend,
	)

	private fun failingStartFixture(
		applicationScope: CoroutineScope,
		startFailure: Throwable?,
	): FailingStartFixture {
		val plan = locationPlan(revision = 1L)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returns registration(
			plan,
			authorizationRevision = 1L,
		)
		stubRetirementRepository(registrations)
		if (startFailure == null) {
			coEvery { fusedBackend.start(any(), any()) } returns LocationBackendStartOutcome.CLEANUP_REQUIRED
		} else {
			coEvery { fusedBackend.start(any(), any()) } throws startFailure
		}
		coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.NOT_REGISTERED
		val sink = RecordingLocationSink()
		return FailingStartFixture(
			runtime = LocationSourceRuntime(
				context = mockk<Context>(relaxed = true),
				applicationScope = applicationScope,
				registrations = registrations,
				fusedBackend = fusedBackend,
				frameworkBackend = frameworkBackend,
				prerequisiteEvaluator = LocationPrerequisiteEvaluator(),
				deviceStateProvider = object : LocationDeviceStateProvider {
					override fun snapshot(): LocationDeviceState = locationState()
				},
			),
			plan = plan,
			sink = sink,
			registrations = registrations,
			fusedBackend = fusedBackend,
		)
	}

	private fun locationRuntimeFixture(
		applicationScope: CoroutineScope,
		callbacksDuringStart: Int = 0,
		sink: RecordingLocationSink = RecordingLocationSink(),
		requiresProviderAcceptance: Boolean = false,
	): LocationRuntimeFixture {
		val plan = locationPlan(revision = 1L)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		var callback: ((List<Location>) -> Unit)? = null
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returns registration(
			plan,
			authorizationRevision = 1L,
			requiresProviderAcceptance = requiresProviderAcceptance,
		)
		stubRetirementRepository(registrations)
		coEvery { registrations.markAccepted(any(), any(), any()) } returns null
		coEvery { fusedBackend.start(any(), any()) } answers {
			callback = secondArg()
			repeat(callbacksDuringStart) { index ->
				requireNotNull(callback)(
					listOf(location(50.0 + index / 1_000.0, SystemClock.elapsedRealtimeNanos())),
				)
			}
			LocationBackendStartOutcome.STARTED
		}
		coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.REMOVED
		val runtime = LocationSourceRuntime(
			context = mockk<Context>(relaxed = true),
			applicationScope = applicationScope,
			registrations = registrations,
			fusedBackend = fusedBackend,
			frameworkBackend = frameworkBackend,
			prerequisiteEvaluator = LocationPrerequisiteEvaluator(),
			deviceStateProvider = object : LocationDeviceStateProvider {
				override fun snapshot(): LocationDeviceState = locationState()
			},
		)
		return LocationRuntimeFixture(
			runtime = runtime,
			plan = plan,
			sink = sink,
			registrations = registrations,
			fusedBackend = fusedBackend,
			providerCallback = { requireNotNull(callback) },
		)
	}

	private fun stubRetirementRepository(registrations: SourceRegistrationRepository) {
		coEvery { registrations.pendingRetirements(SourceKind.LOCATION) } returns emptyList()
		coEvery {
			registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			retirementToken(
				firstArg<SourceRegistration>(),
				secondArg<String>(),
				thirdArg<Long>(),
				arg<Long>(3),
			)
		}
		coEvery { registrations.completeRetirement(any()) } returns true
	}

	private fun retirementToken(
		registration: SourceRegistration,
		reason: String = "TEST_RETIREMENT",
		retiredAtMs: Long = 1L,
		retiredElapsedRealtimeNanos: Long = 1L,
	) = SourceRegistrationRetirementToken(
		source = SourceKind.LOCATION,
		sourceInstanceId = com.adsamcik.tracker.tracker.source.model.SourceInstanceId(
			registration.state.sourceInstanceId,
		),
		registrationGeneration = registration.state.registrationGeneration,
		processIncarnationId = "process-1",
		retiredAtMs = retiredAtMs,
		retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
		reason = reason,
	)

	private fun locationRuntime(
		applicationScope: CoroutineScope,
		registrations: SourceRegistrationRepository,
		fusedBackend: FusedLocationSourceBackend,
		frameworkBackend: FrameworkLocationSourceBackend,
	): LocationSourceRuntime = LocationSourceRuntime(
		context = mockk<Context>(relaxed = true),
		applicationScope = applicationScope,
		registrations = registrations,
		fusedBackend = fusedBackend,
		frameworkBackend = frameworkBackend,
		prerequisiteEvaluator = LocationPrerequisiteEvaluator(),
		deviceStateProvider = object : LocationDeviceStateProvider {
			override fun snapshot(): LocationDeviceState = locationState()
		},
	)

	private fun sessionCutoff(elapsedRealtimeNanos: Long): SessionCutoff = SessionCutoff(
		logicalTrackingId = "location-test",
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		wallTimeMs = System.currentTimeMillis(),
		deadlineElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() + 1_000_000_000L,
	)

	private fun immediateSessionCutoff(): SessionCutoff = SessionCutoff(
		logicalTrackingId = "location-test",
		elapsedRealtimeNanos = Long.MAX_VALUE,
		wallTimeMs = System.currentTimeMillis(),
		deadlineElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
	)

	private fun location(latitude: Double, observedElapsedNanos: Long) = Location("gps").apply {
		elapsedRealtimeNanos = observedElapsedNanos
		time = System.currentTimeMillis()
		this.latitude = latitude
		longitude = 14.0
		accuracy = 5f
	}

	private fun locationPlan(revision: Long) = LocationPlan(
		revision = revision,
		backend = LocationBackend.FUSED,
		mode = LocationMode.BALANCED,
		requestedIntervalMs = 10_000L,
		minimumUpdateIntervalMs = 5_000L,
		minimumDisplacementMeters = 0f,
		maximumBatchDelayMs = 30_000L,
		preciseLocationAvailable = true,
	)

	private fun locationState(
		coarsePermission: Boolean = true,
		finePermission: Boolean = true,
	) = LocationDeviceState(
		apiLevel = 34,
		locationFeatureAvailable = true,
		locationServicesEnabled = true,
		coarsePermission = coarsePermission,
		finePermission = finePermission,
		backgroundLocationPermission = true,
		fusedProviderAvailable = true,
		foregroundServiceLocationCapability = true,
		backgroundForegroundServiceStartLegal = true,
	)

	private fun registration(
		plan: LocationPlan,
		authorizationRevision: Long,
		generation: Long = 9L,
		requiresProviderAcceptance: Boolean = false,
	): SourceRegistration {
		val demand = SourceDemandEntity(
			demandId = "location-demand-$authorizationRevision",
			consumerId = "session:location-test",
			sourceKind = SourceKind.LOCATION.stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = "location-test",
			serviceRunId = "run-1",
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			sourcePolicyRevision = authorizationRevision,
			consentEpoch = 1L,
			persistenceEligible = true,
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
				sourceKind = SourceKind.LOCATION.stableCode,
				registrationGeneration = generation,
				authorizationRevision = authorizationRevision,
				demands = listOf(demand),
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = authorizationRevision,
				effectiveWallTimeMs = 1L,
			).toAuthorizationSnapshotOrNull(),
		)
		return SourceRegistration(
			ownerScope = "source-broker:${SourceKind.LOCATION.stableCode}",
			state = SourceRegistrationStateEntity(
				sourceKind = SourceKind.LOCATION.stableCode,
				ownerScope = "source-broker:${SourceKind.LOCATION.stableCode}",
				sourceInstanceId = "location-1",
				clockDomainId = "boot-1",
				registrationGeneration = generation,
				nextSequence = 0L,
				appliedRevision = plan.revision,
				collectedDataEpoch = 1L,
				updatedAtMs = 1L,
			),
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint(),
			authorization = authorization,
			requiresProviderAcceptance = requiresProviderAcceptance,
		)
	}
}
