package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class StepSourceRuntimeRetirementTest {
	@Test
	fun `orderly stop persists retirement before removing the exact listener`() = runTest {
		val fixture = fixture(this)

		assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, fixture.sink))
		fixture.runtime.close()

		assertEquals(listOf("provider-start", "retiring", "provider-stop", "retired"), fixture.events)
		assertSame(fixture.listeners.single(), fixture.removedListeners.single())
	}

	@Test
	fun `provider registration exception retires the provisional exact listener`() = runTest {
		val fixture = fixture(
			scope = this,
			registerFailure = IllegalStateException("provider apply was ambiguous"),
		)

		assertIs<SourceStartResult.Failed>(fixture.runtime.start(fixture.plan, fixture.sink))

		assertEquals(listOf("provider-start", "retiring", "provider-stop", "retired"), fixture.events)
		assertSame(fixture.listeners.single(), fixture.removedListeners.single())
	}

	@Test
	fun `failed exact removal fences refresh and replacement`() = runTest {
		val replacement = plan(2L)
		val fixture = fixture(
			scope = this,
			registrations = listOf(registration(plan(1L), 1L), registration(replacement, 2L, 10L)),
			unregisterFailures = ArrayDeque<Throwable>(listOf(IllegalStateException("remove failed"),
				IllegalStateException("remove failed again"))),
		)

		assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, fixture.sink))
		val stopped = fixture.runtime.quiesce(SessionCutoff("steps", 10L, 10L, Long.MAX_VALUE))
		val reapplied = fixture.runtime.reconfigure(replacement, fixture.sink)

		assertEquals(RegistrationRemovalOutcome.FAILED, stopped.registrationRemovalOutcome)
		assertEquals(SourceStopStatus.PROVIDER_FAILED, stopped.status)
		assertIs<SourceApplyResult.Failed>(reapplied)
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any<SensorEventListener>(), any<Sensor>(), any<Int>(), any<Int>())
		}
		coVerify(exactly = 1) { fixture.repository.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.repository.completeRetirement(any()) }
		assertSame(fixture.listeners.single(), fixture.removedListeners[0])
		assertSame(fixture.listeners.single(), fixture.removedListeners[1])
	}

	@Test
	fun `retirement persistence retry keeps the first boundary and never removes early`() = runTest {
		val replacement = plan(2L)
		val fixture = fixture(
			scope = this,
			registrations = listOf(registration(plan(1L), 1L), registration(replacement, 2L, 10L)),
			beginOutcomes = ArrayDeque<Any>(listOf(IllegalStateException("commit response lost"), true)),
		)

		assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, fixture.sink))
		val stopped = fixture.runtime.quiesce(SessionCutoff("steps", 10L, 10L, Long.MAX_VALUE))
		assertEquals(RegistrationRemovalOutcome.FAILED, stopped.registrationRemovalOutcome)
		assertEquals(0, fixture.removedListeners.size)
		assertIs<SourceApplyResult.Applied>(fixture.runtime.reconfigure(replacement, fixture.sink))

		assertEquals(2, fixture.retirementRequests.size)
		assertEquals(fixture.retirementRequests.first(), fixture.retirementRequests.last())
		assertSame(fixture.listeners.first(), fixture.removedListeners.single())
		fixture.runtime.close()
	}

	@Test
	fun `ambiguous completion retries the immutable token without removing twice`() = runTest {
		val replacement = plan(2L)
		val completionFailure = IllegalStateException("commit response lost")
		val fixture = fixture(
			scope = this,
			registrations = listOf(registration(plan(1L), 1L), registration(replacement, 2L, 10L)),
			completionOutcomes = ArrayDeque<Any>(listOf(completionFailure, true)),
		)

		assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, fixture.sink))
		val stopped = fixture.runtime.quiesce(SessionCutoff("steps", 10L, 10L, Long.MAX_VALUE))
		assertEquals(RegistrationRemovalOutcome.FAILED, stopped.registrationRemovalOutcome)
		assertIs<SourceApplyResult.Applied>(fixture.runtime.reconfigure(replacement, fixture.sink))

		coVerify(exactly = 1) { fixture.repository.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 2) { fixture.repository.completeRetirement(fixture.retirementTokens.single()) }
		assertEquals(1, fixture.removedListeners.size)
		verify(exactly = 2) {
			fixture.sensorManager.registerListener(any<SensorEventListener>(), any<Sensor>(), any<Int>(), any<Int>())
		}
		fixture.runtime.close()
	}

	@Test
	fun `hung retirement begin times out before exact listener removal`() = runTest {
		val fixture = fixture(
			scope = this,
			beginOutcomes = ArrayDeque(listOf("hang", true)),
		)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, fixture.sink))

		val first = fixture.runtime.quiesce(SessionCutoff("steps", 1L, 1L, Long.MAX_VALUE))

		assertEquals(SourceStopStatus.TIMED_OUT, first.status)
		assertEquals(0, fixture.removedListeners.size)
		fixture.runtime.close()
		assertSame(fixture.listeners.single(), fixture.removedListeners.single())
	}

	@Test
	fun `hung completion retains exact listener and token for retry`() = runTest {
		val fixture = fixture(
			scope = this,
			completionOutcomes = ArrayDeque(listOf("hang", true)),
		)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, fixture.sink))

		val first = fixture.runtime.quiesce(SessionCutoff("steps", 1L, 1L, Long.MAX_VALUE))
		assertEquals(SourceStopStatus.TIMED_OUT, first.status)
		assertSame(fixture.listeners.single(), fixture.removedListeners.single())

		fixture.runtime.close()
		coVerify(exactly = 1) { fixture.repository.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 2) { fixture.repository.completeRetirement(fixture.retirementTokens.single()) }
		assertEquals(1, fixture.removedListeners.size)
	}

	@Test
	fun `terminal checkpoint failure retries the original terminal intent and ack`() = runTest {
		val failure = IllegalStateException("terminal checkpoint unavailable")
		val fixture = fixture(
			scope = this,
			checkpointOutcomes = ArrayDeque(listOf(Unit, failure, Unit)),
		)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, fixture.sink))

		assertEquals(failure.message, assertFailsWith<IllegalStateException> { fixture.runtime.close() }.message)
		fixture.runtime.close()

		coVerify(exactly = 1) { fixture.repository.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.repository.completeRetirement(any()) }
		assertEquals(1, fixture.removedListeners.size)
	}

	@Test
	fun `noncooperative actor times out while ownership remains retryable`() = runTest {
		val applicationScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
		val admissionStarted = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate<*>) =
				error("Steps must use atomic admission")
			override suspend fun admit(
				candidate: com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate<*>,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceAdmissionHandoff {
				admissionStarted.complete(Unit)
				withContext(NonCancellable) { releaseAdmission.await() }
				return SourceAdmissionHandoff.Durable(1L)
			}
		}
		val fixture = fixture(applicationScope, sink = sink)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, sink))
		fixture.listeners.single().onSensorChanged(stepEvent(fixture.sensor, 1f))
		runCurrent()
		admissionStarted.await()
		val now = SystemClock.elapsedRealtimeNanos()

		val stopped = fixture.runtime.quiesce(SessionCutoff("steps", now, now, now + 1_000_000L))
		assertEquals(SourceStopStatus.TIMED_OUT, stopped.status)
		assertTrue(!stopped.appDrainComplete)
		releaseAdmission.complete(Unit)
		advanceUntilIdle()
		fixture.runtime.close()
		applicationScope.cancel()
	}

	@Test
	fun `caller cancellation propagates after noncancellable retirement settlement`() = runTest {
		val applicationScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
		val admissionStarted = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate<*>) =
				error("Steps must use atomic admission")
			override suspend fun admit(
				candidate: com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate<*>,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceAdmissionHandoff {
				admissionStarted.complete(Unit)
				withContext(NonCancellable) { releaseAdmission.await() }
				return SourceAdmissionHandoff.Durable(1L)
			}
		}
		val fixture = fixture(applicationScope, sink = sink)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, sink))
		fixture.listeners.single().onSensorChanged(stepEvent(fixture.sensor, 1f))
		runCurrent()
		admissionStarted.await()

		val closing = launch { fixture.runtime.close() }
		runCurrent()
		closing.cancel()
		advanceUntilIdle()

		assertTrue(closing.isCancelled)
		coVerify(exactly = 1) { fixture.repository.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.repository.completeRetirement(any()) }
		assertSame(fixture.listeners.single(), fixture.removedListeners.single())
		releaseAdmission.complete(Unit)
		advanceUntilIdle()
		fixture.runtime.close()
		applicationScope.cancel()
	}

	@Test
	fun `actor cancellation and fatal completion fence and durably retire ownership`() = runTest {
		for (failure in listOf(CancellationException("actor cancelled"), AssertionError("actor fatal"))) {
			val observed = mutableListOf<Throwable>()
			val handler = CoroutineExceptionHandler { _, throwable -> observed += throwable }
			val applicationScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + handler)
			val sink = object : SourceEventSink {
				override suspend fun admit(candidate: com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate<*>) =
					error("Steps must use atomic admission")
				override suspend fun admit(
					candidate: com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate<*>,
					checkpoint: SensorAdmissionCheckpoint,
				): SourceAdmissionHandoff = throw failure
			}
			val fixture = fixture(applicationScope, sink = sink)
			assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, sink))
			fixture.listeners.single().onSensorChanged(stepEvent(fixture.sensor, 1f))
			advanceUntilIdle()

			assertSame(fixture.listeners.single(), fixture.removedListeners.single())
			coVerify(exactly = 1) { fixture.repository.beginRetirement(any(), any(), any(), any()) }
			val replayed = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))
			assertEquals(
				fixture.retirementRequests.single().registration.state.registrationGeneration,
				replayed.registrationGeneration,
			)
			assertEquals(RegistrationRemovalOutcome.REMOVED, replayed.registrationRemovalOutcome)
			assertEquals(replayed, fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE)))
			coVerify(exactly = 1) { fixture.repository.beginRetirement(any(), any(), any(), any()) }
			if (failure is Error) assertTrue(observed.any { it === failure })
			applicationScope.cancel()
		}
	}

	@Test
	fun `acceptance cancellation cleans up noncancellable and rethrows`() = runTest {
		val fixture = fixture(
			scope = this,
			acceptanceFailure = CancellationException("cancel acceptance"),
		)

		assertFailsWith<CancellationException> {
			fixture.runtime.start(fixture.plan, fixture.sink)
		}

		assertEquals(listOf("provider-start", "retiring", "provider-stop", "retired"), fixture.events)
		assertSame(fixture.listeners.single(), fixture.removedListeners.single())
	}

	@Test
	fun `acceptance fatal error cleans up and is never converted to operational failure`() = runTest {
		val fatal = AssertionError("fatal acceptance")
		val fixture = fixture(scope = this, acceptanceFailure = fatal)

		assertSame(fatal, assertFailsWith<AssertionError> {
			fixture.runtime.start(fixture.plan, fixture.sink)
		})

		assertEquals(listOf("provider-start", "retiring", "provider-stop", "retired"), fixture.events)
	}

	@Test
	fun `null acceptance activates a first provider with no predecessor`() = runTest {
		val fixture = fixture(scope = this)

		assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, fixture.sink))
		fixture.runtime.close()

		assertEquals(listOf("provider-start", "retiring", "provider-stop", "retired"), fixture.events)
	}

	@Test
	fun `stale acceptance exception retires the provisional listener`() = runTest {
		val fixture = fixture(
			scope = this,
			acceptanceFailure = IllegalStateException("stale acceptance"),
		)

		assertIs<SourceStartResult.Failed>(fixture.runtime.start(fixture.plan, fixture.sink))

		assertEquals(listOf("provider-start", "retiring", "provider-stop", "retired"), fixture.events)
	}

	@Test
	fun `retirement cancellation and fatal errors contain the old actor before replacement`() = runTest {
		for (stage in RetirementFailureStage.entries) {
			for (fatal in listOf(false, true)) {
				val replacementPlan = plan(2L)
				val failure = if (fatal) {
					AssertionError("fatal ${stage.name.lowercase()}")
				} else {
					CancellationException("cancel ${stage.name.lowercase()}")
				}
				val fixture = fixture(
					scope = this,
					registrations = listOf(
						registration(plan(1L), 1L),
						registration(replacementPlan, 2L, 10L),
					),
					unregisterFailures = if (stage == RetirementFailureStage.REMOVE) {
						ArrayDeque(listOf(failure))
					} else {
						ArrayDeque()
					},
					beginOutcomes = if (stage == RetirementFailureStage.BEGIN) {
						ArrayDeque(listOf(failure, true))
					} else {
						ArrayDeque()
					},
					completionOutcomes = if (stage == RetirementFailureStage.COMPLETE) {
						ArrayDeque(listOf(failure, true))
					} else {
						ArrayDeque()
					},
				)
				assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, fixture.sink))

				if (fatal) {
					val observed = assertFailsWith<AssertionError> { fixture.runtime.close() }
					assertEquals(failure.message, observed.message)
				} else {
					val observed = assertFailsWith<CancellationException> { fixture.runtime.close() }
					assertEquals(failure.message, observed.message)
				}

				assertIs<SourceApplyResult.Applied>(
					fixture.runtime.reconfigure(replacementPlan, fixture.sink),
				)
				verify(exactly = 2) {
					fixture.sensorManager.registerListener(
						any<SensorEventListener>(), any<Sensor>(), any<Int>(), any<Int>(),
					)
				}
				val expectedRemovalAttempts = if (stage == RetirementFailureStage.REMOVE) 2 else 1
				assertEquals(expectedRemovalAttempts, fixture.removedListeners.size)
				fixture.removedListeners.forEach { removed ->
					assertSame(fixture.listeners.first(), removed)
				}
				fixture.runtime.close()
			}
		}
	}

	private enum class RetirementFailureStage { BEGIN, REMOVE, COMPLETE }

	@Test
	fun `synchronous overflow during resume stays fenced and schedules another recovery`() = runTest {
		val applicationScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
		ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
		val firstAdmissionStarted = CompletableDeferred<Unit>()
		val releaseFirstAdmission = CompletableDeferred<Unit>()
		var admissionOrdinal = 0L
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate<*>):
				SourceAdmissionHandoff = error("Steps must use atomic admission")

			override suspend fun admit(
				candidate: com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate<*>,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceAdmissionHandoff {
				if (++admissionOrdinal == 1L) {
					firstAdmissionStarted.complete(Unit)
					releaseFirstAdmission.await()
				}
				return SourceAdmissionHandoff.Durable(admissionOrdinal)
			}
		}
		var cumulativeSteps = 0f
		val fixture = fixture(
			scope = applicationScope,
			sink = sink,
			onRegister = { registrationIndex, listener, sensor ->
				if (registrationIndex == 1) {
					repeat(STEP_CALLBACK_LANE_CAPACITY + 1) {
						listener.onSensorChanged(stepEvent(sensor, ++cumulativeSteps))
					}
				}
			},
		)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(fixture.plan, sink))
		val initialListener = fixture.listeners.single()
		initialListener.onSensorChanged(stepEvent(fixture.sensor, ++cumulativeSteps))
		runCurrent()
		firstAdmissionStarted.await()
		repeat(STEP_CALLBACK_LANE_CAPACITY + 1) {
			initialListener.onSensorChanged(stepEvent(fixture.sensor, ++cumulativeSteps))
		}

		releaseFirstAdmission.complete(Unit)
		advanceUntilIdle()

		assertEquals(3, fixture.listeners.size)
		assertEquals(2, fixture.removedListeners.size)
		assertSame(fixture.listeners[0], fixture.removedListeners[0])
		assertSame(fixture.listeners[1], fixture.removedListeners[1])
		coVerify(exactly = 0) { fixture.repository.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.repository.completeRetirement(any()) }

		fixture.runtime.close()
		assertTrue(fixture.removedListeners.any { it === fixture.listeners[2] })
		applicationScope.cancel()
	}

	private data class Fixture(
		val runtime: StepSourceRuntime,
		val repository: SourceRegistrationRepository,
		val sensorManager: SensorManager,
		val sensor: Sensor,
		val plan: StepsPlan,
		val sink: SourceEventSink,
		val events: MutableList<String>,
		val listeners: MutableList<SensorEventListener>,
		val removedListeners: MutableList<SensorEventListener>,
		val retirementTokens: MutableList<SourceRegistrationRetirementToken>,
		val retirementRequests: MutableList<RetirementRequest>,
	)

	private data class RetirementRequest(
		val registration: SourceRegistration,
		val reason: String,
		val retiredAtMs: Long,
		val retiredElapsedNanos: Long,
	)

	private fun fixture(
		scope: kotlinx.coroutines.CoroutineScope,
		registrations: List<SourceRegistration> = listOf(registration(plan(1L), 1L)),
		unregisterFailures: ArrayDeque<Throwable> = ArrayDeque(),
		beginOutcomes: ArrayDeque<Any> = ArrayDeque(),
		completionOutcomes: ArrayDeque<Any> = ArrayDeque(),
		registerFailure: Throwable? = null,
		acceptanceFailure: Throwable? = null,
		checkpointOutcomes: ArrayDeque<Any> = ArrayDeque(),
		sink: SourceEventSink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) },
		onRegister: ((Int, SensorEventListener, Sensor) -> Unit)? = null,
	): Fixture {
		val context = mockk<Context>()
		val packageManager = mockk<PackageManager>()
		val sensorManager = mockk<SensorManager>()
		val sensor = mockk<Sensor>()
		val repository = mockk<SourceRegistrationRepository>(relaxed = true)
		val events = mutableListOf<String>()
		val listeners = mutableListOf<SensorEventListener>()
		val removedListeners = mutableListOf<SensorEventListener>()
		val retirementTokens = mutableListOf<SourceRegistrationRetirementToken>()
		val retirementRequests = mutableListOf<RetirementRequest>()
		every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
		every { context.packageManager } returns packageManager
		every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
		every { packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER) } returns true
		every { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns sensor
		every { sensor.type } returns Sensor.TYPE_STEP_COUNTER
		every { sensor.fifoMaxEventCount } returns 0
		every { sensor.minDelay } returns 0
		var registrationIndex = 0
		every { sensorManager.registerListener(any<SensorEventListener>(), sensor, any<Int>(), any<Int>()) } answers {
			listeners += firstArg<SensorEventListener>()
			events += "provider-start"
			onRegister?.invoke(registrationIndex++, firstArg(), sensor)
			registerFailure?.let { throw it }
			true
		}
		every { sensorManager.unregisterListener(any<SensorEventListener>()) } answers {
			removedListeners += firstArg<SensorEventListener>()
			events += "provider-stop"
			unregisterFailures.removeFirstOrNull()?.let { throw it }
		}
		var beginIndex = 0
		coEvery { repository.begin(any(), any(), any(), any(), any()) } answers {
			registrations[beginIndex++.coerceAtMost(registrations.lastIndex)]
		}
		coEvery { repository.loadRuntimeState(any()) } returns null
		coEvery { repository.saveRuntimeState(any(), any(), any(), any(), any(), any(), any()) } answers {
			when (val outcome = checkpointOutcomes.removeFirstOrNull()) {
				is Throwable -> throw outcome
				else -> Unit
			}
		}
		coEvery { repository.markAccepted(any(), any(), any()) } answers {
			acceptanceFailure?.let { throw it }
			null
		}
		coEvery { repository.beginRetirement(any(), any(), any(), any()) } coAnswers {
			events += "retiring"
			val request = RetirementRequest(firstArg(), secondArg(), thirdArg(), arg(3))
			retirementRequests += request
			when (val outcome = beginOutcomes.removeFirstOrNull()) {
				is Throwable -> throw outcome
				"hang" -> awaitCancellation()
				else -> retirementToken(
					request.registration,
					request.reason,
					request.retiredAtMs,
					request.retiredElapsedNanos,
				).also(retirementTokens::add)
			}
		}
		coEvery { repository.completeRetirement(any()) } coAnswers {
			events += "retired"
			when (val outcome = completionOutcomes.removeFirstOrNull()) {
				is Throwable -> throw outcome
				"hang" -> awaitCancellation()
				is Boolean -> outcome
				else -> true
			}
		}
		return Fixture(
			runtime = StepSourceRuntime(context, scope, repository),
			repository = repository,
			sensorManager = sensorManager,
			sensor = sensor,
			plan = registrations.first().let { plan(requireNotNull(it.state.appliedRevision)) },
			sink = sink,
			events = events,
			listeners = listeners,
			removedListeners = removedListeners,
			retirementTokens = retirementTokens,
			retirementRequests = retirementRequests,
		)
	}

	private fun stepEvent(sensor: Sensor, cumulativeSteps: Float): SensorEvent {
		ShadowSystemClock.advanceBy(Duration.ofMillis(1))
		val constructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType!!)
		constructor.isAccessible = true
		return constructor.newInstance(1).also { event ->
			event.sensor = sensor
			event.timestamp = SystemClock.elapsedRealtimeNanos()
			event.values[0] = cumulativeSteps
		}
	}

	private fun plan(revision: Long) = StepsPlan(
		revision = revision,
		enabled = true,
		maximumReportLatencyMs = 0L,
		projectionCheckpointIntervalMs = 5_000L,
		movementPolicyNeedsLowLatency = false,
	)

	private fun registration(
		plan: StepsPlan,
		authorizationRevision: Long,
		generation: Long = 9L,
	): SourceRegistration {
		val demand = SourceDemandEntity(
			demandId = "steps-$authorizationRevision",
			consumerId = "session:steps",
			sourceKind = SourceKind.STEPS.stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = "steps",
			serviceRunId = "run-1",
			manifestRevision = authorizationRevision,
			lifecycleLeaseGeneration = 1L,
			sourcePolicyRevision = authorizationRevision,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 1,
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
		val authorization = requireNotNull(SourceBrokerAuthorization.rows(
			SourceKind.STEPS.stableCode,
			generation,
			authorizationRevision,
			listOf(demand),
			"boot-1",
			authorizationRevision,
			1L,
		).toAuthorizationSnapshotOrNull())
		val ownerScope = "source-broker:${SourceKind.STEPS.stableCode}"
		return SourceRegistration(
			ownerScope,
			SourceRegistrationStateEntity(
				SourceKind.STEPS.stableCode,
				ownerScope,
				"steps-1",
				"boot-1",
				generation,
				0L,
				plan.revision,
				1L,
				1L,
			),
			plan.physicalConfigurationFingerprint(),
			authorization,
			requiresProviderAcceptance = true,
		)
	}

	private fun retirementToken(
		registration: SourceRegistration,
		reason: String,
		retiredAtMs: Long,
		retiredElapsedNanos: Long,
	) = SourceRegistrationRetirementToken(
		SourceKind.STEPS,
		SourceInstanceId(registration.state.sourceInstanceId),
		registration.state.registrationGeneration,
		"process-1",
		retiredAtMs,
		retiredElapsedNanos,
		reason,
	)

	private fun sessionCutoff(elapsedRealtimeNanos: Long): SessionCutoff = SessionCutoff(
		logicalTrackingId = "steps-test",
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		wallTimeMs = System.currentTimeMillis(),
		deadlineElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() + 1_000_000_000L,
	)
}
