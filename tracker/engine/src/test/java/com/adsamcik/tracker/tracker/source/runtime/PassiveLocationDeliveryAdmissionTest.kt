package com.adsamcik.tracker.tracker.source.runtime

import android.location.Location
import com.adsamcik.tracker.shared.base.startup.TrackingAdmissionStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceDeliveryIngress
import com.adsamcik.tracker.tracker.source.ingress.AdmissionResult
import com.adsamcik.tracker.tracker.source.ingress.DeliveryAdmissionResult
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
	fun `oversized cold delivery authenticates every fix and reports unavailable originals`() = runTest {
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
		coVerify(exactly = 1) { repository.passiveLocationAdmissionAt(identity, 65L) }
		coVerify(exactly = 0) { sink.admit(any<SourceDeliveryCandidate>()) }
	}

	@Test
	fun `oversized cold delivery retains all original one-fix identities and canonical ordering`() = runTest {
		val fixture = AdmissionFixture()
		val originals = (1L..129L).map(::fix)
		val expectedIdentities = originals.map { location ->
			locationDeliveryCandidate(listOf(location), listOf(location.elapsedRealtimeNanos),
				1_000L, 1_000L, fixture.allowed.attribution, false).identity.value
		}
		// The duplicate is distant from its original in input order and crosses a work boundary.
		val delivery = originals.asReversed() + Location(originals[64])

		val first = fixture.subject.admit(identity, delivery, 1_000L, 1_000L)
		val repeated = fixture.subject.admit(identity, originals, 2_000L, 2_000L)

		assertEquals(PassiveLocationDeliveryOutcome(129, 0), first)
		assertEquals(PassiveLocationDeliveryOutcome(129, 0), repeated)
		assertEquals(129, fixture.ingress.events.size)
		assertEquals(expectedIdentities + expectedIdentities,
			fixture.ingress.attempts.map { it.identity.value })
		assertEquals(expectedIdentities.map { SourceEventId("point:$it") },
			fixture.ingress.events.values.toList())
		assertTrue(fixture.ingress.attempts.all { it.units.size == 1 && it.units.single().unitIndex == 0 })
		assertEquals((1L..129L).toList() + (1L..129L).toList(),
			fixture.ingress.attempts.map { it.units.single().evidence.observedElapsedRealtimeNanos })
		assertTrue(fixture.ingress.attempts.all {
			val original = it.units.single().evidence
			original.source == SourceKind.LOCATION && original.sourceInstanceId == SourceInstanceId("instance") &&
				original.registrationGeneration == 3L && original.clockDomainId == "boot" &&
				original.capturedCollectedDataEpoch == 7L && original.logicalTrackingId == null &&
				original.serviceRunId == null
		})
	}

	@Test
	fun `invalid original at a work boundary does not discard healthy later fixes`() = runTest {
		val fixture = AdmissionFixture()
		val originals = (1L..129L).map(::fix)
		originals[64].latitude = 999.0

		val outcome = fixture.subject.admit(identity, originals.asReversed(), 1_000L, 1_000L)

		assertEquals(PassiveLocationDeliveryOutcome(128, 1), outcome)
		assertEquals((1L..129L).filter { it != 65L }, fixture.ingress.attempts.map {
			it.units.single().evidence.observedElapsedRealtimeNanos
		})
		assertEquals(128, fixture.ingress.events.size)
		coVerify(exactly = 0) { fixture.registrations.passiveLocationAdmissionAt(identity, 65L) }
		coVerify(exactly = 1) { fixture.registrations.passiveLocationAdmissionAt(identity, 129L) }
	}

	@Test
	fun `permission revoked after a full work chunk fences every later original`() = runTest {
		val fixture = AdmissionFixture()
		fixture.ingress.afterAdmission = { _, count ->
			if (count == 64) fixture.backgroundPermission = false
		}

		val outcome = fixture.subject.admit(identity, (1L..129L).map(::fix), 1_000L, 1_000L)

		assertEquals(PassiveLocationDeliveryOutcome(64, 65), outcome)
		assertEquals(64, fixture.ingress.events.size)
		assertEquals((1L..64L).toList(), fixture.ingress.attempts.map {
			it.units.single().evidence.observedElapsedRealtimeNanos
		})
		coVerify(exactly = 0) { fixture.registrations.passiveLocationAdmissionAt(identity, 65L) }
	}

	@Test
	fun `collection stop after a full work chunk fences every later original`() = runTest {
		val fixture = AdmissionFixture()
		fixture.ingress.afterAdmission = { _, count ->
			if (count == 64) fixture.collectionAllowed = false
		}

		val outcome = fixture.subject.admit(identity, (1L..129L).map(::fix), 1_000L, 1_000L)

		assertEquals(PassiveLocationDeliveryOutcome(64, 65), outcome)
		assertEquals(64, fixture.ingress.events.size)
		coVerify(exactly = 0) { fixture.registrations.passiveLocationAdmissionAt(identity, 65L) }
		coVerify(exactly = 0) { fixture.registrations.passiveLocationAdmissionAt(identity, 129L) }
	}

	@Test
	fun `reached ingress cancellation preserves the admitted prefix and prevents later calls`() = runTest {
		val fixture = AdmissionFixture()
		fixture.ingress.beforeAdmission = { _, attempt ->
			if (attempt == 65) throw CancellationException("receiver cancelled")
		}
		var returned = false
		val job = launch {
			fixture.subject.admit(identity, (1L..129L).map(::fix), 1_000L, 1_000L)
			returned = true
		}
		job.join()

		assertTrue(job.isCancelled)
		assertFalse(returned)
		assertEquals(64, fixture.ingress.events.size)
		assertEquals(65, fixture.ingress.attempts.size)
		coVerify(exactly = 1) { fixture.registrations.passiveLocationAdmissionAt(identity, 65L) }
		coVerify(exactly = 0) { fixture.registrations.passiveLocationAdmissionAt(identity, 66L) }
	}

	@Test
	fun `cooperative cancellation at a work boundary prevents the next registration lookup`() = runTest {
		val fixture = AdmissionFixture()
		fixture.ingress.afterAdmission = { _, count ->
			if (count == 64) currentCoroutineContext().cancel(CancellationException("receiver stopped"))
		}
		var returned = false
		val job = launch {
			fixture.subject.admit(identity, (1L..129L).map(::fix), 1_000L, 1_000L)
			returned = true
		}
		job.join()

		assertTrue(job.isCancelled)
		assertFalse(returned)
		assertEquals(64, fixture.ingress.attempts.size)
		assertEquals(64, fixture.ingress.events.size)
		coVerify(exactly = 0) { fixture.registrations.passiveLocationAdmissionAt(identity, 65L) }
	}

	@Test
	fun `existing five second receiver timeout cancels unfinished later admission`() = runTest {
		val fixture = AdmissionFixture()
		fixture.ingress.beforeAdmission = { _, attempt ->
			if (attempt == 65) delay(6_000L)
		}

		val outcome = withTimeoutOrNull(5_000L) {
			fixture.subject.admit(identity, (1L..129L).map(::fix), 1_000L, 1_000L)
		}

		assertNull(outcome)
		assertEquals(64, fixture.ingress.events.size)
		assertEquals(65, fixture.ingress.attempts.size)
		coVerify(exactly = 0) { fixture.registrations.passiveLocationAdmissionAt(identity, 66L) }
	}

	private inner class AdmissionFixture {
		var collectionAllowed = true
		var backgroundPermission = true
		val startup = mockk<TrackingStartupGate>()
		val device = mockk<LocationDeviceStateProvider>()
		val registrations = mockk<SourceRegistrationRepository>()
		val ingress = RecordingDeliveryIngress()
		val allowed = PassiveLocationAdmission(LocationDeliveryAttribution(
			SourceInstanceId("instance"), 3, "a".repeat(64), 5,
			8L, "b".repeat(64), 9, "boot", 7), approximate = false)

		init {
			coEvery { startup.reconcileAdmission(any()) } returns TrackingAdmissionStartupResult.Ready
			every { startup.permitsNewCollection } answers { collectionAllowed }
			every { startup.collectionResumeBoundary } returns null
			every { device.snapshot() } answers { state().copy(backgroundLocationPermission = backgroundPermission) }
			coEvery { registrations.passiveLocationAdmissionAt(identity, any()) } returns allowed
		}

		val subject = PassiveLocationDeliveryAdmission(startup, device, registrations,
			DurableSourceEventSinkFactory(ingress))
	}

	/** Recording ingress fixture; real adapter/sink/identity logic, no Room or provider evidence. */
	private class RecordingDeliveryIngress : DurableSourceIngress, DurableSourceDeliveryIngress {
		val attempts = mutableListOf<SourceDeliveryCandidate>()
		val events = linkedMapOf<String, SourceEventId>()
		var beforeAdmission: suspend (SourceDeliveryCandidate, Int) -> Unit = { _, _ -> }
		var afterAdmission: suspend (SourceDeliveryCandidate, Int) -> Unit = { _, _ -> }

		override suspend fun admit(delivery: SourceDeliveryCandidate): DeliveryAdmissionResult {
			attempts += delivery
			beforeAdmission(delivery, attempts.size)
			val existing = events[delivery.identity.value]
			val eventId = existing ?: SourceEventId("point:${delivery.identity.value}")
			if (existing == null) events[delivery.identity.value] = eventId
			val ordinal = events.keys.indexOf(delivery.identity.value).toLong() + 1L
			val unit = DeliveryAdmissionResult.AdmittedUnit(0, eventId, ordinal, ordinal)
			afterAdmission(delivery, events.size)
			return if (existing == null) DeliveryAdmissionResult.Admitted(listOf(unit))
				else DeliveryAdmissionResult.Duplicate(listOf(unit))
		}

		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): AdmissionResult =
			error("The passive adapter must retain its original one-fix delivery contract")

		override suspend fun committedBatch(afterOrdinal: Long, limit: Int): List<AdmittedSourceEvent<out SourcePayload>> =
			error("No projection drain is claimed by this admission fixture")

		override suspend fun committedSourceBatch(source: SourceKind, afterOrdinal: Long,
			throughOrdinal: Long, limit: Int): List<AdmittedSourceEvent<out SourcePayload>> =
			error("No projection drain is claimed by this admission fixture")

		override suspend fun checkpoint(consumer: String, ordinal: Long) =
			error("No projection checkpoint is claimed by this admission fixture")
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
