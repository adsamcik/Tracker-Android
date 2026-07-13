package com.adsamcik.tracker.tracker.component.producer

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import com.adsamcik.tracker.tracker.data.collection.WifiScanData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WifiDataProducerTest {

	private lateinit var observer: TrackerDataProducerObserver
	private lateinit var producer: WifiDataProducer

	@Before
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

	private fun initializeProducerForDataRequests() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

		val appContextField = WifiDataProducer::class.java.getDeclaredField("appContext")
		appContextField.isAccessible = true
		appContextField.set(producer, context.applicationContext)

		val wifiManagerField = WifiDataProducer::class.java.getDeclaredField("wifiManager")
		wifiManagerField.isAccessible = true
		wifiManagerField.set(
			producer,
			mockk<WifiManager> {
				every { scanResults } returns emptyList()
			}
		)

		val lastScanRequestField = WifiDataProducer::class.java.getDeclaredField("lastScanRequest")
		lastScanRequestField.isAccessible = true
		lastScanRequestField.setLong(producer, SystemClock.elapsedRealtime())
	}


	@Test
	fun `returns null when no scan data available`() {
		// scanData is null by default; appContext not initialized so it skips re-scan
		val builder = createBuilder()
		producer.onDataRequest(builder)

		builder.wifiScan.shouldBeNull()
	}

	@Test
	fun `sets wifi data in temp data when scan results present`() {
		initializeProducerForDataRequests()
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
		initializeProducerForDataRequests()
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
	fun `ignores empty scan results array`() {
		initializeProducerForDataRequests()
		val scanResults = emptyArray<ScanResult>()
		setScanData(scanResults)

		val builder = createBuilder()
		producer.onDataRequest(builder)

		builder.wifiScan.shouldBeNull()
	}

	@Test
	fun `handles large scan results array`() {
		initializeProducerForDataRequests()
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
		initializeProducerForDataRequests()
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


	@Test
	fun `collected WifiScanData preserves scan result references`() {
		initializeProducerForDataRequests()
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
