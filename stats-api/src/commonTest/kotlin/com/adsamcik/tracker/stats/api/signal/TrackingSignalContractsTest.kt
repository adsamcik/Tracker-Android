package com.adsamcik.tracker.stats.api.signal

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackingSignalContractsTest {

	@Test
	fun `minimal tracking signal keeps optional payloads absent`() {
		val signal = TrackingSignal(timestampMs = EpochMs(1_700_000_000_000L))

		assertEquals(EpochMs(1_700_000_000_000L), signal.timestampMs)
		assertEquals(0L, signal.elapsedRealtimeNanos)
		assertNull(signal.location)
		assertNull(signal.activity)
		assertNull(signal.steps)
		assertNull(signal.cells)
		assertNull(signal.wifi)
		assertNull(signal.pressure)
		assertNull(signal.policy)
	}

	@Test
	fun `tracking signal preserves nested sensor payloads`() {
		val signal = TrackingSignal(
			timestampMs = EpochMs(1234L),
			elapsedRealtimeNanos = 9_876L,
			location = LocationSignal(
				coordinate = CoordinateE7(
					lat = LatE7.fromDegrees(50.087451),
					lon = LonE7.fromDegrees(14.420671),
				),
				horizontalAccuracyM = 4.5f,
				speed = SpeedMps(3.2f),
				altitudeM = 220.0f,
				distanceDelta = DistanceM(12.5f),
				provider = "gps",
			),
			activity = ActivitySignal(
				type = DetectedActivityType.WALKING,
				confidence = ActivityConfidence(88),
			),
			steps = StepSignal(
				stepDelta = StepCount(7),
				totalStepsSinceBoot = 1007L,
				sensorValueStart = 1000,
				sensorValueEnd = 1007,
				sensorReset = false,
			),
			cells = CellSignal(
				towers = listOf(
					CellTowerReading(
						cellId = 42L,
						mcc = "230",
						mnc = "01",
						networkType = 13,
						signalStrength = -95,
					),
				),
			),
			wifi = WifiSignal(
				networks = listOf(
					WifiNetworkReading(
						bssid = "aa:bb:cc:dd:ee:ff",
						ssid = "Tracker",
						capabilities = "[WPA2-PSK-CCMP][ESS]",
						frequency = 2412,
						level = -48,
					),
				),
			),
			pressure = PressureSignal(pressureHpa = 1012.8f, altitudeM = 224.5f),
			policy = PolicySignal(tier = PolicyTier.ACTIVE, policyName = "ACTIVE_MODERATE"),
		)

		assertEquals(9_876L, signal.elapsedRealtimeNanos)
		assertEquals("gps", signal.location?.provider)
		assertEquals(DetectedActivityType.WALKING, signal.activity?.type)
		assertEquals(ActivityConfidence(88), signal.activity?.confidence)
		assertEquals(StepCount(7), signal.steps?.stepDelta)
		assertEquals(1007L, signal.steps?.totalStepsSinceBoot)
		assertEquals(42L, signal.cells?.towers?.single()?.cellId)
		assertEquals("Tracker", signal.wifi?.networks?.single()?.ssid)
		assertEquals(1012.8f, signal.pressure?.pressureHpa)
		assertEquals(PolicyTier.ACTIVE, signal.policy?.tier)
		assertEquals("ACTIVE_MODERATE", signal.policy?.policyName)
	}

	@Test
	fun `policy signal defaults policy name to null`() {
		val signal = PolicySignal(tier = PolicyTier.PRECISION)

		assertEquals(PolicyTier.PRECISION, signal.tier)
		assertNull(signal.policyName)
	}
}
