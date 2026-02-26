package com.adsamcik.tracker.tracker.component.consumer.data

import android.location.Location
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.producer.BarometerDataProducer
import com.adsamcik.tracker.tracker.component.producer.PressureReading
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import kotlin.math.abs

@ExtendWith(RobolectricExtension::class)
@DisplayName("LocationTrackerComponent")
class LocationTrackerComponentTest {

	private lateinit var component: LocationTrackerComponent

	@BeforeEach
	fun setup() {
		component = LocationTrackerComponent()
	}

	private fun createAndroidLocation(
		altitude: Double? = null,
		verticalAccuracy: Float? = null,
		latitude: Double = 50.0,
		longitude: Double = 14.0,
		time: Long = System.currentTimeMillis()
	): Location {
		return Location("test").apply {
			this.latitude = latitude
			this.longitude = longitude
			this.time = time
			elapsedRealtimeNanos = System.nanoTime()
			if (altitude != null) {
				this.altitude = altitude
			}
			if (verticalAccuracy != null) {
				this.verticalAccuracyMeters = verticalAccuracy
			}
		}
	}

	private fun createTempData(
		location: Location,
		pressureReading: PressureReading? = null
	): MutableCollectionTempData {
		val tempData = MutableCollectionTempData(
			timeMillis = location.time,
			elapsedRealtimeNanos = location.elapsedRealtimeNanos
		)
		val locationData = LocationData(
			locations = listOf(location),
			previousLocation = null,
			distance = null
		)
		tempData.set(TrackerComponentRequirement.LOCATION.name, locationData)
		if (pressureReading != null) {
			tempData.set(BarometerDataProducer.PRESSURE_KEY, pressureReading)
		}
		return tempData
	}

	@Nested
	@DisplayName("lifecycle")
	inner class Lifecycle {
		@Test
		fun `requires LOCATION data`() {
			component.requiredData shouldBe listOf(TrackerComponentRequirement.LOCATION)
		}

		@Test
		fun `onEnable initializes processor`() = runTest {
			val context = RuntimeEnvironment.getApplication()
			component.onEnable(context)

			val location = createAndroidLocation(altitude = 500.0, verticalAccuracy = 5f)
			val tempData = createTempData(location)
			val collectionData = MutableCollectionData()

			component.onDataUpdated(tempData, collectionData)
			collectionData.location.shouldNotBeNull()
		}

		@Test
		fun `onDisable clears processor`() = runTest {
			val context = RuntimeEnvironment.getApplication()
			component.onEnable(context)
			component.onDisable(context)

			// After disable, altitude still passes through but without processing
			val location = createAndroidLocation(altitude = 500.0, verticalAccuracy = 5f)
			val tempData = createTempData(location)
			val collectionData = MutableCollectionData()

			component.onDataUpdated(tempData, collectionData)
			collectionData.location.shouldNotBeNull()
		}

		@Test
		fun `enable-disable-enable cycle works`() = runTest {
			val context = RuntimeEnvironment.getApplication()
			component.onEnable(context)
			component.onDisable(context)
			component.onEnable(context)

			val location = createAndroidLocation(altitude = 500.0, verticalAccuracy = 5f)
			val tempData = createTempData(location)
			val collectionData = MutableCollectionData()

			component.onDataUpdated(tempData, collectionData)
			collectionData.location.shouldNotBeNull()
		}
	}

