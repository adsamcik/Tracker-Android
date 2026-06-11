package com.adsamcik.tracker.tracker.component.producer

import android.net.wifi.ScanResult
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import com.adsamcik.tracker.tracker.data.collection.WifiScanData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import org.robolectric.annotation.Config

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("WifiDataProducer")
class WifiDataProducerTest {

	private lateinit var observer: TrackerDataProducerObserver
	private lateinit var producer: WifiDataProducer

	@BeforeEach
	fun setUp() {
		observer = mockk(relaxed = true)
		producer = WifiDataProducer(observer)
	}

	private fun createBuilder(): TrackingCycleBuilder {
		return TrackingCycleBuilder(System.currentTimeMillis(), System.nanoTime())
	}

	private fun setScanData(scanResults: Array<ScanResult>?, time: Long = 1000L, relativeTime: Long = 2000L) {
		val scanDataField = WifiDataProducer::class.java.getDeclaredField("scanData")
		scanDataField.isAccessible = true
		scanDataField.set(producer, scanResults)

		val scanTimeField = WifiDataProducer::class.java.getDeclaredField("scanTime")
		scanTimeField.isAccessible = true
		scanTimeField.setLong(producer, time)

		val scanTimeRelativeField = WifiDataProducer::class.java.getDeclaredField("scanTimeRelative")
		scanTimeRelativeField.isAccessible = true
		scanTimeRelativeField.setLong(producer, relativeTime)
	}

	private fun createMockScanResult(): ScanResult {
		return mockk<ScanResult>(relaxed = true)
	}

	@Nested
	@DisplayName("onDataRequest")
	inner class OnDataRequest {

		@Test
		fun `returns null when no scan data available`() {
			// scanData is null by default; appContext not initialized so it skips re-scan
			val builder = createBuilder()
			producer.onDataRequest(builder)

			builder.wifiScan.shouldBeNull()
		}

		@Test
		fun `sets wifi data in temp data when scan results present`() {
			val scanResults = arrayOf(createMockScanResult(), createMockScanResult())
			setScanData(scanResults, time = 5000L, relativeTime = 10000L)

			val builder = createBuilder()
			producer.onDataRequest(builder)

			val wifiData = builder.wifiScan
			wifiData.shouldNotBeNull()
			wifiData.data shouldHaveSize 2
			wifiData.timeMillis shouldBe 5000L
			wifiData.relativeTimeNanos shouldBe 10000L
		}

		@Test
		fun `clears scan data after collection`() {
			val scanResults = arrayOf(createMockScanResult())
			setScanData(scanResults)

			val builder1 = createBuilder()
			producer.onDataRequest(builder1)
			builder1.wifiScan.shouldNotBeNull()

			// Second request should have no data
			val builder2 = createBuilder()
			producer.onDataRequest(builder2)
			builder2.wifiScan.shouldBeNull()
		}

		@Test
		fun `handles empty scan results array`() {
			val scanResults = emptyArray<ScanResult>()
			setScanData(scanResults)

			val builder = createBuilder()
			producer.onDataRequest(builder)

			val wifiData = builder.wifiScan
			wifiData.shouldNotBeNull()
			wifiData.data shouldHaveSize 0
		}

		@Test
		fun `handles large scan results array`() {
			val scanResults = Array(100) { createMockScanResult() }
			setScanData(scanResults)

			val builder = createBuilder()
			producer.onDataRequest(builder)

			val wifiData = builder.wifiScan
			wifiData.shouldNotBeNull()
			wifiData.data shouldHaveSize 100
		}

		@Test
		fun `resets scan time after collection`() {
			setScanData(arrayOf(createMockScanResult()), time = 5000L, relativeTime = 10000L)

			producer.onDataRequest(createBuilder())

			// Verify internal time fields were reset
			val scanTimeField = WifiDataProducer::class.java.getDeclaredField("scanTime")
			scanTimeField.isAccessible = true
			scanTimeField.getLong(producer) shouldBe -1L

			val scanTimeRelativeField = WifiDataProducer::class.java.getDeclaredField("scanTimeRelative")
			scanTimeRelativeField.isAccessible = true
			scanTimeRelativeField.getLong(producer) shouldBe -1L
		}
	}

	@Nested
	@DisplayName("scan data consistency")
	inner class ScanDataConsistency {

		@Test
		fun `collected WifiScanData preserves scan result references`() {
			val result1 = createMockScanResult()
			val result2 = createMockScanResult()
			setScanData(arrayOf(result1, result2))

			val builder = createBuilder()
			producer.onDataRequest(builder)

			val wifiData = builder.wifiScan
			wifiData.shouldNotBeNull()
			wifiData.data[0] shouldBe result1
			wifiData.data[1] shouldBe result2
		}
	}
}
