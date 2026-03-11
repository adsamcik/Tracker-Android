package com.adsamcik.tracker.tracker.component.consumer.data

import android.location.Location
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.producer.PressureReading
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
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

	private fun createCycle(
		location: Location,
		pressureReading: PressureReading? = null
	): TrackingCycle {
		val locationData = LocationData(
			locations = listOf(location),
			previousLocation = null,
			distance = null
		)
		return TrackingCycle(
			timestampMs = location.time,
			elapsedRealtimeNanos = location.elapsedRealtimeNanos,
			location = locationData,
			pressure = pressureReading,
			rawGpsAltitude = if (location.hasAltitude()) location.altitude else null,
		)
	}

	/**
	 * Creates a cycle that mimics what [LocationCollectionTrigger] produces:
	 * [LocationData] contains a previousLocation and a precomputed distance.
	 */
	private fun createCycleWithPrevious(
		location: Location,
		previousLocation: Location
	): TrackingCycle {
		val distance = location.distanceTo(previousLocation)
		val locationData = LocationData(
			locations = listOf(location),
			previousLocation = previousLocation,
			distance = distance
		)
		return TrackingCycle(
			timestampMs = location.time,
			elapsedRealtimeNanos = location.elapsedRealtimeNanos,
			location = locationData,
		)
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
			val tempData = createCycle(location)
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
			val tempData = createCycle(location)
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
			val tempData = createCycle(location)
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
			val tempData = createCycle(location)
			val collectionData = MutableCollectionData()

			component.onDataUpdated(tempData, collectionData)

			val result = collectionData.location
			result.shouldNotBeNull()
			result.altitude.shouldNotBeNull()
		}

		@Test
		fun `removes altitude when vertical accuracy is poor`() = runTest {
			val location = createAndroidLocation(altitude = 500.0, verticalAccuracy = 25f)
			val tempData = createCycle(location)
			val collectionData = MutableCollectionData()

			component.onDataUpdated(tempData, collectionData)

			val result = collectionData.location
			result.shouldNotBeNull()
			result.altitude.shouldBeNull()
		}

		@Test
		fun `handles location without altitude`() = runTest {
			val location = createAndroidLocation(altitude = null)
			val tempData = createCycle(location)
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
			component.onDataUpdated(createCycle(loc1), collectionData1)

			val alt1 = collectionData1.location?.altitude
			alt1.shouldNotBeNull()

			val loc2 = createAndroidLocation(altitude = 600.0, verticalAccuracy = 10f, time = 2000L)
			val collectionData2 = MutableCollectionData()
			component.onDataUpdated(createCycle(loc2), collectionData2)

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
			val tempData = createCycle(location, pressureReading = pressure)
			val collectionData = MutableCollectionData()

			component.onDataUpdated(tempData, collectionData)

			val result = collectionData.location
			result.shouldNotBeNull()
			result.altitude.shouldNotBeNull()
		}

		@Test
		fun `works without barometer data`() = runTest {
			val location = createAndroidLocation(altitude = 500.0, verticalAccuracy = 5f)
			val tempData = createCycle(location, pressureReading = null)
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
			component.onDataUpdated(createCycle(loc1, stablePressure), MutableCollectionData())

			val loc2 = createAndroidLocation(altitude = 500.0, verticalAccuracy = 10f, time = 2000L)
			component.onDataUpdated(createCycle(loc2, stablePressure), MutableCollectionData())

			// GPS spikes while barometer stable
			val loc3 = createAndroidLocation(altitude = 530.0, verticalAccuracy = 10f, time = 3000L)
			val collectionData = MutableCollectionData()
			component.onDataUpdated(createCycle(loc3, stablePressure), collectionData)

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

	@Nested
	@DisplayName("teleport jump detection")
	inner class TeleportJumpDetection {
		@BeforeEach
		fun enable() = runTest {
			component.onEnable(RuntimeEnvironment.getApplication())
		}

		@Test
		fun `rejects cross-continent jump even with large time delta`() = runTest {
			// First fix in New York — establishes lastAcceptedLocation
			val baseTimeNanos = 1_000_000_000_000L
			val nyc = createAndroidLocation(
				latitude = 40.7128, longitude = -74.0060,
				time = 1000L,
			).apply { elapsedRealtimeNanos = baseTimeNanos }
			val tempData1 = createCycle(nyc)
			val collectionData1 = MutableCollectionData()
			component.onDataUpdated(tempData1, collectionData1)
			collectionData1.location.shouldNotBeNull()

			// Second fix in Prague 24 hours later — ~7,800 km jump.
			// Speed = 7,800 km / 24 h = 325 km/h < 500 km/h, so the old speed-only
			// check would have accepted this. The absolute distance guard must catch it.
			val hoursLater24 = baseTimeNanos + 24L * 3600L * 1_000_000_000L
			val prague = createAndroidLocation(
				latitude = 50.0875, longitude = 14.4213,
				time = 1000L + 24L * 3600L * 1000L,
			).apply { elapsedRealtimeNanos = hoursLater24 }
			val tempData2 = createCycle(prague)
			val collectionData2 = MutableCollectionData()
			component.onDataUpdated(tempData2, collectionData2)

			// Location must be rejected
			collectionData2.location.shouldBeNull()
		}

		@Test
		fun `rejects large jump when elapsed time is unknown`() = runTest {
			// First fix with valid elapsedRealtimeNanos
			val loc1 = createAndroidLocation(
				latitude = 40.7128, longitude = -74.0060,
				time = 1000L,
			).apply { elapsedRealtimeNanos = 1_000_000_000_000L }
			component.onDataUpdated(createCycle(loc1), MutableCollectionData().also {
				component.onDataUpdated(createCycle(loc1), it)
			})

			// Second fix with zeroed timestamps — elapsedSecondsBetween returns null
			val loc2 = createAndroidLocation(
				latitude = 50.0875, longitude = 14.4213,
				time = 0L,
			).apply { elapsedRealtimeNanos = 0L }
			val collectionData = MutableCollectionData()
			component.onDataUpdated(createCycle(loc2), collectionData)

			// Must be rejected by the unknown-time distance fallback (>1 km)
			collectionData.location.shouldBeNull()
		}

		@Test
		fun `accepts normal movement within thresholds`() = runTest {
			val baseNanos = 1_000_000_000_000L
			// First fix
			val loc1 = createAndroidLocation(
				latitude = 50.0875, longitude = 14.4213,
				time = 1000L,
			).apply { elapsedRealtimeNanos = baseNanos }
			val cd1 = MutableCollectionData()
			component.onDataUpdated(createCycle(loc1), cd1)
			cd1.location.shouldNotBeNull()

			// Second fix ~100m north, 5 seconds later — normal walking
			val loc2 = createAndroidLocation(
				latitude = 50.0884, longitude = 14.4213,
				time = 6000L,
			).apply { elapsedRealtimeNanos = baseNanos + 5_000_000_000L }
			val cd2 = MutableCollectionData()
			component.onDataUpdated(createCycle(loc2), cd2)
			cd2.location.shouldNotBeNull()
		}

		@Test
		fun `rejects supersonic speed jump`() = runTest {
			val baseNanos = 1_000_000_000_000L
			// First fix
			val loc1 = createAndroidLocation(
				latitude = 50.0, longitude = 14.0,
				time = 1000L,
			).apply { elapsedRealtimeNanos = baseNanos }
			component.onDataUpdated(createCycle(loc1), MutableCollectionData().also {
				component.onDataUpdated(createCycle(loc1), it)
			})

			// Second fix ~5 km away, 1 second later — 18,000 km/h
			val loc2 = createAndroidLocation(
				latitude = 50.045, longitude = 14.0,
				time = 2000L,
			).apply { elapsedRealtimeNanos = baseNanos + 1_000_000_000L }
			val cd = MutableCollectionData()
			component.onDataUpdated(createCycle(loc2), cd)
			cd.location.shouldBeNull()
		}
	}

	@Nested
	@DisplayName("teleport distance leakage fix")
	inner class TeleportDistanceLeakage {
		@BeforeEach
		fun enable() = runTest {
			component.onEnable(RuntimeEnvironment.getApplication())
		}

		@Test
		fun `rejected teleport does not produce location in collectionData`() = runTest {
			val baseNanos = 1_000_000_000_000L

			// First fix in NYC — establishes lastAcceptedLocation
			val nyc = createAndroidLocation(
				latitude = 40.7128, longitude = -74.0060,
				time = 1000L,
			).apply { elapsedRealtimeNanos = baseNanos }
			component.onDataUpdated(createCycle(nyc), MutableCollectionData())

			// Second fix in Tokyo — teleport with precomputed distance (~10,870 km)
			// simulating what LocationCollectionTrigger would produce.
			val tokyo = createAndroidLocation(
				latitude = 35.6762, longitude = 139.6503,
				time = 2000L,
			).apply { elapsedRealtimeNanos = baseNanos + 1_000_000_000L }
			val cycle = createCycleWithPrevious(tokyo, nyc)

			// Precondition: trigger baked in both previousLocation and distance
			cycle.location!!.distance.shouldNotBeNull()
			cycle.location!!.previousLocation.shouldNotBeNull()

			val collectionData = MutableCollectionData()
			component.onDataUpdated(cycle, collectionData)

			// Teleport must be rejected — no location in output
			collectionData.location.shouldBeNull()
		}

		@Test
		fun `rejected teleport rebases lastAcceptedLocation for recovery`() = runTest {
			val baseNanos = 1_000_000_000_000L

			// Establish baseline in NYC
			val nyc = createAndroidLocation(
				latitude = 40.7128, longitude = -74.0060,
				time = 1000L,
			).apply { elapsedRealtimeNanos = baseNanos }
			component.onDataUpdated(createCycle(nyc), MutableCollectionData())

			// Teleport to Tokyo with precomputed distance — rejected
			val tokyo = createAndroidLocation(
				latitude = 35.6762, longitude = 139.6503,
				time = 2000L,
			).apply { elapsedRealtimeNanos = baseNanos + 1_000_000_000L }
			val cd = MutableCollectionData()
			component.onDataUpdated(createCycleWithPrevious(tokyo, nyc), cd)
			cd.location.shouldBeNull()

			// Follow-up near Tokyo 5s later must be accepted (rebased baseline)
			val nearTokyo = createAndroidLocation(
				latitude = 35.6770, longitude = 139.6503,
				time = 7000L,
			).apply { elapsedRealtimeNanos = baseNanos + 6_000_000_000L }
			val cd2 = MutableCollectionData()
			component.onDataUpdated(createCycle(nearTokyo), cd2)
			cd2.location.shouldNotBeNull()
		}

		@Test
		fun `follow-up point after rejected teleport is accepted`() = runTest {
			val baseNanos = 1_000_000_000_000L

			// First fix in NYC
			val nyc = createAndroidLocation(
				latitude = 40.7128, longitude = -74.0060,
				time = 1000L,
			).apply { elapsedRealtimeNanos = baseNanos }
			component.onDataUpdated(createCycle(nyc), MutableCollectionData())

			// Second fix: teleport to Tokyo (rejected)
			val tokyo = createAndroidLocation(
				latitude = 35.6762, longitude = 139.6503,
				time = 2000L,
			).apply { elapsedRealtimeNanos = baseNanos + 1_000_000_000L }
			component.onDataUpdated(
				createCycleWithPrevious(tokyo, nyc),
				MutableCollectionData()
			)

			// Third fix: 100m from the Tokyo teleport point, 5 seconds later.
			// This must be ACCEPTED — tracking should recover by using the
			// teleported location as the new baseline.
			val nearTokyo = createAndroidLocation(
				latitude = 35.6770, longitude = 139.6503,
				time = 7000L,
			).apply { elapsedRealtimeNanos = baseNanos + 6_000_000_000L }
			val cd = MutableCollectionData()
			component.onDataUpdated(createCycle(nearTokyo), cd)
			cd.location.shouldNotBeNull()
		}

		@Test
		fun `normal movement preserves distance in cycle`() = runTest {
			val baseNanos = 1_000_000_000_000L

			// First fix
			val loc1 = createAndroidLocation(
				latitude = 50.0875, longitude = 14.4213,
				time = 1000L,
			).apply { elapsedRealtimeNanos = baseNanos }
			component.onDataUpdated(createCycle(loc1), MutableCollectionData())

			// Second fix ~100m north — normal walking, 5 seconds later
			val loc2 = createAndroidLocation(
				latitude = 50.0884, longitude = 14.4213,
				time = 6000L,
			).apply { elapsedRealtimeNanos = baseNanos + 5_000_000_000L }
			val cycle = createCycleWithPrevious(loc2, loc1)
			val originalDistance = cycle.location!!.distance

			val cd = MutableCollectionData()
			component.onDataUpdated(cycle, cd)

			// Normal movement must be accepted and cycle location data unchanged (immutable)
			cd.location.shouldNotBeNull()
			cycle.location!!.distance shouldBe originalDistance
		}
	}
}
