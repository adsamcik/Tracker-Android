package com.adsamcik.tracker.map.presentation.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.adsamcik.tracker.shared.base.extension.sensorManager
import com.adsamcik.tracker.shared.base.location.UiLocationProvider
import com.adsamcik.tracker.shared.base.location.UiLocationRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Phase 4: Cold Flow-based manager exposing location and bearing updates with no UI references.
 * Supports both precise and coarse location permissions with adaptive priority.
 */
class LocationAndSensorsManager(
    private val context: Context,
    private val uiLocationProvider: UiLocationProvider,
) {

    /** 
     * Emits triples of (lat, lng, accuracyMeters). Completes when flow is closed.
     * Adapts priority based on granted permissions: high accuracy for precise, balanced for coarse.
     */
    fun locationUpdates(highAccuracy: Boolean = true): Flow<Triple<Double, Double, Double>> {
        if (!context.hasLocationPermission) return emptyFlow()

        return uiLocationProvider.locationUpdates(
            UiLocationRequest(
                tag = "map-location-indicator",
                intervalMillis = LOCATION_UPDATE_INTERVAL_MS,
                highAccuracy = highAccuracy && context.hasPreciseLocationPermission,
            ),
        ).mapNotNull { location ->
            if (location.latitude != 0.0 || location.longitude != 0.0) {
                Triple(
                    location.latitude,
                    location.longitude,
                    if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0,
                )
            } else {
                null
            }
        }
    }

    /** Emits azimuth degrees (0..360) derived from TYPE_ROTATION_VECTOR. */
    fun bearingUpdates(): Flow<Float> = callbackFlow {
        val sm: SensorManager = context.sensorManager
        val rotationVector: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        
        // Fallback to orientation sensor if rotation vector is not available
        val sensor = rotationVector ?: sm.getDefaultSensor(Sensor.TYPE_ORIENTATION)
        
        if (sensor == null) {
            // No suitable sensor available, emit a default bearing and complete
            trySend(0f)
            close()
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var lastEmittedBearing = Float.MIN_VALUE
        
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val bearing: Float = when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        // orientation[0] is azimuth in radians; convert to degrees [0,360)
                        val deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        ((deg + 360f) % 360f)
                    }
                    Sensor.TYPE_ORIENTATION -> {
                        // Deprecated sensor but used as fallback
                        @Suppress("DEPRECATION")
                        val azimuth = event.values[0]
                        ((azimuth + 360f) % 360f)
                    }
                    else -> return
                }
                
                // Debounce: only emit if bearing changed significantly
                if (kotlin.math.abs(bearing - lastEmittedBearing) > 2f) {
                    lastEmittedBearing = bearing
                    trySend(bearing)
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                // Could emit accuracy events if needed in the future
            }
        }

        val success = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        if (!success) {
            close(IllegalStateException("Failed to register sensor listener"))
            return@callbackFlow
        }
        
        awaitClose { 
            try {
                sm.unregisterListener(listener)
            } catch (e: Exception) {
                Reporter.report(e)
            }
        }
    }

    companion object {
        private const val LOCATION_UPDATE_INTERVAL_MS = 2000L
    }
}
