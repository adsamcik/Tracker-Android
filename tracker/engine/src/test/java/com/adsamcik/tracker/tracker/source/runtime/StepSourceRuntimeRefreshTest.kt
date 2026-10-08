package com.adsamcik.tracker.tracker.source.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class StepSourceRuntimeRefreshTest {
	@Test
	fun `compatible authorization refresh keeps the existing step listener`() = runTest {
		val initialPlan = plan(revision = 1L)
		val refreshedPlan = initialPlan.copy(revision = 2L)
		val initialRegistration = registration(initialPlan, authorizationRevision = 1L, generation = 9L)
		val refreshedRegistration = registration(refreshedPlan, authorizationRevision = 2L, generation = 9L)
		val context = mockk<Context>()
		val packageManager = mockk<PackageManager>()
		val sensorManager = mockk<SensorManager>()
		val sensor = mockk<Sensor>()
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
		every { context.packageManager } returns packageManager
		every { packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER) } returns true
		every { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns sensor
		stubCounterIdentity(sensor)
		every { sensor.fifoMaxEventCount } returns 0
		every { sensor.minDelay } returns 0
		every {
			sensorManager.registerListener(any<SensorEventListener>(), sensor, any<Int>(), any<Int>())
		} returns true
		every { sensorManager.unregisterListener(any<SensorEventListener>()) } just Runs
		coEvery {
			registrations.beginPurposeScoped(any(), any(), any(), any(), any(), any())
		} returns initialRegistration
		coEvery {
			registrations.refreshPurposeScopedAuthorization(any(), any(), any(), any(), any(), any(), any())
		} returns refreshedRegistration
		coEvery { registrations.loadRuntimeState(any()) } returns null
		coEvery { registrations.beginRetirement(any(), any(), any(), any()) } answers {
			retirementToken(firstArg())
		}
		coEvery { registrations.completeRetirement(any()) } returns true
		val runtime = StepSourceRuntime(
			context,
			backgroundScope,
			registrations,
			mockk<StepsSessionFactProjectionLane>(relaxed = true),
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertIs<SourceStartResult.Started>(runtime.start(initialPlan, sink))
		assertIs<SourceApplyResult.Applied>(runtime.reconfigure(refreshedPlan, sink))

		coVerify(exactly = 1) {
			registrations.beginPurposeScoped(
				any(), any(), any(), any(), any(), SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			)
		}
		coVerify(exactly = 1) {
			registrations.refreshPurposeScopedAuthorization(
				any(), initialRegistration, any(), any(), any(), any(),
				SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			)
		}
		verify(exactly = 1) {
			sensorManager.registerListener(any<SensorEventListener>(), sensor, any<Int>(), any<Int>())
		}
		verify(exactly = 0) { sensorManager.unregisterListener(any<SensorEventListener>()) }
		runtime.close()
	}

	@Test
	fun `incompatible authorization check reserves only one step replacement`() = runTest {
		val initialPlan = plan(revision = 1L)
		val replacementPlan = initialPlan.copy(revision = 2L)
		val initialRegistration = registration(initialPlan, authorizationRevision = 1L, generation = 9L)
		val replacementRegistration = registration(replacementPlan, authorizationRevision = 2L, generation = 10L)
		val context = mockk<Context>()
		val packageManager = mockk<PackageManager>()
		val sensorManager = mockk<SensorManager>()
		val sensor = mockk<Sensor>()
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
		every { context.packageManager } returns packageManager
		every { packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER) } returns true
		every { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns sensor
		stubCounterIdentity(sensor)
		every { sensor.fifoMaxEventCount } returns 0
		every { sensor.minDelay } returns 0
		every {
			sensorManager.registerListener(any<SensorEventListener>(), sensor, any<Int>(), any<Int>())
		} returns true
		every { sensorManager.unregisterListener(any<SensorEventListener>()) } just Runs
		coEvery {
			registrations.beginPurposeScoped(any(), any(), any(), any(), any(), any())
		} returnsMany
			listOf(initialRegistration, replacementRegistration)
		coEvery {
			registrations.refreshPurposeScopedAuthorization(any(), any(), any(), any(), any(), any(), any())
		} returns null
		coEvery { registrations.loadRuntimeState(any()) } returns null
		coEvery { registrations.beginRetirement(any(), any(), any(), any()) } answers {
			retirementToken(firstArg())
		}
		coEvery { registrations.completeRetirement(any()) } returns true
		val runtime = StepSourceRuntime(
			context,
			backgroundScope,
			registrations,
			mockk<StepsSessionFactProjectionLane>(relaxed = true),
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertIs<SourceStartResult.Started>(runtime.start(initialPlan, sink))
		assertIs<SourceApplyResult.Applied>(runtime.reconfigure(replacementPlan, sink))

		coVerify(exactly = 1) {
			registrations.refreshPurposeScopedAuthorization(
				any(), initialRegistration, any(), any(), any(), any(),
				SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			)
		}
		// Initial acquisition plus the orderly post-stop replacement; no speculative reservation.
		coVerify(exactly = 2) {
			registrations.beginPurposeScoped(
				any(), any(), any(), any(), any(), SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			)
		}
		verify(exactly = 2) {
			sensorManager.registerListener(any<SensorEventListener>(), sensor, any<Int>(), any<Int>())
		}
		verify(exactly = 1) { sensorManager.unregisterListener(any<SensorEventListener>()) }
		runtime.close()
	}

	@Test
	fun `verified live recording transfers only its operation claim without refreshing authorization`() = runTest {
		val fixture = continuityFixture(backgroundScope)
		val initialClaim = claim(1L)
		val initialPlan = plan(1L)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(initialClaim, initialPlan, fixture.sink))
		val continuation = assertNotNull(fixture.runtime.continuingRecording(initialClaim, plan(2L)))
		val next = claim(2L)
		val result = assertIs<SourceApplyResult.Applied>(
			fixture.runtime.reuseContinuingRecording(continuation, next, plan(2L)) { true },
		)
		assertEquals(2L, result.state.desiredRevision)
		assertEquals(continuation.registration.state.registrationGeneration, result.state.registrationGeneration)
		coVerify(exactly = 0) {
			fixture.registrations.refreshPurposeScopedAuthorization(any(), any(), any(), any(), any(), any(), any())
		}
		verify(exactly = 1) { fixture.sensors.registerListener(any<SensorEventListener>(), any<Sensor>(), any<Int>(), any<Int>()) }
		verify(exactly = 0) { fixture.sensors.unregisterListener(any<SensorEventListener>()) }
		val cutoff = cutoff()
		assertIs<OwnedSourceShutdown.NotOwned>(fixture.runtime.shutdownIfOwned(initialClaim, cutoff))
		assertIs<OwnedSourceShutdown.Released>(fixture.runtime.shutdownIfOwned(next, cutoff))
	}

	@Test
	fun `permission loss after preflight cleans the old recording and allows only a normal fresh start`() = runTest {
		val fixture = continuityFixture(backgroundScope)
		val initialClaim = claim(1L)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(initialClaim, plan(1L), fixture.sink))
		val continuation = assertNotNull(fixture.runtime.continuingRecording(initialClaim, plan(2L)))
		val result = fixture.runtime.reuseContinuingRecording(continuation, claim(2L), plan(2L)) {
			// The Room recheck is a storage fixture; permission changes during that suspension boundary.
			fixture.permission = false
			true
		}
		assertIs<SourceApplyResult.Failed>(result)
		verify(exactly = 1) { fixture.sensors.unregisterListener(any<SensorEventListener>()) }
		verify(exactly = 1) { fixture.sensors.registerListener(any<SensorEventListener>(), any<Sensor>(), any<Int>(), any<Int>()) }
		fixture.permission = true
		assertIs<SourceApplyResult.Applied>(fixture.runtime.reconfigure(claim(3L), plan(3L), fixture.sink))
		coVerify(exactly = 2) { fixture.registrations.beginPurposeScoped(any(), any(), any(), any(), any(), any()) }
		verify(exactly = 2) { fixture.sensors.registerListener(any<SensorEventListener>(), any<Sensor>(), any<Int>(), any<Int>()) }
		fixture.runtime.close()
	}

	@Test
	fun `stale preflight cannot stop a replacement owner or reuse its new listener`() = runTest {
		val fixture = continuityFixture(backgroundScope)
		val old = claim(1L)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(old, plan(1L), fixture.sink))
		val stale = assertNotNull(fixture.runtime.continuingRecording(old, plan(2L)))
		val successor = claim(2L)
		assertIs<SourceApplyResult.Applied>(fixture.runtime.reconfigure(successor, plan(2L), fixture.sink))
		var rechecked = false
		assertIs<SourceApplyResult.Failed>(fixture.runtime.reuseContinuingRecording(stale, claim(3L), plan(3L)) {
			rechecked = true
			true
		})
		assertFalse(rechecked)
		verify(exactly = 1) { fixture.sensors.unregisterListener(any<SensorEventListener>()) }
		assertIs<OwnedSourceShutdown.Released>(fixture.runtime.shutdownIfOwned(successor,
			cutoff()))
		verify(exactly = 2) { fixture.sensors.unregisterListener(any<SensorEventListener>()) }
	}

	private fun cutoff(): SessionCutoff {
		val now = SystemClock.elapsedRealtimeNanos()
		return SessionCutoff("steps-test", now, System.currentTimeMillis(), now + 3_000_000_000L)
	}

	private fun claim(generation: Long) = SourceRuntimeClaim(SourceKind.STEPS, "action-$generation", 1,
		generation, "steps-test", "run-1")

	/** Real runtime/actor/listener lifecycle; Android hardware and registration storage are fixtures. */
	private fun continuityFixture(scope: CoroutineScope): ContinuityFixture {
		val context = mockk<Context>()
		val packageManager = mockk<PackageManager>()
		val sensors = mockk<SensorManager>()
		val sensor = mockk<Sensor>()
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fixture = ContinuityFixture(sensors, registrations)
		every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensors
		every { context.packageManager } returns packageManager
		every { context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) } answers {
			if (fixture.permission) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
		}
		every { context.checkPermission(Manifest.permission.ACTIVITY_RECOGNITION, any(), any()) } answers {
			if (fixture.permission) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
		}
		every { packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER) } returns true
		every { sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns sensor
		stubCounterIdentity(sensor)
		every { sensor.fifoMaxEventCount } returns 0
		every { sensor.minDelay } returns 0
		every { sensors.registerListener(any<SensorEventListener>(), sensor, any<Int>(), any<Int>()) } returns true
		every { sensors.unregisterListener(any<SensorEventListener>()) } just Runs
		var acquisitions = 0L
		coEvery { registrations.beginPurposeScoped(any(), any(), any(), any(), any(), any()) } coAnswers {
			acquisitions++
			val revision = secondArg<Long>()
			registration(plan(revision), revision, 8L + acquisitions)
		}
		coEvery { registrations.refreshPurposeScopedAuthorization(any(), any(), any(), any(), any(), any(), any()) } returns null
		coEvery { registrations.loadRuntimeState(any()) } returns null
		every { registrations.currentProcessIncarnationId() } returns "process-1"
		coEvery { registrations.beginRetirement(any(), any(), any(), any()) } answers { retirementToken(firstArg()) }
		coEvery { registrations.completeRetirement(any()) } returns true
		fixture.runtime = StepSourceRuntime(context, scope, registrations,
			mockk<StepsSessionFactProjectionLane>(relaxed = true))
		return fixture
	}

	private class ContinuityFixture(
		val sensors: SensorManager,
		val registrations: SourceRegistrationRepository,
	) {
		var permission = true
		lateinit var runtime: StepSourceRuntime
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
	}

	private fun plan(revision: Long) = StepsPlan(
		revision = revision,
		enabled = true,
		maximumReportLatencyMs = 30_000L,
		projectionCheckpointIntervalMs = 5_000L,
		movementPolicyNeedsLowLatency = false,
	)

	private fun stubCounterIdentity(sensor: Sensor) {
		every { sensor.type } returns Sensor.TYPE_STEP_COUNTER
		every { sensor.stringType } returns "android.sensor.step_counter"
		every { sensor.vendor } returns "vendor"
		every { sensor.name } returns "counter"
		every { sensor.version } returns 1
		every { sensor.id } returns 7
	}

	private fun registration(
		plan: StepsPlan,
		authorizationRevision: Long,
		generation: Long,
	): SourceRegistration {
		val demand = SourceDemandEntity(
			demandId = "steps-demand-$authorizationRevision",
			consumerId = "session:steps-test",
			sourceKind = SourceKind.STEPS.stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = "steps-test",
			serviceRunId = "run-1",
			manifestRevision = authorizationRevision,
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
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = generation,
				authorizationRevision = authorizationRevision,
				demands = listOf(demand),
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = authorizationRevision,
				effectiveWallTimeMs = 1L,
			).toAuthorizationSnapshotOrNull(),
		)
		val ownerScope = SourceProviderPurposeScope.exactOwnerScope(
			SourceKind.STEPS.stableCode,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		)
		return SourceRegistration(
			ownerScope = ownerScope,
			state = SourceRegistrationStateEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				ownerScope = ownerScope,
				sourceInstanceId = "steps-1",
				clockDomainId = "boot-1",
				registrationGeneration = generation,
				nextSequence = 0L,
				appliedRevision = plan.revision,
				collectedDataEpoch = 1L,
				updatedAtMs = 1L,
			),
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint(),
			authorization = authorization,
			requiresProviderAcceptance = false,
		)
	}

	private fun retirementToken(registration: SourceRegistration) = SourceRegistrationRetirementToken(
		source = SourceKind.STEPS,
		sourceInstanceId = SourceInstanceId(registration.state.sourceInstanceId),
		registrationGeneration = registration.state.registrationGeneration,
		processIncarnationId = "process-1",
		retiredAtMs = 1L,
		retiredElapsedRealtimeNanos = 1L,
		reason = "ORDERLY_STOP",
	)
}
