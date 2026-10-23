package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.Manifest
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.policy.BatteryAwarePolicy
import com.adsamcik.tracker.tracker.policy.BatteryPressure
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.api.TrackingPurposeReconciliationRetryScheduler
import com.adsamcik.tracker.tracker.service.BackgroundLocationHost
import com.adsamcik.tracker.tracker.service.BackgroundLocationHostResult
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientLocationPurposeOwnerTest {
	private val broker = mockk<SourceBroker>()
	private val caller = mockk<SourceCallerDemandDispatcher>()
	private val host = mockk<BackgroundLocationHost>()
	private val runtime = mockk<SharedLocationSourceController>()
	private val settings = mockk<TrackingParamsRepository>()
	private val reporter = mockk<TrackingPurposeAvailabilityReporter>(relaxed = true)
	private val retries = mockk<TrackingPurposeReconciliationRetryScheduler>(relaxed = true)
	private val identity = AmbientReconciliationIdentity(AmbientTrackingSource.LOCATION, 2, 3, 4, 5,
		"owner", 1, null, "retention", 1)
	private val lease = AmbientReconciliationLease(identity)

	private fun subject(mode: LocationBackgroundMode = LocationBackgroundMode.ADAPTIVE,
		context: Context = mockk(relaxed = true), motion: CollectionMotionController = mockk(),
		battery: BatteryAwarePolicy = mockk()): AmbientLocationPurposeOwner {
		every { settings.data } returns MutableStateFlow(TrackingParamsState(sourcePolicyRevision = 2,
			ambientLocationEnabled = true, locationEnabled = false, locationProfileVersion = 1,
			locationBalanceLevel = 3, locationBackgroundMode = mode))
		every { host.active } returns MutableStateFlow(null)
		coEvery { caller.dispatchAmbientSource(any()) } returns GuardedPurposeDemandResult.Applied(
			AmbientSourceDemandResult.Active(mockk()), null)
		return AmbientLocationPurposeOwner(context, broker, caller, host, runtime,
			settings, motion, SourcePlanResolver(), object : BootClockDomainProvider {
				override fun current() = "boot"
			}, reporter, retries, battery)
	}

	@Test
	fun `passive only uses the shared owner without acquiring a foreground host`() = runTest {
		val context = RuntimeEnvironment.getApplication()
		Shadows.shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_BACKGROUND_LOCATION)
		val demand = mockk<SourceDemandEntity>()
		every { demand.consumerId } returns "ambient:location"
		every { demand.sourcePolicyRevision } returns 2L
		every { demand.consentEpoch } returns 3L
		every { demand.desiredLatencyMs } returns 120_000L
		coEvery { broker.authorizationDemands(any()) } returns listOf(demand)
		coEvery { runtime.reconcileAmbientJoin(any(), 2) } returns
			AmbientLocationRuntimeJoinResult.Active(null, null)
		val owner = subject(LocationBackgroundMode.PASSIVE_ONLY, context)

		val result = owner.reconcile(lease)

		assertEquals(AmbientSourceOperationalState.READY, result.state)
		assertEquals(AmbientAcquisitionMechanism.PASSIVE_LOCATION, result.mechanism)
		coVerify(exactly = 0) { host.acquire(any()) }
		coVerify(exactly = 1) { runtime.reconcileAmbientJoin(match {
			it?.passiveSystemDelivery == true && it.mode == LocationMode.PASSIVE &&
				it.minimumUpdateIntervalMs == 120_000L && it.requestedIntervalMs == 120_000L
		}, 2) }
	}

	@Test
	fun `adaptive all day owner keeps a useful unknown request then restores fresh movement cadence`() = runTest {
		val context = RuntimeEnvironment.getApplication()
		Shadows.shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_BACKGROUND_LOCATION)
		val motion = CollectionMotionController().also { it.configureScope("background", "boot", 0L) }
		val battery = mockk<BatteryAwarePolicy> { every { currentPressure() } returns BatteryPressure.NONE }
		val owner = subject(context = context, motion = motion, battery = battery)
		val hosted = BackgroundLocationHostResult.Active(identity, mockk())
		every { host.active } returns MutableStateFlow(hosted)
		coEvery { host.acquire(identity) } returns hosted
		coEvery { broker.authorizationDemands(SourceKind.LOCATION) } returns listOf(adaptiveDemand())
		val plans = mutableListOf<LocationPlan>()
		coEvery { runtime.reconcileAmbientJoin(any(), 2L) } coAnswers {
			plans += requireNotNull(firstArg<LocationPlan?>())
			AmbientLocationRuntimeJoinResult.Active(null, null)
		}
		assertEquals(AmbientSourceOperationalState.READY, owner.reconcile(lease).state)
		assertEquals(LocationMode.HIGH_ACCURACY, plans.single().mode)
		assertEquals(30_000L, plans.single().requestedIntervalMs)
		motion.onMotionAssistance("background", "boot", 1L, 1L, StableActivityTypeCode.WALKING, 0)
		owner.refreshActivePlan()
		assertEquals(LocationMode.HIGH_ACCURACY, plans.last().mode)
		assertEquals(10_000L, plans.last().requestedIntervalMs)
		coVerify(exactly = 1) { host.acquire(identity) }
	}

	private fun adaptiveDemand(): SourceDemandEntity {
		val contract = SourceDemandContractFactory.forProfile(SourceKind.LOCATION, 1, 3,
			DirectSourceDemandPurpose.AMBIENT_PRODUCT)
		return SourceDemandEntity(demandId = "background", consumerId = "ambient:location", sourceKind = 1,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, logicalTrackingId = null, serviceRunId = null,
			manifestRevision = null, lifecycleLeaseGeneration = null, sourcePolicyRevision = 2L, consentEpoch = 3L,
			persistenceEligible = true, qosCode = 2, minimumAcquisitionSpec = contract.encodeFloor(),
			adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
			maximumAgeMs = contract.maximumProviderItemAgeMs, desiredLatencyMs = contract.targetPlanningLatencyMs,
			requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
			requestedBootId = "boot", requestedElapsedRealtimeNanos = 0L, requestedAtMs = 0L,
			status = SourceDemandEntity.STATUS_ACTIVE, retireBootId = null, retireElapsedRealtimeNanos = null,
			retiredAtMs = null)
	}

	@Test
	fun `stale purpose lease cannot acquire a host or change provider work`() = runTest {
		val owner = subject()
		val result = owner.reconcile(AmbientReconciliationLease(identity.copy(policyRevision = 1)))
		assertEquals(AmbientSourceOperationalState.UNAVAILABLE, result.state)
		coVerify(exactly = 0) { caller.dispatchAmbientSource(any()) }
		coVerify(exactly = 0) { host.acquire(any()) }
		coVerify(exactly = 0) { runtime.reconcileAmbientJoin(any(), any()) }
	}

	@Test
	fun `host rejection retires exact demand before reporting unavailable`() = runTest {
		val owner = subject()
		coEvery { host.acquire(identity) } returns BackgroundLocationHostResult.Blocked("test-host-rejected")
		coEvery { broker.retireAmbientSourceDemand(identity, any(), any(), any()) } returns true
		coEvery { runtime.reconcileAmbientJoin(null, 2) } returns AmbientLocationRuntimeJoinResult.Inactive(null)
		assertEquals(AmbientSourceOperationalState.UNAVAILABLE, owner.reconcile(lease).state)
		coVerify(exactly = 1) { broker.retireAmbientSourceDemand(identity, any(), any(), any()) }
		coVerify(exactly = 1) { runtime.reconcileAmbientJoin(null, 2) }
		coVerify(exactly = 0) { host.release(any()) }
	}

	@Test
	fun `failed exact retirement cannot be reported as completed cleanup`() = runTest {
		val owner = subject()
		coEvery { broker.retireAmbientSourceDemand(identity, any(), any(), any()) } returns false
		assertEquals(false, owner.retire(lease))
		coVerify(exactly = 0) { runtime.reconcileAmbientJoin(any(), any()) }
		coVerify(exactly = 0) { host.release(any()) }
	}

	@Test
	fun `host rejection with failed retirement remains a reconciliation failure`() = runTest {
		val owner = subject()
		coEvery { host.acquire(identity) } returns BackgroundLocationHostResult.Blocked("test-host-rejected")
		coEvery { broker.retireAmbientSourceDemand(identity, any(), any(), any()) } returns false
		assertFailsWith<IllegalStateException> { owner.reconcile(lease) }
	}

	@Test
	fun `policy tick failure withdraws readiness and leaves reconciliation debt`() = runTest {
		val owner = subject()
		coEvery { host.acquire(identity) } returns BackgroundLocationHostResult.Blocked("test-host-rejected")
		coEvery { broker.retireAmbientSourceDemand(identity, any(), any(), any()) } returns false
		assertFailsWith<IllegalStateException> { owner.reconcile(lease) }
		coEvery { broker.retireAmbientSourceDemand(identity, any(), any(), any()) } throws
			IllegalStateException("authority temporarily unavailable")

		assertFailsWith<IllegalStateException> { owner.refreshActivePlan() }
		coVerify(exactly = 1) { reporter.invalidateAmbient(AmbientTrackingSource.LOCATION) }
		coVerify(exactly = 1) { retries.schedule(any()) }
	}
}
