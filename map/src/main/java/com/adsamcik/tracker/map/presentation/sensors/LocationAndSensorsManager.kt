package com.adsamcik.tracker.map.presentation.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import androidx.annotation.RequiresPermission
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.sensorManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Phase 4: Cold Flow-based manager exposing location and bearing updates with no UI references.
 */
class LocationAndSensorsManager(private val context: Context) {

    /** Emits triples of (lat, lng, accuracyMeters). Completes when flow is closed. */
    fun locationUpdates(highAccuracy: Boolean = true): Flow<Triple<Double, Double, Double>> = callbackFlow {
        if (!context.hasLocationPermission) {
            close(IllegalStateException("Missing location permission"))
            return@callbackFlow
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        val req = LocationRequest.Builder(LOCATION_UPDATE_INTERVAL_MS)
            .setPriority(if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val loc: Location = locationResult.lastLocation ?: return
                trySend(Triple(loc.latitude, loc.longitude, loc.accuracy.toDouble()))
            }
        }

        Assist.ensureLooper()
        @Suppress("MissingPermission")
        val task = client.requestLocationUpdates(req, callback, requireNotNull(android.os.Looper.myLooper()))

        awaitClose {
            client.removeLocationUpdates(callback)
        }
    }

    /** Emits azimuth degrees (0..360) derived from TYPE_ROTATION_VECTOR. */
    fun bearingUpdates(): Flow<Float> = callbackFlow {
        val sm: SensorManager = context.sensorManager
        val rotationVector: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVector == null) {
            close(IllegalStateException("Rotation vector sensor not available"))
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                // orientation[0] is azimuth in radians; convert to degrees [0,360)
                val deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                val norm = ((deg + 360f) % 360f)
                trySend(norm)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        sm.registerListener(listener, rotationVector, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sm.unregisterListener(listener) }
    }

    companion object {
        private const val LOCATION_UPDATE_INTERVAL_MS = 2000L
    }
}
