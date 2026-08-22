package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ConnectivityObservationAdmissionTest {
	@Test
	fun `mixed-age provider collection retains only individually fresh observations`() {
		val now = 20L * NANOS_PER_MILLISECOND
		val observations = listOf(
			TimedObservation("fresh", 19L * NANOS_PER_MILLISECOND),
			TimedObservation("edge", 15L * NANOS_PER_MILLISECOND),
			TimedObservation("stale", 14L * NANOS_PER_MILLISECOND),
			TimedObservation("unknown", null),
			TimedObservation("future", 21L * NANOS_PER_MILLISECOND),
		)

		val admitted = freshProviderObservations(
			observations,
			receivedElapsedRealtimeNanos = now,
			maximumAgeMs = 5L,
			providerTimestampNanos = TimedObservation::providerTimestampNanos,
		)

		assertEquals(listOf("fresh", "edge"), admitted.map(TimedObservation::id))
	}

	@Test
	fun `missing Wi-Fi provider time is not replaced by receipt time`() {
		val accessPoint = WifiBackendAccessPoint(
			bssid = "00:00:00:00:00:00",
			frequencyMhz = 2_412,
			signalLevelDbm = -50,
			platformTimestampMicros = 0L,
		)

		assertNull(accessPoint.providerTimestampNanos)
		assertNull(WifiBackendSnapshot(listOf(accessPoint)).freshestTimestampNanos)
		assertTrue(
			freshProviderObservations(
				listOf(accessPoint),
				receivedElapsedRealtimeNanos = 10L * NANOS_PER_MILLISECOND,
				maximumAgeMs = 5L,
				providerTimestampNanos = WifiBackendAccessPoint::providerTimestampNanos,
			).isEmpty(),
		)
	}

	@Test
	fun `failed scan update never triggers a cache read`() {
		assertTrue(shouldReadWifiSnapshot(true))
		assertTrue(shouldReadWifiSnapshot(null))
		assertEquals(false, shouldReadWifiSnapshot(false))
	}

	@Test
	fun `coverage-only delivery requires a provider-confirmed empty result`() {
		assertTrue(shouldAdmitCoverageOnly(0, 0, providerDeliveryConfirmedFresh = true))
		assertEquals(false, shouldAdmitCoverageOnly(0, 0, providerDeliveryConfirmedFresh = false))
		assertEquals(false, shouldAdmitCoverageOnly(3, 0, providerDeliveryConfirmedFresh = true))
		assertEquals(false, shouldAdmitCoverageOnly(1, 1, providerDeliveryConfirmedFresh = true))
	}

	@Test
	fun `radio persistence withholds raw provider and subscription identities`() {
		val rawBssid = "aa:bb:cc:dd:ee:ff"
		val wifi = WifiBackendAccessPoint(rawBssid, 2_412, -50, 10L).toMinimizedEvidence()
		val rawCellIdentity = "310:260:123:456"
		val cell = CellBackendObservation(rawCellIdentity, "LTE", true, -90, 10_000L)
			.toMinimizedEvidence()
		val cellPayload = minimizedCellSnapshotPayload(listOf(cell), CellRefreshOutcome.CALLBACK)

		assertEquals(WITHHELD_RADIO_IDENTIFIER_TOKEN, wifi.identifierToken)
		assertEquals(WITHHELD_RADIO_IDENTIFIER_TOKEN, cell.identifierToken)
		assertNull(cellPayload.subscriptionId)
		assertFalse(wifi.toString().contains(rawBssid))
		assertFalse(cellPayload.toString().contains(rawCellIdentity))
	}

	@Test
	fun `unsupported sparse refresh does not create a polling loop`() {
		assertTrue(shouldScheduleCellRefresh(CellMode.OBSERVE_AND_SPARSE_REFRESH, true))
		assertEquals(false, shouldScheduleCellRefresh(CellMode.OBSERVE_AND_SPARSE_REFRESH, false))
		assertEquals(false, shouldScheduleCellRefresh(CellMode.OBSERVE_CHANGES, true))
	}

	@Test
	fun `paid acquisition is direct-only finite and stops after qualified evidence`() {
		val direct = DirectAcquisitionBudget(maximumAttempts = 3, directCaptureRequested = true)
		assertTrue(direct.consumeRequest())
		direct.markQualifiedEvidence()
		assertFalse(direct.canRequest)
		assertFalse(direct.consumeRequest())

		val exhausted = DirectAcquisitionBudget(maximumAttempts = 2, directCaptureRequested = true)
		assertTrue(exhausted.consumeRequest())
		assertTrue(exhausted.consumeRequest())
		assertFalse(exhausted.consumeRequest())

		val ambientOnly = DirectAcquisitionBudget(maximumAttempts = 3, directCaptureRequested = false)
		assertFalse(ambientOnly.canRequest)
		assertFalse(ambientOnly.consumeRequest())
	}

	@Test
	fun `failed handoff does not poison replay identity`() {
		val gate = BoundedReplayIdentityGate(capacity = 2)
		assertTrue(gate.shouldAdmit("retry-me"))
		assertTrue(gate.shouldAdmit("retry-me"))
		gate.record("retry-me")
		assertEquals(false, gate.shouldAdmit("retry-me"))
	}

	@Test
	fun `live replay memory is explicitly bounded`() {
		val gate = BoundedReplayIdentityGate(capacity = 2)
		gate.record("a")
		gate.record("b")
		gate.record("c")

		assertTrue(gate.shouldAdmit("a"))
		assertEquals(false, gate.shouldAdmit("b"))
		assertEquals(false, gate.shouldAdmit("c"))
	}

	@Test
	fun `age arithmetic saturates instead of overflowing`() {
		val admitted = freshProviderObservations(
			listOf(TimedObservation("valid", 1L)),
			receivedElapsedRealtimeNanos = Long.MAX_VALUE,
			maximumAgeMs = Long.MAX_VALUE,
			providerTimestampNanos = TimedObservation::providerTimestampNanos,
		)

		assertEquals(listOf("valid"), admitted.map(TimedObservation::id))
	}

	private data class TimedObservation(val id: String, val providerTimestampNanos: Long?)

	private companion object {
		const val NANOS_PER_MILLISECOND = 1_000_000L
	}
}
