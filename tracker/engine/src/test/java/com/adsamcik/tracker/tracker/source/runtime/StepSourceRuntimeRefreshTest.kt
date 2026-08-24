package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertIs
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
		every { sensor.fifoMaxEventCount } returns 0
		every { sensor.minDelay } returns 0
		every {
			sensorManager.registerListener(any<SensorEventListener>(), sensor, any<Int>(), any<Int>())
		} returns true
		every { sensorManager.unregisterListener(any<SensorEventListener>()) } just Runs
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returns initialRegistration
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns refreshedRegistration
		coEvery { registrations.loadRuntimeState(any()) } returns null
		coEvery { registrations.beginRetirement(any(), any(), any(), any()) } answers {
			retirementToken(firstArg())
		}
		coEvery { registrations.completeRetirement(any()) } returns true
		val runtime = StepSourceRuntime(context, backgroundScope, registrations)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertIs<SourceStartResult.Started>(runtime.start(initialPlan, sink))
		assertIs<SourceApplyResult.Applied>(runtime.reconfigure(refreshedPlan, sink))

		coVerify(exactly = 1) { registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 1) {
			registrations.refreshActiveAuthorization(any(), initialRegistration, any(), any(), any(), any())
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
		every { sensor.fifoMaxEventCount } returns 0
		every { sensor.minDelay } returns 0
		every {
			sensorManager.registerListener(any<SensorEventListener>(), sensor, any<Int>(), any<Int>())
		} returns true
		every { sensorManager.unregisterListener(any<SensorEventListener>()) } just Runs
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returnsMany
			listOf(initialRegistration, replacementRegistration)
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns null
		coEvery { registrations.loadRuntimeState(any()) } returns null
		coEvery { registrations.beginRetirement(any(), any(), any(), any()) } answers {
			retirementToken(firstArg())
		}
		coEvery { registrations.completeRetirement(any()) } returns true
		val runtime = StepSourceRuntime(context, backgroundScope, registrations)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertIs<SourceStartResult.Started>(runtime.start(initialPlan, sink))
		assertIs<SourceApplyResult.Applied>(runtime.reconfigure(replacementPlan, sink))

		coVerify(exactly = 1) {
			registrations.refreshActiveAuthorization(any(), initialRegistration, any(), any(), any(), any())
		}
		// Initial acquisition plus the orderly post-stop replacement; no speculative reservation.
		coVerify(exactly = 2) { registrations.begin(any(), any(), any(), any(), any()) }
		verify(exactly = 2) {
			sensorManager.registerListener(any<SensorEventListener>(), sensor, any<Int>(), any<Int>())
		}
		verify(exactly = 1) { sensorManager.unregisterListener(any<SensorEventListener>()) }
		runtime.close()
	}

	private fun plan(revision: Long) = StepsPlan(
		revision = revision,
		enabled = true,
		maximumReportLatencyMs = 30_000L,
		projectionCheckpointIntervalMs = 5_000L,
		movementPolicyNeedsLowLatency = false,
	)

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
		val ownerScope = "source-broker:${SourceKind.STEPS.stableCode}"
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
