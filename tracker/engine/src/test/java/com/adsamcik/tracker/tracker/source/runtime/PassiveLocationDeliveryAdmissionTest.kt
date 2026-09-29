package com.adsamcik.tracker.tracker.source.runtime

import android.location.Location
import com.adsamcik.tracker.shared.base.startup.TrackingAdmissionStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassiveLocationDeliveryAdmissionTest {
	private val identity = PassiveLocationIdentity(LocationBackend.FRAMEWORK, "instance", 3,
		"boot", "a".repeat(64), 7)

	@Test
	fun `only independently authenticated fixes reach unbound durable ingress`() = runTest {
		val startup = mockk<TrackingStartupGate>()
		coEvery { startup.reconcileAdmission(any()) } returns TrackingAdmissionStartupResult.Ready
		every { startup.permitsNewCollection } returns true
		every { startup.collectionResumeBoundary } returns null
		val device = mockk<LocationDeviceStateProvider>()
		every { device.snapshot() } returns state()
		val repository = mockk<SourceRegistrationRepository>()
		val allowed = PassiveLocationAdmission(LocationDeliveryAttribution(
			SourceInstanceId("instance"), 3, "a".repeat(64), 5,
			8L, "b".repeat(64), 9, "boot", 7), approximate = false)
		coEvery { repository.passiveLocationAdmissionAt(identity, 100L) } returns allowed
		coEvery { repository.passiveLocationAdmissionAt(identity, 200L) } returns null
		val sink = mockk<SourceEventSink>()
		coEvery { sink.admit(any<SourceDeliveryCandidate>()) } returns
			SourceDeliveryAdmissionHandoff.Durable(listOf(1L), 1L)
		val factory = mockk<DurableSourceEventSinkFactory>()
		every { factory.unbound } returns sink
		val subject = PassiveLocationDeliveryAdmission(startup, device, repository, factory)

		val outcome = subject.admit(identity, listOf(fix(100), fix(200)), 300, 1_000)

		assertEquals(1, outcome.admittedOrDuplicate)
		assertEquals(1, outcome.unverified)
	}

	@Test
	fun `background permission loss blocks a cold delivery before storage admission`() = runTest {
		val startup = mockk<TrackingStartupGate>()
		coEvery { startup.reconcileAdmission(any()) } returns TrackingAdmissionStartupResult.Ready
		every { startup.permitsNewCollection } returns true
		val device = mockk<LocationDeviceStateProvider>()
		every { device.snapshot() } returns state().copy(backgroundLocationPermission = false)
		val repository = mockk<SourceRegistrationRepository>()
		val factory = mockk<DurableSourceEventSinkFactory>()
		every { factory.unbound } returns mockk()

		val outcome = PassiveLocationDeliveryAdmission(startup, device, repository, factory)
			.admit(identity, listOf(fix(100)), 300, 1_000)

		assertEquals(0, outcome.admittedOrDuplicate)
		assertEquals(1, outcome.unverified)
	}

	@Test
	fun `cold receiver bounds an oversized provider batch and reports excluded fixes`() = runTest {
		val startup = mockk<TrackingStartupGate>()
		coEvery { startup.reconcileAdmission(any()) } returns TrackingAdmissionStartupResult.Ready
		every { startup.permitsNewCollection } returns true
		every { startup.collectionResumeBoundary } returns null
		val device = mockk<LocationDeviceStateProvider>()
		every { device.snapshot() } returns state()
		val repository = mockk<SourceRegistrationRepository>()
		coEvery { repository.passiveLocationAdmissionAt(identity, any()) } returns null
		val sink = mockk<SourceEventSink>()
		val factory = mockk<DurableSourceEventSinkFactory>()
		every { factory.unbound } returns sink

		val outcome = PassiveLocationDeliveryAdmission(startup, device, repository, factory)
			.admit(identity, (1L..65L).map(::fix), 100L, 1_000L)

		assertEquals(0, outcome.admittedOrDuplicate)
		assertEquals(65, outcome.unverified)
		assertEquals(true, outcome.overflow)
		coVerify(exactly = 0) { repository.passiveLocationAdmissionAt(identity, 65L) }
		coVerify(exactly = 0) { sink.admit(any<SourceDeliveryCandidate>()) }
	}

	private fun fix(elapsedNanos: Long) = Location("passive").apply {
		latitude = 50.0 + elapsedNanos / 10_000.0
		longitude = 14.0
		accuracy = 10f
		time = 900L
		elapsedRealtimeNanos = elapsedNanos
	}

	private fun state() = LocationDeviceState(apiLevel = 34,
		locationFeatureAvailable = true, locationServicesEnabled = true,
		coarsePermission = true, finePermission = true, backgroundLocationPermission = true,
		fusedProviderAvailable = false, foregroundServiceLocationCapability = false,
		backgroundForegroundServiceStartLegal = false)
}
