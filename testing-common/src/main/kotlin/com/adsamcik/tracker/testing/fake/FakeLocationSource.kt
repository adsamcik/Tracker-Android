package com.adsamcik.tracker.testing.fake

import android.location.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fake location source for testing location-dependent code without Android.
 *
 * Emits locations via a Flow that tests can subscribe to, and provides
 * helper methods to emit synthetic location events.
 *
 * Usage:
 * ```kotlin
 * @Test
 * fun `test location tracking`() = runTest {
 *     val fakeLocationSource = FakeLocationSource()
 *     val tracker = LocationTracker(fakeLocationSource)
 *
 *     tracker.start()
 *
 *     // Emit synthetic locations
 *     fakeLocationSource.emitLocation(lat = 37.7749, lon = -122.4194, accuracy = 10f)
 *     fakeLocationSource.emitLocation(lat = 37.7750, lon = -122.4195, accuracy = 10f)
 *
 *     // Verify tracker received and processed locations
 *     tracker.locationCount shouldBe 2
 * }
 * ```
 *
 * Note: This class provides Location objects but some fields may be stubbed.
 * For tests that need MockK-based Location mocking, use the helper factory methods.
 */
class FakeLocationSource {

    private val _locations = MutableSharedFlow<Location>(extraBufferCapacity = 10)

    /** Flow of location events. Subscribe to receive emitted locations. */
    val locations: Flow<Location> = _locations.asSharedFlow()

    /** List of all locations emitted (for assertions). */
    private val _emittedLocations = mutableListOf<Location>()
    val emittedLocations: List<Location> get() = _emittedLocations.toList()

    /**
     * Emit a location event.
     *
     * @param lat Latitude in degrees
     * @param lon Longitude in degrees
     * @param accuracy Accuracy in meters
     * @param time Timestamp in milliseconds (defaults to current time)
     * @param altitude Altitude in meters (optional)
     * @param speed Speed in m/s (optional)
     * @param bearing Bearing in degrees (optional)
     * @param provider Location provider name (defaults to "fused")
     */
    suspend fun emitLocation(
        lat: Double,
        lon: Double,
        accuracy: Float = 10f,
        time: Long = System.currentTimeMillis(),
        altitude: Double? = null,
        speed: Float? = null,
        bearing: Float? = null,
        provider: String = "fused"
    ) {
        val location = createLocation(lat, lon, accuracy, time, altitude, speed, bearing, provider)
        _emittedLocations.add(location)
        _locations.emit(location)
    }

    /**
     * Emit multiple locations in sequence.
     */
    suspend fun emitLocations(locations: List<LocationData>) {
        locations.forEach { data ->
            emitLocation(
                lat = data.lat,
                lon = data.lon,
                accuracy = data.accuracy,
                time = data.time,
                altitude = data.altitude,
                speed = data.speed,
                bearing = data.bearing,
                provider = data.provider
            )
        }
    }

    /** Clear emission history. */
    fun clear() {
        _emittedLocations.clear()
    }

    /**
     * Simple data class for bulk location emission.
     */
    data class LocationData(
        val lat: Double,
        val lon: Double,
        val accuracy: Float = 10f,
        val time: Long = System.currentTimeMillis(),
        val altitude: Double? = null,
        val speed: Float? = null,
        val bearing: Float? = null,
        val provider: String = "fused"
    )

    companion object {
        /**
         * Create a Location object (works in Robolectric or real Android).
         * For pure JVM tests without Robolectric, this will throw - use MockK instead.
         */
        fun createLocation(
            lat: Double,
            lon: Double,
            accuracy: Float = 10f,
            time: Long = System.currentTimeMillis(),
            altitude: Double? = null,
            speed: Float? = null,
            bearing: Float? = null,
            provider: String = "fused"
        ): Location {
            return Location(provider).apply {
                latitude = lat
                longitude = lon
                this.accuracy = accuracy
                this.time = time
                altitude?.let { this.altitude = it }
                speed?.let { this.speed = it }
                bearing?.let { this.bearing = it }
            }
        }

        /**
         * Creates a domain Location that works on pure JVM (no Robolectric needed).
         * Use this for pure JUnit 5 tests.
         */
        fun createDomainLocation(
            latitude: Double = 0.0,
            longitude: Double = 0.0,
            altitude: Double? = null,
            accuracy: Float? = null,
            speed: Float? = null,
            time: Long = System.currentTimeMillis(),
        ): com.adsamcik.tracker.shared.base.data.Location {
            return com.adsamcik.tracker.shared.base.data.Location(
                time = time,
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                horizontalAccuracy = accuracy,
                verticalAccuracy = null,
                speed = speed,
                speedAccuracy = null,
            )
        }

        /**
         * Generate a path of locations between two points.
         *
         * @param start Starting coordinates (lat, lon)
         * @param end Ending coordinates (lat, lon)
         * @param count Number of points to generate
         * @param intervalMs Time interval between points in milliseconds
         * @param startTime Starting timestamp
         */
        fun generatePath(
            start: Pair<Double, Double>,
            end: Pair<Double, Double>,
            count: Int,
            intervalMs: Long = 1000,
            startTime: Long = System.currentTimeMillis()
        ): List<LocationData> {
            val latStep = (end.first - start.first) / (count - 1)
            val lonStep = (end.second - start.second) / (count - 1)

            return (0 until count).map { i ->
                LocationData(
                    lat = start.first + (latStep * i),
                    lon = start.second + (lonStep * i),
                    time = startTime + (intervalMs * i)
                )
            }
        }
    }
}
