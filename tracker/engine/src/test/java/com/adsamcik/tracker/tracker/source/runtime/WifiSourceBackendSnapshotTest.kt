package com.adsamcik.tracker.tracker.source.runtime

import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiSourceBackendSnapshotTest {
	@Test
	fun `Android scan retains each available network identity name and provider time`() {
		val scan = ScanResult().apply {
			BSSID = "aa:bb:cc:dd:ee:ff"
			SSID = "Home Wi-Fi"
			frequency = 2_412
			level = -52
			timestamp = 9_876L
		}
		val manager = mockk<WifiManager>()
		every { manager.scanResults } returns listOf(scan)
		val backend = AndroidWifiSourceBackend(manager, { _, _ -> }, { })

		val point = requireNotNull(backend.readSnapshot()).accessPoints.single()
		assertEquals("aa:bb:cc:dd:ee:ff", point.bssid)
		assertEquals("Home Wi-Fi", point.networkName)
		assertEquals(9_876_000L, point.providerTimestampNanos)
		assertEquals("WifiBackendAccessPoint", point.toString())
	}

	@Test
	fun `Android withheld network details remain unknown without invented identity`() {
		val scan = ScanResult().apply {
			BSSID = "02:00:00:00:00:00"
			SSID = WifiManager.UNKNOWN_SSID
			frequency = 5_180
			level = -60
			timestamp = 9_876L
		}
		val manager = mockk<WifiManager>()
		every { manager.scanResults } returns listOf(scan)
		val backend = AndroidWifiSourceBackend(manager, { _, _ -> }, { })

		val point = requireNotNull(backend.readSnapshot()).accessPoints.single()
		assertEquals("", point.bssid)
		assertNull(point.networkName)
		assertEquals("", point.toSourceEvidence().identifierToken)
	}
}
