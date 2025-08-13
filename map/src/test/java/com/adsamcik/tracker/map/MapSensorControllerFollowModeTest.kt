package com.adsamcik.tracker.map

import androidx.appcompat.widget.AppCompatImageButton
import com.google.android.gms.maps.GoogleMap
import org.junit.Test
import org.junit.Ignore
import org.mockito.kotlin.*

/**
 * Follow mode wiring test for MapSensorController.
 * Validates that enabling follow mode registers a camera move started listener and that a gesture cancels it safely.
 */
class MapSensorControllerFollowModeTest {
    @Ignore("Requires Android framework (Resources & GoogleMap final). Convert to Robolectric/instrumentation later.")
    @Test
    fun followModeEnableAndGestureCancel() {
        val map = mock<GoogleMap> {
            on { cameraPosition } doReturn com.google.android.gms.maps.model.CameraPosition.fromLatLngZoom(
                com.google.android.gms.maps.model.LatLng(0.0,0.0), 16f
            )
        }
        val eventListener = MapEventListener(map)
        // Provide a mocked SensorManager cast-safe; returning null rotation vector implicitly
        val sensorManager = mock<android.hardware.SensorManager> {
            on { getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) } doReturn null
        }
        val context = mock<android.content.Context> {
            on { getSystemService(android.content.Context.SENSOR_SERVICE) } doReturn sensorManager
        }
    val dummyPositionController = mock<MapPositionController> {}
    val controller = MapSensorController(context, map, eventListener, dummyPositionController)
        val button = mock<AppCompatImageButton> { on { context } doReturn context }

        controller.onMyPositionButtonClick(button)

        // Capture and simulate gesture cancellation
        argumentCaptor<GoogleMap.OnCameraMoveStartedListener>().apply {
            verify(map).setOnCameraMoveStartedListener(capture())
            firstValue.onCameraMoveStarted(GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE)
        }

        // Underlying map listener interactions occurred
        verify(map, atLeastOnce()).setOnCameraMoveStartedListener(any())
    }
}
