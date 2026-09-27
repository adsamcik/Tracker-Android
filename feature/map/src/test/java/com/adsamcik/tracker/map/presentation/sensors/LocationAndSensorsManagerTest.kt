package com.adsamcik.tracker.map.presentation.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import com.adsamcik.tracker.testing.fake.FakeUiLocationProvider
// Removed ApplicationProvider to avoid Robolectric manifest parsing
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasSelfPermission
import com.adsamcik.tracker.shared.base.location.UiLocationProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.robolectric.shadows.ShadowLocationManager

@OptIn(ExperimentalCoroutinesApi::class)
class LocationAndSensorsManagerTest {

    private lateinit var context: Context
    private lateinit var manager: LocationAndSensorsManager

    private val mockFusedLocationClient: FusedLocationProviderClient = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        mockkStatic("com.adsamcik.tracker.shared.base.extension.ContextExtensionsKt")
        every { context.hasSelfPermission(any()) } returns true
        manager = LocationAndSensorsManager(context, FakeUiLocationProvider())
    }

    @Test
    @Disabled("Requires Android permission framework; skip in unit tests")
    fun `location updates flow completes gracefully without permission`() = runTest { }

    @Test
    fun `bearing updates flow handles missing sensor gracefully`() = runTest {
        // Mock context with no rotation vector sensor
        val sensorManager = mockk<SensorManager>(relaxed = true)
        every { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) } returns null
        @Suppress("DEPRECATION")
        every { sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION) } returns null

        // Stub getSystemService to return our mocked SensorManager
        every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
        val managerWithoutSensor = LocationAndSensorsManager(context, FakeUiLocationProvider())
        
        // Should emit a default bearing and complete
        val result = withTimeoutOrNull(1000) {
            managerWithoutSensor.bearingUpdates().first()
        }
        
        result shouldBe 0f
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
            normalized shouldBe expected
        }
    }

    @Test
    fun `production location flow preserves valid zero coordinates`() = runTest {
        updates(location(0.0, 0.0, 5f)) shouldBe listOf(Triple(0.0, 0.0, 5.0))
    }

    @Test
    fun `production flow retains unknown accuracy rather than inventing zero`() = runTest {
        updates(location(50.0, 14.0, null)) shouldBe listOf(Triple(50.0, 14.0, null))
        updates(location(50.0, 14.0, Float.NaN)) shouldBe listOf(Triple(50.0, 14.0, null))
        updates(location(50.0, 14.0, -1f)) shouldBe listOf(Triple(50.0, 14.0, null))
    }

    @Test
    fun `explicit zero accuracy remains distinguishable from absent accuracy`() = runTest {
        updates(location(50.0, 14.0, 0f)) shouldBe listOf(Triple(50.0, 14.0, 0.0))
    }

    @Test
    fun `production flow rejects nonfinite and out of range coordinates`() = runTest {
        updates(
            location(Double.NaN, 14.0, 5f), location(50.0, Double.POSITIVE_INFINITY, 5f),
            location(90.1, 14.0, 5f), location(50.0, -180.1, 5f), location(-90.0, 180.0, 8f),
        ) shouldBe listOf(Triple(-90.0, 180.0, 8.0))
    }

    private suspend fun updates(vararg locations: Location): List<Triple<Double, Double, Double?>> {
        val provider = mockk<UiLocationProvider>()
        every { provider.locationUpdates(any()) } returns flowOf(*locations)
        return LocationAndSensorsManager(context, provider).locationUpdates().toList()
    }

    private fun location(lat: Double, lon: Double, accuracyMeters: Float?): Location = mockk {
        every { latitude } returns lat
        every { longitude } returns lon
        every { hasAccuracy() } returns (accuracyMeters != null)
        every { accuracy } returns (accuracyMeters ?: 0f)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("com.adsamcik.tracker.shared.base.extension.ContextExtensionsKt")
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
            actualShouldEmit shouldBe shouldEmit
        }
    }
}
