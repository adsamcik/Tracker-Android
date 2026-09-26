package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
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

	private fun subject(): AmbientLocationPurposeOwner {
		every { settings.data } returns MutableStateFlow(TrackingParamsState(sourcePolicyRevision = 2,
			ambientLocationEnabled = true, locationEnabled = false, locationProfileVersion = 1,
			locationBalanceLevel = 3, locationBackgroundMode = LocationBackgroundMode.ADAPTIVE))
		every { host.active } returns MutableStateFlow(null)
		coEvery { caller.dispatchAmbientLocation(any()) } returns GuardedPurposeDemandResult.Applied(
			AmbientLocationDemandResult.Active(mockk()), null)
		return AmbientLocationPurposeOwner(mockk<Context>(relaxed = true), broker, caller, host, runtime,
			settings, mockk(), SourcePlanResolver(), object : BootClockDomainProvider {
				override fun current() = "boot"
			}, reporter, retries, mockk())
	}

	@Test
	fun `stale purpose lease cannot acquire a host or change provider work`() = runTest {
		val owner = subject()
		val result = owner.reconcile(AmbientReconciliationLease(identity.copy(policyRevision = 1)))
		assertEquals(AmbientSourceOperationalState.UNAVAILABLE, result.state)
		coVerify(exactly = 0) { caller.dispatchAmbientLocation(any()) }
		coVerify(exactly = 0) { host.acquire(any()) }
		coVerify(exactly = 0) { runtime.reconcileAmbientJoin(any(), any()) }
	}

	@Test
	fun `host rejection retires exact demand before reporting unavailable`() = runTest {
		val owner = subject()
		coEvery { host.acquire(identity) } returns BackgroundLocationHostResult.Blocked("test-host-rejected")
		coEvery { broker.retireAmbientLocationDemand(identity, any(), any(), any()) } returns true
		coEvery { runtime.reconcileAmbientJoin(null, 2) } returns AmbientLocationRuntimeJoinResult.Inactive(null)
		assertEquals(AmbientSourceOperationalState.UNAVAILABLE, owner.reconcile(lease).state)
		coVerify(exactly = 1) { broker.retireAmbientLocationDemand(identity, any(), any(), any()) }
		coVerify(exactly = 1) { runtime.reconcileAmbientJoin(null, 2) }
		coVerify(exactly = 0) { host.release(any()) }
	}

	@Test
	fun `failed exact retirement cannot be reported as completed cleanup`() = runTest {
		val owner = subject()
		coEvery { broker.retireAmbientLocationDemand(identity, any(), any(), any()) } returns false
		assertEquals(false, owner.retire(lease))
		coVerify(exactly = 0) { runtime.reconcileAmbientJoin(any(), any()) }
		coVerify(exactly = 0) { host.release(any()) }
	}

	@Test
	fun `host rejection with failed retirement remains a reconciliation failure`() = runTest {
		val owner = subject()
		coEvery { host.acquire(identity) } returns BackgroundLocationHostResult.Blocked("test-host-rejected")
		coEvery { broker.retireAmbientLocationDemand(identity, any(), any(), any()) } returns false
		assertFailsWith<IllegalStateException> { owner.reconcile(lease) }
	}

	@Test
	fun `policy tick failure withdraws readiness and leaves reconciliation debt`() = runTest {
		val owner = subject()
		coEvery { host.acquire(identity) } returns BackgroundLocationHostResult.Blocked("test-host-rejected")
		coEvery { broker.retireAmbientLocationDemand(identity, any(), any(), any()) } returns false
		assertFailsWith<IllegalStateException> { owner.reconcile(lease) }
		coEvery { broker.retireAmbientLocationDemand(identity, any(), any(), any()) } throws
			IllegalStateException("authority temporarily unavailable")

		assertFailsWith<IllegalStateException> { owner.refreshActivePlan() }
		coVerify(exactly = 1) { reporter.invalidateAmbient(AmbientTrackingSource.LOCATION) }
		coVerify(exactly = 1) { retries.schedule(any()) }
	}
}
