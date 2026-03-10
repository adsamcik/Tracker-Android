package com.adsamcik.tracker.tracker.data.collection

import android.location.Location
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.producer.BarometerDataProducer
import com.adsamcik.tracker.tracker.component.producer.PressureReading
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LegacyTempDataAdapterTest {

	companion object {
		private const val TIME_MS = 1_700_000_000_000L
		private const val ELAPSED_NANOS = 5_000_000_000L

		@JvmStatic
		@BeforeAll
		fun setupAll() {
			mockkStatic("com.adsamcik.tracker.logger.AssertKt")
			every { com.adsamcik.tracker.logger.assertTrue(any()) } returns Unit
			every { com.adsamcik.tracker.logger.assertTrue(any(), any()) } returns Unit
		}

		@JvmStatic
		@AfterAll
		fun teardownAll() {
			unmockkStatic("com.adsamcik.tracker.logger.AssertKt")
		}
	}

	// --- Helpers ---

	private fun createLocation(lat: Double = 50.0, lon: Double = 14.0): Location {
		val loc = mockk<Location>(relaxed = true)
		every { loc.latitude } returns lat
		every { loc.longitude } returns lon
		return loc
	}

	private fun createLocationData(lat: Double = 50.0, lon: Double = 14.0): LocationData {
		return LocationData(
			locations = listOf(createLocation(lat, lon)),
			previousLocation = null,
			distance = null,
		)
	}

	// --- Tests ---

	@Nested
	inner class `full cycle to TempData` {

		@Test
		fun `all fields accessible via old getters`() {
			val activity = ActivityInfo(activityType = 7, confidence = 80)
			val locationData = createLocationData()
			val cell = mockk<CellScanData>(relaxed = true)
			val wifi = mockk<WifiScanData>(relaxed = true)
			val pressure = PressureReading(pressureHpa = 1013.25f, altitudeM = 250.0f)

			val cycle = TrackingCycle(
				timestampMs = TIME_MS,
				elapsedRealtimeNanos = ELAPSED_NANOS,
				activity = activity,
				location = locationData,
				cellScan = cell,
				wifiScan = wifi,
				stepDelta = 42,
				pressure = pressure,
				rawGpsAltitude = 248.5,
			)

			val tempData = LegacyTempDataAdapter.toTempData(cycle)

			tempData.timeMillis shouldBe TIME_MS
			tempData.elapsedRealtimeNanos shouldBe ELAPSED_NANOS

			tempData.tryGetActivity().shouldNotBeNull()
			tempData.tryGetActivity() shouldBe activity

			tempData.tryGetLocationData().shouldNotBeNull()
			tempData.tryGetLocationData() shouldBe locationData

			tempData.containsKey(TrackerComponentRequirement.CELL.name) shouldBe true
			tempData.containsKey(TrackerComponentRequirement.WIFI.name) shouldBe true

			val steps: Int? = tempData.tryGet(StepDataProducer.NEW_STEPS_ARG)
			steps shouldBe 42

			val pressureResult: PressureReading? = tempData.tryGet(BarometerDataProducer.PRESSURE_KEY)
			pressureResult shouldBe pressure

			val rawAlt: Double? = tempData.tryGet("raw_gps_altitude")
			rawAlt shouldBe 248.5
		}
	}

	@Nested
	inner class `partial cycle to TempData` {

		@Test
		fun `location only populates location`() {
			val locationData = createLocationData(48.0, 16.0)
			val cycle = TrackingCycle(
				timestampMs = TIME_MS,
				elapsedRealtimeNanos = ELAPSED_NANOS,
				location = locationData,
			)

			val tempData = LegacyTempDataAdapter.toTempData(cycle)

			tempData.tryGetLocationData().shouldNotBeNull()
			tempData.tryGetLocationData() shouldBe locationData

			tempData.tryGetActivity().shouldBeNull()
			tempData.containsKey(TrackerComponentRequirement.CELL.name).shouldBeFalse()
			tempData.containsKey(TrackerComponentRequirement.WIFI.name).shouldBeFalse()
			tempData.containsKey(StepDataProducer.NEW_STEPS_ARG).shouldBeFalse()
			tempData.containsKey(BarometerDataProducer.PRESSURE_KEY).shouldBeFalse()
			tempData.containsKey("raw_gps_altitude").shouldBeFalse()
		}
	}

	@Nested
	inner class `empty cycle to TempData` {

		@Test
		fun `empty cycle produces empty TempData`() {
			val cycle = TrackingCycle(
				timestampMs = TIME_MS,
				elapsedRealtimeNanos = ELAPSED_NANOS,
			)

			val tempData = LegacyTempDataAdapter.toTempData(cycle)

			tempData.timeMillis shouldBe TIME_MS
			tempData.elapsedRealtimeNanos shouldBe ELAPSED_NANOS
			tempData.tryGetActivity().shouldBeNull()
			tempData.tryGetLocationData().shouldBeNull()
			tempData.containsKey(TrackerComponentRequirement.CELL.name).shouldBeFalse()
			tempData.containsKey(TrackerComponentRequirement.WIFI.name).shouldBeFalse()
			tempData.containsKey(StepDataProducer.NEW_STEPS_ARG).shouldBeFalse()
			tempData.containsKey(BarometerDataProducer.PRESSURE_KEY).shouldBeFalse()
			tempData.containsKey("raw_gps_altitude").shouldBeFalse()
		}
	}
}
