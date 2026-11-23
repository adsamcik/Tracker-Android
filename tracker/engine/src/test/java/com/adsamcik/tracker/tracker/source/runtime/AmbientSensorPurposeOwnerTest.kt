package com.adsamcik.tracker.tracker.source.runtime

import android.Manifest
import android.os.BatteryManager
import android.os.PowerManager
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationSnapshot
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.api.TrackingPurposeReconciliationRetryScheduler
import com.adsamcik.tracker.tracker.policy.BatteryAwarePolicy
import com.adsamcik.tracker.tracker.service.BackgroundLocationHost
import com.adsamcik.tracker.tracker.service.BackgroundLocationHostResult
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.control.LocationMotionScope
import com.adsamcik.tracker.activity.api.registration.LocationMotionPurpose
import com.adsamcik.tracker.tracker.source.coordinator.SemanticAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanResolver
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientSensorPurposeOwnerTest {
	@Test
	fun `Activity and Pressure record without Location or a session through their own shared owners`() = runTest {
		val fixture = Fixture()
		val activity = fixture.owner.reconcile(fixture.activityLease)
		val pressure = fixture.owner.reconcile(fixture.pressureLease)

		assertEquals(AmbientSourceOperationalState.READY, activity.state)
		assertEquals(AmbientAcquisitionMechanism.ACTIVITY_RECOGNITION, activity.mechanism)
		assertEquals(AmbientSourceOperationalState.READY, pressure.state)
		assertEquals(AmbientAcquisitionMechanism.PRESSURE_SENSOR, pressure.mechanism)
		assertEquals(setOf(AmbientTrackingSource.ACTIVITY, AmbientTrackingSource.PRESSURE), fixture.accepted.keys)
		coVerify(exactly = 1) { fixture.activity.setDemand(ActivityRegistrationOwner.ALL_DAY_TRACKING, any()) }
		coVerify(exactly = 1) { fixture.pressure.reconcileAmbient(match { it?.enabled == true }) }
		coVerify(exactly = 0) { fixture.host.acquire(match { it.source != AmbientTrackingSource.PRESSURE }) }
	}

	@Test
	fun `denied Activity permission keeps an independently running Pressure source`() = runTest {
		val fixture = Fixture()
		fixture.owner.reconcile(fixture.pressureLease)
		Shadows.shadowOf(fixture.context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

		val result = fixture.owner.reconcile(fixture.activityLease)

		assertEquals(AmbientSourceOperationalState.PERMISSION_REQUIRED, result.state)
		assertEquals(AmbientSourceUnavailableReason.ACTIVITY_PERMISSION_REQUIRED, result.reason)
		assertEquals(fixture.pressureLease.identity, fixture.accepted[AmbientTrackingSource.PRESSURE])
		assertEquals(fixture.pressureLease.identity, fixture.hosted.value[AmbientTrackingSource.PRESSURE]?.identity)
		coVerify(exactly = 0) { fixture.pressure.reconcileAmbient(null) }
	}

	@Test
	fun `retiring all-day Activity removes only its owner and preserves automatic detection`() = runTest {
		val fixture = Fixture(automatic = true)
		fixture.owner.reconcile(fixture.activityLease)

		assertTrue(fixture.owner.retire(AmbientTrackingSource.ACTIVITY, fixture.activityLease))

		assertEquals(setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR), fixture.activityOwners)
		assertFalse(fixture.accepted.containsKey(AmbientTrackingSource.ACTIVITY))
		coVerify(exactly = 0) { fixture.activity.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR) }
		coVerify(exactly = 0) { fixture.retries.schedule(any()) }
	}

	@Test
	fun `a late predecessor retirement cannot remove its accepted Activity successor`() = runTest {
		val fixture = Fixture(automatic = true)
		fixture.owner.reconcile(fixture.activityLease)
		val successor = AmbientReconciliationLease(fixture.activityLease.identity.copy(
			ownerCasToken = "activity-successor", executionRevision = 2L))
		fixture.owner.reconcile(successor)

		assertTrue(fixture.owner.retire(AmbientTrackingSource.ACTIVITY, fixture.activityLease))

		assertEquals(successor.identity, fixture.accepted[AmbientTrackingSource.ACTIVITY])
		assertTrue(ActivityRegistrationOwner.ALL_DAY_TRACKING in fixture.activityOwners)
		coVerify(exactly = 0) { fixture.activity.clearDemand(any()) }
		assertTrue(fixture.owner.retire(AmbientTrackingSource.ACTIVITY, successor))
	}

	@Test
	fun `a failed Pressure host withdraws its demand without starting the sensor`() = runTest {
		val fixture = Fixture()
		coEvery { fixture.host.acquire(any()) } returns BackgroundLocationHostResult.Blocked("HOST_UNAVAILABLE")

		val result = fixture.owner.reconcile(fixture.pressureLease)

		assertEquals(AmbientSourceOperationalState.UNAVAILABLE, result.state)
		assertEquals(AmbientSourceUnavailableReason.FOREGROUND_HOST_UNAVAILABLE, result.reason)
		assertFalse(fixture.accepted.containsKey(AmbientTrackingSource.PRESSURE))
		assertTrue(fixture.hosted.value.isEmpty())
		coVerify(exactly = 0) { fixture.pressure.reconcileAmbient(match { it?.enabled == true }) }
	}

	@Test
	fun `settings replaced during dispatch cannot fall back to a stale Activity plan`() = runTest {
		val fixture = Fixture()
		fixture.afterDispatch = { fixture.params.value = fixture.params.value.copy(sourcePolicyRevision = 3L) }

		val result = fixture.owner.reconcile(fixture.activityLease)

		assertFalse(result.isOperational)
		assertFalse(fixture.accepted.containsKey(AmbientTrackingSource.ACTIVITY))
		coVerify(exactly = 0) { fixture.activity.setDemand(any(), any()) }
	}

	@Test
	fun `an automatic-only arbiter snapshot cannot claim all-day Activity is recording`() = runTest {
		val fixture = Fixture(automatic = true)
		coEvery { fixture.activity.setDemand(ActivityRegistrationOwner.ALL_DAY_TRACKING, any()) } returns
			ActivityRegistrationResult(ActivityRegistrationStatus.DEGRADED, fixture.activitySnapshot())

		val result = fixture.owner.reconcile(fixture.activityLease)

		assertFalse(result.isOperational)
		assertFalse(fixture.accepted.containsKey(AmbientTrackingSource.ACTIVITY))
		assertEquals(setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR), fixture.activityOwners)
	}

	@Test
	fun `Activity refresh failure retries that source while Pressure keeps updating`() = runTest {
		val fixture = Fixture()
		fixture.owner.reconcile(fixture.activityLease)
		fixture.owner.reconcile(fixture.pressureLease)
		coEvery { fixture.activity.setDemand(ActivityRegistrationOwner.ALL_DAY_TRACKING, any()) } throws
			IllegalStateException("Activity provider unavailable")

		fixture.owner.refreshActivePlans()

		coVerify(exactly = 2) { fixture.pressure.reconcileAmbient(match { it?.enabled == true }) }
		coVerify(exactly = 0) { fixture.pressure.reconcileAmbient(null) }
		assertEquals(fixture.pressureLease.identity, fixture.accepted[AmbientTrackingSource.PRESSURE])
		assertEquals(fixture.pressureLease.identity, fixture.hosted.value[AmbientTrackingSource.PRESSURE]?.identity)
		verify(exactly = 1) { fixture.reporter.invalidateAmbient(AmbientTrackingSource.ACTIVITY) }
		coVerify(exactly = 1) { fixture.retries.schedule(match { debt ->
			debt.failures.single().source == AmbientTrackingSource.ACTIVITY }) }
	}

	@Test
	fun `losing the Pressure host retires only Pressure and requests repair`() = runTest {
		val fixture = Fixture(automatic = true)
		fixture.owner.reconcile(fixture.activityLease)
		fixture.owner.reconcile(fixture.pressureLease)
		fixture.hosted.value = emptyMap()

		fixture.owner.refreshActivePlans()

		assertFalse(fixture.accepted.containsKey(AmbientTrackingSource.PRESSURE))
		assertEquals(fixture.activityLease.identity, fixture.accepted[AmbientTrackingSource.ACTIVITY])
		assertEquals(setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationOwner.ALL_DAY_TRACKING), fixture.activityOwners)
		coVerify(exactly = 1) { fixture.pressure.reconcileAmbient(null) }
		coVerify(exactly = 0) { fixture.activity.clearDemand(any()) }
		verify(exactly = 1) { fixture.reporter.invalidateAmbient(AmbientTrackingSource.PRESSURE) }
		coVerify(exactly = 1) { fixture.retries.schedule(match { debt ->
			debt.failures.single().source == AmbientTrackingSource.PRESSURE }) }
	}

	@Test
	fun `invalid Pressure host retires exact provider before release and preserves Activity`() = runTest {
		val fixture = Fixture(automatic = true)
		fixture.owner.reconcile(fixture.activityLease)
		fixture.owner.reconcile(fixture.pressureLease)

		assertTrue(fixture.owner.retire(AmbientTrackingSource.PRESSURE, fixture.pressureLease,
			reconcileAfterRetirement = true))

		coVerifyOrder {
			fixture.broker.retireAmbientSourceDemand(fixture.pressureLease.identity, any(), any(), any())
			fixture.pressure.reconcileAmbient(null)
			fixture.host.release(fixture.pressureLease.identity)
		}
		assertEquals(fixture.activityLease.identity, fixture.accepted[AmbientTrackingSource.ACTIVITY])
		assertTrue(ActivityRegistrationOwner.ALL_DAY_TRACKING in fixture.activityOwners)
		coVerify(exactly = 0) { fixture.activity.clearDemand(any()) }
		coVerify(exactly = 1) { fixture.retries.schedule(match { debt ->
			debt.failures.single().source == AmbientTrackingSource.PRESSURE }) }
	}

	@Test
	fun `incomplete Pressure retirement keeps its host until physical cleanup succeeds`() = runTest {
		val fixture = Fixture()
		fixture.owner.reconcile(fixture.activityLease)
		fixture.owner.reconcile(fixture.pressureLease)
		coEvery { fixture.pressure.reconcileAmbient(any()) } returns
			SourceApplyResult.Failed(mockk(), retryable = true)

		assertFalse(fixture.owner.retire(AmbientTrackingSource.PRESSURE, fixture.pressureLease,
			reconcileAfterRetirement = true))

		assertEquals(fixture.pressureLease.identity, fixture.hosted.value[AmbientTrackingSource.PRESSURE]?.identity)
		assertTrue(ActivityRegistrationOwner.ALL_DAY_TRACKING in fixture.activityOwners)
		coVerify(exactly = 0) { fixture.host.release(any()) }
		coVerify(exactly = 1) { fixture.retries.schedule(match { debt ->
			debt.failures.single().source == AmbientTrackingSource.PRESSURE }) }
		coEvery { fixture.pressure.reconcileAmbient(null) } returns SourceApplyResult.Applied(mockk())

		assertTrue(fixture.owner.retire(AmbientTrackingSource.PRESSURE, fixture.pressureLease,
			reconcileAfterRetirement = true))
		assertTrue(fixture.hosted.value.isEmpty())
		coVerify(exactly = 1) { fixture.host.release(fixture.pressureLease.identity) }
	}

	@Test
	fun `advancing a session lease keeps all-day Pressure available to resume after End`() = runTest {
		val fixture = Fixture(realPlanning = true, sharedPressure = true, applicationScope = backgroundScope)
		var captureLease: Long? = null
		coEvery { fixture.broker.authorizationDemands(SourceKind.PRESSURE) } answers {
			buildList {
				if (fixture.accepted.containsKey(AmbientTrackingSource.PRESSURE)) {
					add(fixture.pressureDemand(capture = false))
				}
				captureLease?.let { add(fixture.pressureDemand(capture = true, leaseGeneration = it)) }
			}
		}
		assertEquals(AmbientSourceOperationalState.READY, fixture.owner.reconcile(fixture.pressureLease).state)
		val host = fixture.hosted.value.getValue(AmbientTrackingSource.PRESSURE)
		val plan = PressurePlan(2L, true, 100_000, 0, 5_000L)
		val claim = SourceRuntimeClaim(SourceKind.PRESSURE, "start", 1, 1L, "session", "run")
		captureLease = 1L
		assertIs<SourceStartResult.Started>(fixture.pressure.start(claim, plan, fixture.pressureSink))
		captureLease = 2L

		fixture.owner.refreshActivePlans()

		assertEquals(fixture.pressureLease.identity, fixture.accepted[AmbientTrackingSource.PRESSURE])
		assertSame(host, fixture.hosted.value.getValue(AmbientTrackingSource.PRESSURE))
		coVerify(exactly = 0) { fixture.broker.retireAmbientSourceDemand(any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.host.release(any()) }
		coVerify(exactly = 1) { fixture.retries.schedule(match {
			it.failures.single().source == AmbientTrackingSource.PRESSURE }) }
		val successor = claim.copy(actionId = "reconfigure", leaseGeneration = 2L)
		assertIs<SourceApplyResult.Applied>(fixture.pressure.reconfigure(successor, plan, fixture.pressureSink))
		captureLease = null
		assertIs<OwnedSourceShutdown.Released>(fixture.pressure.shutdownIfOwned(successor,
			SessionCutoff("session", 20L, 30L, 100L)))
		fixture.owner.refreshActivePlans()
		coVerify(exactly = 1) { fixture.physicalPressure.quiesceShared(any()) }
		coVerify(exactly = 2) { fixture.physicalPressure.reconfigure(any<PressurePlan>(), fixture.pressureSink) }
		assertEquals(fixture.pressureLease.identity, fixture.accepted[AmbientTrackingSource.PRESSURE])
	}

	@Test
	fun `retryable initial Pressure activation preserves its demand without claiming readiness`() = runTest {
		val fixture = Fixture()
		coEvery { fixture.pressure.reconcileAmbient(match { it?.enabled == true }) } returns
			SourceApplyResult.Failed(mockk(), retryable = true)

		val pending = fixture.owner.reconcile(fixture.pressureLease)

		assertEquals(AmbientSourceOperationalState.WAITING, pending.state)
		assertEquals(fixture.pressureLease.purposeLeaseIdentity, pending.lastIdentity)
		assertEquals(fixture.pressureLease.identity, fixture.accepted[AmbientTrackingSource.PRESSURE])
		assertEquals(fixture.pressureLease.identity, fixture.hosted.value[AmbientTrackingSource.PRESSURE]?.identity)
		coVerify(exactly = 0) { fixture.pressure.reconcileAmbient(null) }
		verify(exactly = 0) { fixture.reporter.invalidateAmbient(any()) }
		coVerify(exactly = 1) { fixture.retries.schedule(any()) }
		coEvery { fixture.pressure.reconcileAmbient(match { it?.enabled == true }) } returns
			SourceApplyResult.Applied(mockk())
		assertEquals(AmbientSourceOperationalState.READY, fixture.owner.reconcile(fixture.pressureLease).state)
	}

	@Test
	fun `nonretryable Pressure refresh failure still retires its demand and host`() = runTest {
		val fixture = Fixture()
		fixture.owner.reconcile(fixture.pressureLease)
		coEvery { fixture.pressure.reconcileAmbient(match { it?.enabled == true }) } returns
			SourceApplyResult.Failed(mockk(), retryable = false)

		fixture.owner.refreshActivePlans()

		assertFalse(fixture.accepted.containsKey(AmbientTrackingSource.PRESSURE))
		assertTrue(fixture.hosted.value.isEmpty())
		coVerify(exactly = 1) { fixture.pressure.reconcileAmbient(null) }
		coVerify(exactly = 1) { fixture.host.release(fixture.pressureLease.identity) }
	}

	@Test
	fun `retryable Activity refresh preserves all-day and automatic owners`() = runTest {
		val fixture = Fixture(automatic = true)
		fixture.owner.reconcile(fixture.activityLease)
		coEvery { fixture.activity.setDemand(ActivityRegistrationOwner.ALL_DAY_TRACKING, any()) } returns
			ActivityRegistrationResult(ActivityRegistrationStatus.FAILED, fixture.activitySnapshot(), retryable = true)

		fixture.owner.refreshActivePlans()

		assertEquals(fixture.activityLease.identity, fixture.accepted[AmbientTrackingSource.ACTIVITY])
		assertEquals(setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationOwner.ALL_DAY_TRACKING), fixture.activityOwners)
		coVerify(exactly = 0) { fixture.activity.clearDemand(any()) }
		coVerify(exactly = 0) { fixture.broker.retireAmbientSourceDemand(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.retries.schedule(match {
			it.failures.single().source == AmbientTrackingSource.ACTIVITY }) }
	}

	@Test
	fun `a revoked sensor choice still retires after a retryable Pressure refresh`() = runTest {
		val fixture = Fixture()
		fixture.owner.reconcile(fixture.pressureLease)
		coEvery { fixture.pressure.reconcileAmbient(match { it?.enabled == true }) } returns
			SourceApplyResult.Failed(mockk(), retryable = true)
		fixture.owner.refreshActivePlans()
		assertTrue(fixture.accepted.containsKey(AmbientTrackingSource.PRESSURE))

		fixture.params.value = fixture.params.value.copy(sourcePolicyRevision = 3L, barometerEnabled = false)
		fixture.owner.refreshActivePlans()

		assertFalse(fixture.accepted.containsKey(AmbientTrackingSource.PRESSURE))
		assertTrue(fixture.hosted.value.isEmpty())
		coVerify(exactly = 1) { fixture.pressure.reconcileAmbient(null) }
	}

	@Test
	fun `revoking Activity permission retires only its all-day owner`() = runTest {
		val fixture = Fixture(automatic = true)
		fixture.owner.reconcile(fixture.activityLease)
		fixture.owner.reconcile(fixture.pressureLease)
		Shadows.shadowOf(fixture.context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

		fixture.owner.refreshActivePlans()

		assertFalse(fixture.accepted.containsKey(AmbientTrackingSource.ACTIVITY))
		assertEquals(setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR), fixture.activityOwners)
		assertEquals(fixture.pressureLease.identity, fixture.accepted[AmbientTrackingSource.PRESSURE])
		coVerify(exactly = 0) { fixture.pressure.reconcileAmbient(null) }
	}

	@Test
	fun `cancelled sensor refresh propagates without scheduling provider recovery`() = runTest {
		val fixture = Fixture()
		fixture.owner.reconcile(fixture.activityLease)
		fixture.owner.reconcile(fixture.pressureLease)
		val cancelled = CancellationException("Refresh cancelled")
		coEvery { fixture.activity.setDemand(ActivityRegistrationOwner.ALL_DAY_TRACKING, any()) } throws cancelled

		assertSame(cancelled, assertFailsWith<CancellationException> { fixture.owner.refreshActivePlans() })

		coVerify(exactly = 0) { fixture.retries.schedule(any()) }
		verify(exactly = 0) { fixture.reporter.invalidateAmbient(any()) }
		coVerify(exactly = 1) { fixture.pressure.reconcileAmbient(match { it?.enabled == true }) }
		assertEquals(setOf(AmbientTrackingSource.ACTIVITY, AmbientTrackingSource.PRESSURE), fixture.accepted.keys)
	}

	@Test
	fun `authorized all-day Pressure adapts to thermal pressure and recovers without changing its owner`() = runTest {
		val fixture = Fixture(realPlanning = true)
		fixture.params.value = fixture.params.value.copy(activityEnabled = false,
			balanceProfileVersion = 1, overallBalanceLevel = 3, locationProfileVersion = 1, locationBalanceLevel = 3)
		val power = fixture.context.getSystemService(PowerManager::class.java)
		val shadowPower = Shadows.shadowOf(power)
		shadowPower.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
		Shadows.shadowOf(fixture.context.getSystemService(BatteryManager::class.java))
			.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
		val contract = SourceDemandContractFactory.forProfile(SourceKind.PRESSURE, 1, 3,
			DirectSourceDemandPurpose.AMBIENT_PRODUCT)
		val demand = SourceDemandEntity(demandId = "pressure-demand",
			consumerId = ambientSourceConsumerId(SourceKind.PRESSURE),
			sourceKind = SourceKind.PRESSURE.stableCode, purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			logicalTrackingId = null, serviceRunId = null, manifestRevision = null, lifecycleLeaseGeneration = null,
			sourcePolicyRevision = 2L, consentEpoch = 3L, persistenceEligible = true, qosCode = 2,
			minimumAcquisitionSpec = contract.encodeFloor(), adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
			maximumAgeMs = contract.maximumProviderItemAgeMs, desiredLatencyMs = contract.targetPlanningLatencyMs,
			requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs, requestedBootId = "boot",
			requestedElapsedRealtimeNanos = 1L, requestedAtMs = 1L, status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null)
		coEvery { fixture.broker.authorizationDemands(SourceKind.PRESSURE) } returns listOf(demand)
		val applied = mutableListOf<PressurePlan>()
		coEvery { fixture.pressure.reconcileAmbient(any()) } coAnswers {
			firstArg<PressurePlan?>()?.let(applied::add)
			SourceApplyResult.Applied(mockk())
		}

		assertEquals(AmbientSourceOperationalState.READY, fixture.owner.reconcile(fixture.pressureLease).state)
		val host = fixture.hosted.value.getValue(AmbientTrackingSource.PRESSURE)
		shadowPower.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
		fixture.owner.refreshActivePlans()
		shadowPower.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
		fixture.owner.refreshActivePlans()

		assertEquals(3, applied.size)
		assertTrue(applied.all(PressurePlan::enabled))
		assertTrue(applied[1].hardwareSamplePeriodMicros > applied[0].hardwareSamplePeriodMicros)
		assertTrue(applied[1].maximumReportLatencyMicros > applied[0].maximumReportLatencyMicros)
		assertEquals(applied[0], applied[2])
		assertEquals(setOf(AmbientTrackingSource.PRESSURE), fixture.accepted.keys)
		assertEquals(fixture.pressureLease.identity, fixture.accepted[AmbientTrackingSource.PRESSURE])
		assertSame(host, fixture.hosted.value.getValue(AmbientTrackingSource.PRESSURE))
		coVerify(exactly = 1) { fixture.host.acquire(fixture.pressureLease.identity) }
		coVerify(exactly = 0) { fixture.host.release(any()) }
		coVerify(exactly = 0) { fixture.retries.schedule(any()) }
	}

	private class Fixture(
		automatic: Boolean = false,
		realPlanning: Boolean = false,
		sharedPressure: Boolean = false,
		applicationScope: CoroutineScope? = null,
	) {
		val context = RuntimeEnvironment.getApplication()
		val broker = mockk<SourceBroker>()
		val callers = mockk<SourceCallerDemandDispatcher>()
		val activity = mockk<ActivityRegistrationArbiter>()
		val pressure: SharedPressureSourceController
		val physicalPressure = mockk<PressureSourceRuntime>()
		val pressureSink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		val host = mockk<BackgroundLocationHost>()
		val settings = mockk<TrackingParamsRepository>()
		val factory = if (realPlanning) {
			SemanticAcquisitionPlanFactory()
		} else {
			mockk<SemanticAcquisitionPlanFactory>()
		}
		val reporter = mockk<TrackingPurposeAvailabilityReporter>(relaxed = true)
		val retries = mockk<TrackingPurposeReconciliationRetryScheduler>(relaxed = true)
		val params = MutableStateFlow(TrackingParamsState(sourcePolicyRevision = 2L,
			locationEnabled = false, stepsEnabled = false, activityEnabled = true, barometerEnabled = true,
			wifiEnabled = false, cellEnabled = false, autoTrackingMode = 0))
		val accepted = mutableMapOf<AmbientTrackingSource, AmbientReconciliationIdentity>()
		val activityOwners = mutableSetOf<ActivityRegistrationOwner>().apply {
			if (automatic) {
				add(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
			}
		}
		val hosted = MutableStateFlow<Map<AmbientTrackingSource, BackgroundLocationHostResult.Active>>(emptyMap())
		val activityLease = lease(AmbientTrackingSource.ACTIVITY)
		val pressureLease = lease(AmbientTrackingSource.PRESSURE)
		var afterDispatch: () -> Unit = {}
		private val scope = LocationMotionScope("sensor-test", "boot", 1L, "host", setOf(LocationMotionPurpose.BACKGROUND))
		val owner: AmbientSensorPurposeOwner

		init {
			Shadows.shadowOf(context).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
			every { settings.data } returns params
			if (!realPlanning) {
				every { factory.create(any(), any(), any(), any()) } returns AcquisitionPlanRevision(
					2L, "sensor-test", 1L, mapOf(
						SourceKind.ACTIVITY to ActivityPlan(2L, ActivityMode.CONTINUOUS_RECOGNITION, 30_000L, 50, emptySet()),
						SourceKind.PRESSURE to PressurePlan(2L, true, 200_000, 60_000_000, 60_000L)))
			}
			coEvery { broker.authorizationDemands(any()) } returns emptyList()
			coEvery { callers.dispatchAmbientSource(any()) } coAnswers {
				val request = firstArg<AmbientSourceDemandDispatchRequest>()
				accepted[request.identity.source] = request.identity
				afterDispatch()
				GuardedPurposeDemandResult.Applied(AmbientSourceDemandResult.Active(mockk()), null)
			}
			coEvery { broker.currentAmbientSourceIdentity(any()) } coAnswers {
				accepted.values.singleOrNull { it.source.canonicalSource.stableCode == firstArg<SourceKind>().stableCode }
			}
			coEvery { broker.retireAmbientSourceDemand(any(), any(), any(), any()) } coAnswers {
				val identity = firstArg<AmbientReconciliationIdentity>()
				val current = accepted[identity.source]
				when {
					current == null -> true
					current != identity -> false
					else -> { accepted.remove(identity.source); true }
				}
			}
			coEvery { activity.setDemand(any(), any()) } coAnswers {
				activityOwners += firstArg<ActivityRegistrationOwner>()
				ActivityRegistrationResult(ActivityRegistrationStatus.APPLIED, activitySnapshot())
			}
			coEvery { activity.clearDemand(any()) } coAnswers {
				activityOwners -= firstArg<ActivityRegistrationOwner>()
				ActivityRegistrationResult(ActivityRegistrationStatus.APPLIED, activitySnapshot())
			}
			pressure = if (sharedPressure) {
				val sinks = mockk<DurableSourceEventSinkFactory>()
				every { sinks.unbound } returns pressureSink
				every { physicalPressure.capabilities } returns MutableStateFlow(SourceCapabilities(true, true, true, null, null))
				coEvery { physicalPressure.reconfigure(any<PressurePlan>(), pressureSink) } returns
					SourceApplyResult.Applied(mockk())
				coEvery { physicalPressure.reconfigure(any<SourceRuntimeClaim>(), any(), pressureSink) } returns
					SourceApplyResult.Applied(mockk())
				coEvery { physicalPressure.quiesceShared(any()) } returns SourceStopAck(SourceKind.PRESSURE,
					SourceInstanceId("pressure"), 1L, 2L, 5L, 5L, 50L, 0L, null, null,
					RegistrationRemovalOutcome.REMOVED, ProviderFlushOutcome.COMPLETE,
					ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER, true, SourceStopStatus.COMPLETE)
				SharedPressureSourceController(physicalPressure, broker, sinks, reporter, retries,
					requireNotNull(applicationScope))
			} else {
				mockk<SharedPressureSourceController>().also {
					coEvery { it.reconcileAmbient(any()) } returns SourceApplyResult.Applied(mockk())
				}
			}
			every { host.leases } returns hosted
			coEvery { host.acquire(any()) } coAnswers {
				val identity = firstArg<AmbientReconciliationIdentity>()
				BackgroundLocationHostResult.Active(identity, scope).also { hosted.value += identity.source to it }
			}
			coEvery { host.release(any()) } coAnswers {
				val identity = firstArg<AmbientReconciliationIdentity>()
				if (hosted.value[identity.source]?.identity == identity) {
					hosted.value -= identity.source; true
				} else {
					false
				}
			}
			owner = AmbientSensorPurposeOwner(context, broker, callers, activity, pressure, host, settings,
				factory, if (realPlanning) {
					SourcePlanResolver()
				} else {
					mockk<SourcePlanResolver>()
				},
				if (realPlanning) {
					CollectionMotionController()
				} else {
					mockk<CollectionMotionController>()
				},
				if (realPlanning) {
					BatteryAwarePolicy(context)
				} else {
					mockk<BatteryAwarePolicy>()
				},
				object : BootClockDomainProvider { override fun current() = "boot" }, reporter, retries)
		}

		fun activitySnapshot() = ActivityRegistrationSnapshot(activityOwners.isNotEmpty(), null,
			activityOwners.toSet(), 30, emptySet())

		fun pressureDemand(capture: Boolean, leaseGeneration: Long = 1L): SourceDemandEntity {
			val contract = SourceDemandContractFactory.forProfile(SourceKind.PRESSURE, 1, 3,
				if (capture) {
					DirectSourceDemandPurpose.SESSION_CAPTURE
				} else {
					DirectSourceDemandPurpose.AMBIENT_PRODUCT
				})
			return SourceDemandEntity(demandId = if (capture) {
				"session-pressure"
			} else {
				"ambient-pressure"
			},
				consumerId = if (capture) {
					"session:session"
				} else {
					ambientSourceConsumerId(SourceKind.PRESSURE)
				},
				sourceKind = SourceKind.PRESSURE.stableCode,
				purpose = if (capture) {
					SourceBrokerPurpose.SESSION_CAPTURE
				} else {
					SourceBrokerPurpose.AMBIENT_PRODUCT
				},
				logicalTrackingId = "session".takeIf { capture }, serviceRunId = "run".takeIf { capture },
				manifestRevision = 1L.takeIf { capture }, lifecycleLeaseGeneration = leaseGeneration.takeIf { capture },
				sourcePolicyRevision = 2L, consentEpoch = 3L, persistenceEligible = true, qosCode = 2,
				minimumAcquisitionSpec = contract.encodeFloor(), adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
				maximumAgeMs = contract.maximumProviderItemAgeMs, desiredLatencyMs = contract.targetPlanningLatencyMs,
				requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs, requestedBootId = "boot",
				requestedElapsedRealtimeNanos = 1L, requestedAtMs = 1L, status = SourceDemandEntity.STATUS_ACTIVE,
				retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null)
		}

		private fun lease(source: AmbientTrackingSource) = AmbientReconciliationLease(
			AmbientReconciliationIdentity(source, 2L, 3L, 4L, 5L,
				"${source.name.lowercase()}-owner", 1L, null, "retention", 1L))
	}
}
