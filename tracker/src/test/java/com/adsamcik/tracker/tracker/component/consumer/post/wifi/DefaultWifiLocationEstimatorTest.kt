package com.adsamcik.tracker.tracker.component.consumer.post.wifi

import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.WifiData
import com.adsamcik.tracker.shared.base.data.WifiInfo
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultWifiLocationEstimatorTest {

    private fun wifiInfo(
        bssid: String = "00:11:22:33:44:55",
        ssid: String = "Test",
        capabilities: String = "[WPA2]",
        frequency: Int = 2_412,
        level: Int = -50
    ) = WifiInfo(
        bssid = bssid,
        ssid = ssid,
        capabilities = capabilities,
        frequency = frequency,
        level = level
    )

    private fun location(
        latitude: Double,
        longitude: Double,
        accuracy: Float = 5f,
        time: Long = 0L
    ) = Location(
        time = time,
        latitude = latitude,
        longitude = longitude,
        altitude = null,
        horizontalAccuracy = accuracy,
        verticalAccuracy = null,
        speed = null,
        speedAccuracy = null
    )

    private fun wifiData(time: Long, location: Location, info: WifiInfo) =
        WifiData(location, time, listOf(info))

    @Test
    fun duplicateScansAreSuppressed() {
        val estimator = DefaultWifiLocationEstimator(
            DefaultWifiLocationEstimator.Config(metadataUpdateIntervalMillis = Long.MAX_VALUE)
        )
        val baseLocation = location(50.0, 14.0)
        val firstScan = estimator.onScan(wifiData(1_000L, baseLocation, wifiInfo(level = -45)))
        assertEquals(1, firstScan.size)

        val secondScan = estimator.onScan(wifiData(1_500L, baseLocation, wifiInfo(level = -46)))
        assertTrue("Duplicate scan should not trigger persistence", secondScan.isEmpty())
    }

    @Test
    fun rejectsOutliersAfterBaselineEstablished() {
        val estimator = DefaultWifiLocationEstimator(
            DefaultWifiLocationEstimator.Config(minSamplesForOutlierCheck = 2)
        )
        val first = location(50.0000, 14.0000)
        val second = location(50.0003, 14.0003)

        estimator.onScan(wifiData(1_000L, first, wifiInfo(level = -48)))
        estimator.onScan(wifiData(2_000L, second, wifiInfo(level = -47)))

        val outlierLocation = location(51.0, 15.0)
        val outlierResult = estimator.onScan(wifiData(3_000L, outlierLocation, wifiInfo(level = -30)))
        assertTrue("Outlier should be ignored", outlierResult.isEmpty())

        val snapshot = estimator.snapshot().first { it.bssid == "00:11:22:33:44:55" }
        assertEquals(2, snapshot.sampleCount)
        assertTrue("Latitude should remain close to baseline", abs(snapshot.latitude!! - 50.00015) < 0.01)
        assertTrue("Longitude should remain close to baseline", abs(snapshot.longitude!! - 14.00015) < 0.01)
    }
}
