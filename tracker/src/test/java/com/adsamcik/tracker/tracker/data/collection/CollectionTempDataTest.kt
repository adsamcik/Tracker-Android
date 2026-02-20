package com.adsamcik.tracker.tracker.data.collection

import android.location.Location
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.TrackerDataConsumerComponent
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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
import org.junit.jupiter.api.assertThrows

class CollectionTempDataTest {

	companion object {
		@JvmStatic
		@BeforeAll
		fun setupAll() {
			// Mock logger assertions so they don't crash in unit tests
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

	private fun createTempData(
		timeMillis: Long = 1_000_000L,
		elapsedRealtimeNanos: Long = 1_000_000_000_000L
	): MutableCollectionTempData {
		return MutableCollectionTempData(timeMillis, elapsedRealtimeNanos)
	}

	@Nested
	inner class `constructor and timestamps` {

		@Test
		fun `stores time millis`() {
			val data = createTempData(timeMillis = 42L)
			data.timeMillis shouldBe 42L
		}

		@Test
		fun `stores elapsed realtime nanos`() {
			val data = createTempData(elapsedRealtimeNanos = 99L)
			data.elapsedRealtimeNanos shouldBe 99L
		}

		@Test
		fun `map is initially empty`() {
			val data = createTempData()
			data.containsKey("anything").shouldBeFalse()
		}
	}

	@Nested
	inner class `generic set and get` {

		@Test
		fun `set and containsKey returns true`() {
			val data = createTempData()
			data.set("myKey", "myValue")

			data.containsKey("myKey").shouldBeTrue()
		}

		@Test
		fun `containsKey returns false for missing key`() {
			val data = createTempData()

			data.containsKey("missing").shouldBeFalse()
		}

		@Test
		fun `tryGet returns value when present`() {
			val data = createTempData()
			data.set("key", 42)

			val result: Int? = data.tryGet("key")
			result shouldBe 42
		}

		@Test
		fun `tryGet returns null when missing`() {
			val data = createTempData()

			val result: String? = data.tryGet("missing")
			result.shouldBeNull()
		}

		@Test
		fun `get returns value when present`() {
			val data = createTempData()
			data.set("key", "hello")

			val result: String = data.get("key")
			result shouldBe "hello"
		}

		@Test
		fun `get throws when key is missing`() {
			val data = createTempData()

			assertThrows<NullPointerException> {
				data.get<String>("missing")
			}
		}

		@Test
		fun `overwriting a key replaces value`() {
			val data = createTempData()
			data.set("key", "first")
			data.set("key", "second")

			val result: String = data.get("key")
			result shouldBe "second"
		}

		@Test
		fun `multiple keys are independent`() {
			val data = createTempData()
			data.set("a", 1)
			data.set("b", 2)
			data.set("c", 3)

			data.get<Int>("a") shouldBe 1
			data.get<Int>("b") shouldBe 2
			data.get<Int>("c") shouldBe 3
		}
	}

	@Nested
	inner class `typed setters and getters` {

		@Test
		fun `setActivity and tryGetActivity round-trips`() {
			val data = createTempData()
			val activity = ActivityInfo(activityType = 7, confidence = 80)
			data.setActivity(activity)

			val result = data.tryGetActivity()
			result.shouldNotBeNull()
			result shouldBe activity
		}

		@Test
		fun `tryGetActivity returns null when not set`() {
			val data = createTempData()

			data.tryGetActivity().shouldBeNull()
		}

		@Test
		fun `setLocationData and tryGetLocationData round-trips`() {
			val data = createTempData()
			val location = createAndroidLocation(50.0, 14.0)
			val locationData = LocationData(
				locations = listOf(location),
				previousLocation = null,
				distance = null
			)
			data.setLocationData(locationData)

			val result = data.tryGetLocationData()
			result.shouldNotBeNull()
			result shouldBe locationData
		}

		@Test
		fun `tryGetLocationData returns null when not set`() {
			val data = createTempData()

			data.tryGetLocationData().shouldBeNull()
		}

		@Test
		fun `tryGetLocation returns location from LocationData`() {
			val data = createTempData()
			val location = createAndroidLocation(48.0, 16.0)
			val locationData = LocationData(
				locations = listOf(location),
				previousLocation = null,
				distance = null
			)
			data.setLocationData(locationData)

			val result = data.tryGetLocation()
			result.shouldNotBeNull()
		}

		@Test
		fun `tryGetLocation returns null when no location data`() {
			val data = createTempData()

			data.tryGetLocation().shouldBeNull()
		}

		@Test
		fun `setCellData stores cell data under CELL key`() {
			val data = createTempData()
			val cellData = mockk<CellScanData>(relaxed = true)
			data.setCellData(cellData)

			data.containsKey(TrackerComponentRequirement.CELL.name).shouldBeTrue()
		}
	}

	@Nested
	inner class `permission-validated getters` {

		@Test
		fun `getActivity succeeds with correct requirement`() {
			val data = createTempData()
			val activity = ActivityInfo(activityType = 0, confidence = 95)
			data.setActivity(activity)

			val component = createComponent(TrackerComponentRequirement.ACTIVITY)
			val result = data.getActivity(component)
			result shouldBe activity
		}

		@Test
		fun `getLocationData succeeds with correct requirement`() {
			val data = createTempData()
			val location = createAndroidLocation(50.0, 14.0)
			val locationData = LocationData(
				locations = listOf(location),
				previousLocation = null,
				distance = null
			)
			data.setLocationData(locationData)

			val component = createComponent(TrackerComponentRequirement.LOCATION)
			val result = data.getLocationData(component)
			result shouldBe locationData
		}

		@Test
		fun `getLocation returns lastLocation from LocationData`() {
			val data = createTempData()
			val location = createAndroidLocation(50.0, 14.0)
			val locationData = LocationData(
				locations = listOf(location),
				previousLocation = null,
				distance = null
			)
			data.setLocationData(locationData)

			val component = createComponent(TrackerComponentRequirement.LOCATION)
			val result = data.getLocation(component)
			result.shouldNotBeNull()
		}
	}

	@Nested
	inner class `data accumulation` {

		@Test
		fun `multiple data types coexist independently`() {
			val data = createTempData()

			val activity = ActivityInfo(activityType = 7, confidence = 80)
			data.setActivity(activity)

			val location = createAndroidLocation(50.0, 14.0)
			val locationData = LocationData(
				locations = listOf(location),
				previousLocation = null,
				distance = null
			)
			data.setLocationData(locationData)

			data.set("custom_key", "custom_value")

			data.tryGetActivity() shouldBe activity
			data.tryGetLocationData() shouldBe locationData
			data.tryGet<String>("custom_key") shouldBe "custom_value"
		}

		@Test
		fun `each instance is independent`() {
			val data1 = createTempData(timeMillis = 1L)
			val data2 = createTempData(timeMillis = 2L)

			data1.set("key", "value1")
			data2.set("key", "value2")

			data1.get<String>("key") shouldBe "value1"
			data2.get<String>("key") shouldBe "value2"
		}

		@Test
		fun `stores different value types`() {
			val data = createTempData()

			data.set("int", 42)
			data.set("string", "hello")
			data.set("double", 3.14)
			data.set("boolean", true)
			data.set("list", listOf(1, 2, 3))

			data.get<Int>("int") shouldBe 42
			data.get<String>("string") shouldBe "hello"
			data.get<Double>("double") shouldBe 3.14
			data.get<Boolean>("boolean") shouldBe true
			data.get<List<Int>>("list") shouldBe listOf(1, 2, 3)
		}
	}

	// --- Helpers ---

	private fun createAndroidLocation(
		latitude: Double,
		longitude: Double
	): Location {
		val location = mockk<Location>(relaxed = true)
		every { location.latitude } returns latitude
		every { location.longitude } returns longitude
		return location
	}

	private fun createComponent(
		vararg requirements: TrackerComponentRequirement
	): TrackerDataConsumerComponent {
		val component = mockk<TrackerDataConsumerComponent>()
		every { component.requiredData } returns requirements.toList()
		return component
	}
}
