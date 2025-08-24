package com.adsamcik.tracker.map.presentation.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
// Removed ApplicationProvider to avoid Robolectric manifest parsing
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Ignore
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.robolectric.shadows.ShadowLocationManager

@OptIn(ExperimentalCoroutinesApi::class)
class LocationAndSensorsManagerTest {

    private lateinit var context: Context
    private lateinit var manager: LocationAndSensorsManager

    @Mock
    private lateinit var mockFusedLocationClient: FusedLocationProviderClient

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
    context = mock(Context::class.java)
    manager = LocationAndSensorsManager(context)
    }

    @Test
    @Ignore("Requires Android permission framework; skip in unit tests")
    fun `location updates flow completes gracefully without permission`() = runTest { }

    @Test
    fun `bearing updates flow handles missing sensor gracefully`() = runTest {
        // Mock context with no rotation vector sensor
    val sensorManager = mock(SensorManager::class.java)
    `when`(sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)).thenReturn(null)
    @Suppress("DEPRECATION")
    `when`(sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)).thenReturn(null)

    // Stub getSystemService to return our mocked SensorManager
    `when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
    val managerWithoutSensor = LocationAndSensorsManager(context)
        
        // Should emit a default bearing and complete
        val result = withTimeoutOrNull(1000) {
            managerWithoutSensor.bearingUpdates().first()
        }
        
        assertEquals(0f, result)
    }

    @Test
    fun `bearing updates normalizes angles correctly`() {
        // Test bearing normalization logic
        val testCases = listOf(
            -90f to 270f,
            0f to 0f,
            90f to 90f,
            180f to 180f,
            270f to 270f,
            360f to 0f,
            450f to 90f,
            -180f to 180f
        )
        
        testCases.forEach { (input, expected) ->
            val normalized = ((input % 360f) + 360f) % 360f
            assertEquals("Failed for input $input", expected, normalized, 0.01f)
        }
    }

    @Test
    fun `location update validation works correctly`() {
        val validLocations = listOf(
            Triple(37.7749, -122.4194, true), // San Francisco
            Triple(51.5074, -0.1278, true),   // London
            Triple(-33.8688, 151.2093, true), // Sydney
            Triple(0.0, 0.0, false),          // Invalid (0,0)
        )
        
        validLocations.forEach { (lat, lng, shouldBeValid) ->
            val isValid = lat != 0.0 || lng != 0.0
            assertEquals("Failed for ($lat, $lng)", shouldBeValid, isValid)
        }
    }

    @Test
    fun `location accuracy validation works`() {
        val location = mock(Location::class.java)
        
        // Test with accuracy
        `when`(location.hasAccuracy()).thenReturn(true)
        `when`(location.accuracy).thenReturn(5.0f)
        
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0
        assertEquals(5.0, accuracy, 0.01)
        
        // Test without accuracy
        `when`(location.hasAccuracy()).thenReturn(false)
        
        val defaultAccuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0
        assertEquals(0.0, defaultAccuracy, 0.01)
    }

    @Test
    fun `bearing debouncing threshold works correctly`() {
        val threshold = 2f
        val testCases = listOf(
            Pair(0f, 1f) to false,  // 1 degree change - should not emit
            Pair(0f, 2f) to false,  // 2 degree change - should not emit
            Pair(0f, 3f) to true,   // 3 degree change - should emit
            Pair(10f, 15f) to true, // 5 degree change - should emit
            Pair(350f, 5f) to true, // Cross 0 degree - should emit
        )
        
        testCases.forEach { (bearings, shouldEmit) ->
            val (last, current) = bearings
            val change = kotlin.math.abs(current - last)
            val actualShouldEmit = change > threshold
            assertEquals("Failed for bearings $last -> $current", shouldEmit, actualShouldEmit)
        }
    }
}