	@Nested
	@DisplayName("altitude processing")
	inner class AltitudeProcessing {
		@BeforeEach
		fun enable() = runTest {
			component.onEnable(RuntimeEnvironment.getApplication())
		}

		@Test
		fun `processes altitude when vertical accuracy is good`() = runTest {
			val location = createAndroidLocation(altitude = 500.0, verticalAccuracy = 5f)
			val tempData = createTempData(location)
			val collectionData = MutableCollectionData()

			component.onDataUpdated(tempData, collectionData)

			val result = collectionData.location
			result.shouldNotBeNull()
			result.altitude.shouldNotBeNull()
		}

		@Test
		fun `removes altitude when vertical accuracy is poor`() = runTest {
			val location = createAndroidLocation(altitude = 500.0, verticalAccuracy = 25f)
			val tempData = createTempData(location)
			val collectionData = MutableCollectionData()

			component.onDataUpdated(tempData, collectionData)

			val result = collectionData.location
			result.shouldNotBeNull()
			result.altitude.shouldBeNull()
		}

		@Test
		fun `handles location without altitude`() = runTest {
			val location = createAndroidLocation(altitude = null)
			val tempData = createTempData(location)
			val collectionData = MutableCollectionData()

			component.onDataUpdated(tempData, collectionData)

			val result = collectionData.location
			result.shouldNotBeNull()
			result.altitude.shouldBeNull()
		}

		@Test
		fun `dampens altitude jump via Kalman filter`() = runTest {
			val loc1 = createAndroidLocation(altitude = 500.0, verticalAccuracy = 10f, time = 1000L)
			val collectionData1 = MutableCollectionData()
			component.onDataUpdated(createTempData(loc1), collectionData1)

			val alt1 = collectionData1.location?.altitude
			alt1.shouldNotBeNull()

			val loc2 = createAndroidLocation(altitude = 600.0, verticalAccuracy = 10f, time = 2000L)
			val collectionData2 = MutableCollectionData()
			component.onDataUpdated(createTempData(loc2), collectionData2)

			val alt2 = collectionData2.location?.altitude
			alt2.shouldNotBeNull()

			// Kalman should dampen the 100m jump
			val diff = abs(alt2 - alt1)
			assert(diff < 100.0) { "Expected altitude jump to be dampened, but diff was $diff" }
		}
	}

	@Nested
	@DisplayName("barometer integration")
	inner class BarometerIntegration {
		@BeforeEach
		fun enable() = runTest {
			component.onEnable(RuntimeEnvironment.getApplication())
		}

		@Test
		fun `uses barometer data when available`() = runTest {
			val pressure = PressureReading(pressureHpa = 955f, altitudeM = 500f)
			val location = createAndroidLocation(altitude = 500.0, verticalAccuracy = 5f)
			val tempData = createTempData(location, pressureReading = pressure)
			val collectionData = MutableCollectionData()

			component.onDataUpdated(tempData, collectionData)

			val result = collectionData.location
			result.shouldNotBeNull()
			result.altitude.shouldNotBeNull()
		}

		@Test
		fun `works without barometer data`() = runTest {
			val location = createAndroidLocation(altitude = 500.0, verticalAccuracy = 5f)
			val tempData = createTempData(location, pressureReading = null)
			val collectionData = MutableCollectionData()

			component.onDataUpdated(tempData, collectionData)

			val result = collectionData.location
			result.shouldNotBeNull()
			result.altitude.shouldNotBeNull()
		}

		@Test
		fun `barometer dampens GPS spike`() = runTest {
			val stablePressure = PressureReading(pressureHpa = 955f, altitudeM = 500f)

			// Establish baseline
			val loc1 = createAndroidLocation(altitude = 500.0, verticalAccuracy = 10f, time = 1000L)
			component.onDataUpdated(createTempData(loc1, stablePressure), MutableCollectionData())

			val loc2 = createAndroidLocation(altitude = 500.0, verticalAccuracy = 10f, time = 2000L)
			component.onDataUpdated(createTempData(loc2, stablePressure), MutableCollectionData())

			// GPS spikes while barometer stable
			val loc3 = createAndroidLocation(altitude = 530.0, verticalAccuracy = 10f, time = 3000L)
			val collectionData = MutableCollectionData()
			component.onDataUpdated(createTempData(loc3, stablePressure), collectionData)

			val result = collectionData.location
			result.shouldNotBeNull()
			val altitude = result.altitude
			altitude.shouldNotBeNull()

			// Fused result should be closer to 500 than 530
			assert(abs(altitude - 500.0) < abs(altitude - 530.0)) {
				"Expected fused altitude ($altitude) closer to 500 than 530"
			}
		}
	}
}
