package com.adsamcik.tracker.tracker.component.consumer.post.wifi

import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.WifiData
import com.adsamcik.tracker.shared.base.data.WifiInfo
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

@DisplayName("DefaultWifiLocationEstimator")
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

    @Nested
    @DisplayName("Duplicate Scan Handling")
    inner class DuplicateScanHandling {

        @Test
        fun `duplicate scans are suppressed`() {
            val estimator = DefaultWifiLocationEstimator(
                DefaultWifiLocationEstimator.Config(metadataUpdateIntervalMillis = Long.MAX_VALUE)
            )
            val baseLocation = location(50.0, 14.0)

            val firstScan = estimator.onScan(wifiData(1_000L, baseLocation, wifiInfo(level = -45)))
            firstScan shouldHaveSize 1

            val secondScan = estimator.onScan(wifiData(1_500L, baseLocation, wifiInfo(level = -46)))
            secondScan.shouldBeEmpty()
        }
    }

    @Nested
    @DisplayName("Outlier Detection")
    inner class OutlierDetection {

        @Test
        fun `rejects outliers after baseline established`() {
            val estimator = DefaultWifiLocationEstimator(
                DefaultWifiLocationEstimator.Config(minSamplesForOutlierCheck = 2)
            )
            val first = location(50.0000, 14.0000)
            val second = location(50.0003, 14.0003)

            estimator.onScan(wifiData(1_000L, first, wifiInfo(level = -48)))
            estimator.onScan(wifiData(2_000L, second, wifiInfo(level = -47)))

            val outlierLocation = location(51.0, 15.0)
            val outlierResult = estimator.onScan(wifiData(3_000L, outlierLocation, wifiInfo(level = -30)))
            outlierResult.shouldBeEmpty()

            val snapshot = estimator.snapshot().first { it.bssid == "00:11:22:33:44:55" }
            snapshot.sampleCount shouldBe 2
            abs(snapshot.latitude!! - 50.00015) shouldBeLessThan 0.01
            abs(snapshot.longitude!! - 14.00015) shouldBeLessThan 0.01
        }
    }
}
