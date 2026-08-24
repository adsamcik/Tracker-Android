package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.testing.fake.FakeLocationSource
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class LocationSourceDeliveryTest {
	@Test
	fun `permission gate observes revoke without waiting for reconciliation`() {
		var state = deviceState(coarsePermission = true, finePermission = false)
		val gate = LocationPermissionGate(object : LocationDeviceStateProvider {
			override fun snapshot(): LocationDeviceState = state
		})

		assertTrue(gate.allowsCurrentCallback())
		state = deviceState(coarsePermission = false, finePermission = false)
		assertEquals(false, gate.allowsCurrentCallback())
	}

	@Test
	fun `one provider callback becomes one atomic delivery in canonical provider order`() {
		val late = location("late", elapsedNanos = 900L, wallTimeMs = 9L)
		val early = location("early", elapsedNanos = 700L, wallTimeMs = 7L)

		val delivery = delivery(listOf(late, early), receivedElapsedNanos = 1_000L)

		assertEquals(listOf(0, 1), delivery.units.map { it.unitIndex })
		assertEquals(
			listOf("early", "late"),
			delivery.units.map { (it.evidence.payload as LocationFixPayload).provider },
		)
		assertTrue(delivery.units.all { it.evidence.sourceSequence == 0L })
	}

	@Test
	fun `reordered provider replay with duplicates has stable identity and total order`() {
		val first = location("z-provider", elapsedNanos = 700L, wallTimeMs = 7L)
		val duplicate = copyLocation(first)
		val second = location("a-provider", elapsedNanos = 700L, wallTimeMs = 7L)

		val original = delivery(listOf(first, second, duplicate), receivedElapsedNanos = 1_000L)
		val replay = delivery(
			listOf(copyLocation(duplicate), copyLocation(second), copyLocation(first)),
			receivedElapsedNanos = 1_500L,
		)

		assertEquals(original.identity, replay.identity)
		assertEquals(
			listOf("a-provider", "z-provider"),
			original.units.map { (it.evidence.payload as LocationFixPayload).provider },
		)
		assertEquals(original.units.map { it.evidence.payload }, replay.units.map { it.evidence.payload })
	}

	@Test
	fun `mixed poison preserves qualified subset and reports poison count`() {
		val valid = location("valid", elapsedNanos = 700L, wallTimeMs = 7L)
		val missingTime = location("missing", elapsedNanos = 0L, wallTimeMs = 8L)
		val future = location("future", elapsedNanos = 1_001L, wallTimeMs = 9L)
		val invalid = location("invalid", elapsedNanos = 600L, wallTimeMs = 6L).apply {
			latitude = Double.NaN
		}

		val partition = partitionLocationBatch(
			listOf(future, valid, invalid, missingTime),
			receivedElapsedRealtimeNanos = 1_000L,
			cutoffElapsedRealtimeNanos = null,
		)

		assertEquals(3, partition.poisonCount)
		assertEquals(0, partition.postCutoffCount)
		assertEquals(listOf("valid"), partition.eligible.map { it.location.provider })
	}

	@Test
	fun `cutoff partitions pre-cutoff units without classifying later units as poison`() {
		val before = location("before", elapsedNanos = 700L, wallTimeMs = 7L)
		val after = location("after", elapsedNanos = 900L, wallTimeMs = 9L)

		val partition = partitionLocationBatch(
			listOf(after, before),
			receivedElapsedRealtimeNanos = 1_000L,
			cutoffElapsedRealtimeNanos = 800L,
		)

		assertEquals(0, partition.poisonCount)
		assertEquals(1, partition.postCutoffCount)
		assertEquals(listOf("before"), partition.eligible.map { it.location.provider })
	}

	@Test
	fun `missing and future provider elapsed times cannot manufacture observed time`() {
		val missing = location("gps", elapsedNanos = 0L, wallTimeMs = 7L)
		val future = location("gps", elapsedNanos = 1_001L, wallTimeMs = 7L)

		assertThrows(IllegalArgumentException::class.java) {
			uncheckedDelivery(missing, observedNanos = 0L, receivedElapsedNanos = 1_000L)
		}
		assertThrows(IllegalArgumentException::class.java) {
			uncheckedDelivery(future, observedNanos = 1_001L, receivedElapsedNanos = 1_000L)
		}
	}

	@Test
	fun `bounded lane rejects overflow explicitly and drains accepted order`() = runTest {
		val lane = LocationCallbackLane(capacity = 2)
		val received = mutableListOf<Long>()

		assertEquals(LocationLaneOffer.ACCEPTED, lane.offer(rawBatch(1L)))
		assertEquals(LocationLaneOffer.ACCEPTED, lane.offer(rawBatch(2L)))
		assertEquals(LocationLaneOffer.CAPACITY_EXHAUSTED, lane.offer(rawBatch(3L)))
		lane.close()
		val consumer = launch { lane.consume { batch -> received += batch.callbackSequence } }
		consumer.join()

		assertEquals(listOf(1L, 2L), received)
		assertEquals(LocationLaneOffer.CLOSED, lane.offer(rawBatch(4L)))
	}

	@Test
	fun `stale fixes are excluded without poisoning fresh siblings`() {
		val stale = location("stale", elapsedNanos = 100L, wallTimeMs = 1L)
		val fresh = location("fresh", elapsedNanos = 950L, wallTimeMs = 2L)

		val partition = partitionLocationBatch(
			locations = listOf(fresh, stale),
			receivedElapsedRealtimeNanos = 1_000L,
			cutoffElapsedRealtimeNanos = null,
			maximumEvidenceAgeNanos = 100L,
		)

		assertEquals(listOf("fresh"), partition.eligible.map { it.location.provider })
		assertEquals(1, partition.staleCount)
		assertEquals(0, partition.poisonCount)
	}

	@Test
	fun `admission reconciliation is bounded and backs off`() {
		assertEquals(listOf(25L, 100L, 500L, 2_000L), (0..3).map(::locationAdmissionRetryDelayMs))
		assertEquals(null, locationAdmissionRetryDelayMs(4))
	}

	private fun delivery(
		locations: List<android.location.Location>,
		receivedElapsedNanos: Long,
	): SourceDeliveryCandidate {
		val partition = partitionLocationBatch(
			locations,
			receivedElapsedRealtimeNanos = receivedElapsedNanos,
			cutoffElapsedRealtimeNanos = null,
		)
		return locationDeliveryCandidate(
			locations = partition.eligible.map(QualifiedLocationFix::location),
			observedTimes = partition.eligible.map(QualifiedLocationFix::observedElapsedRealtimeNanos),
			receivedElapsedNanos = receivedElapsedNanos,
			receivedWallTimeMs = 10_000L,
			attribution = ATTRIBUTION,
			approximate = false,
		)
	}

	private fun rawBatch(sequence: Long) = RawLocationBatch(
		locations = listOf(location("gps", elapsedNanos = sequence, wallTimeMs = sequence)),
		receivedElapsedNanos = sequence,
		receivedWallTimeMs = sequence,
		callbackSequence = sequence,
		context = LocationCallbackContext(ATTRIBUTION, approximate = false, sink = NOOP_SINK),
	)

	private fun uncheckedDelivery(
		location: android.location.Location,
		observedNanos: Long,
		receivedElapsedNanos: Long,
	) = locationDeliveryCandidate(
		locations = listOf(location),
		observedTimes = listOf(observedNanos),
		receivedElapsedNanos = receivedElapsedNanos,
		receivedWallTimeMs = 10_000L,
		attribution = ATTRIBUTION,
		approximate = false,
	)

	private fun location(provider: String, elapsedNanos: Long, wallTimeMs: Long) =
		FakeLocationSource.createLocation(
			lat = 50.0 + elapsedNanos / 1_000_000.0,
			lon = 14.0,
			accuracy = 5f,
			time = wallTimeMs,
			provider = provider,
		).apply {
			elapsedRealtimeNanos = elapsedNanos
		}

	private fun copyLocation(source: android.location.Location) =
		FakeLocationSource.createLocation(
			lat = source.latitude,
			lon = source.longitude,
			accuracy = source.accuracy,
			time = source.time,
			altitude = source.altitude.takeIf { source.hasAltitude() },
			speed = source.speed.takeIf { source.hasSpeed() },
			bearing = source.bearing.takeIf { source.hasBearing() },
			provider = source.provider ?: "test",
		).apply {
			elapsedRealtimeNanos = source.elapsedRealtimeNanos
		}

	private fun deviceState(
		coarsePermission: Boolean,
		finePermission: Boolean,
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

	private companion object {
		val NOOP_SINK = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		val ATTRIBUTION = LocationDeliveryAttribution(
			sourceInstanceId = SourceInstanceId("location-instance"),
			registrationGeneration = 3L,
			physicalConfigurationFingerprint = "location-config",
			authorizationRevision = 4L,
			purposeEligibilityMask = 1L,
			eligibilityFingerprint = "eligibility",
			configRevision = 5L,
			clockDomainId = "boot-1",
			collectedDataEpoch = 6L,
		)
	}
}
