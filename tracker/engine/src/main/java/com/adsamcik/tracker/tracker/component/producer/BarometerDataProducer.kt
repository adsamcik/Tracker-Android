package com.adsamcik.tracker.tracker.component.producer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.tracker.altitude.BarometricAltitudeFormula
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder

/**
 * Produces barometric pressure data from the device pressure sensor.
 * Accumulates and averages pressure readings between collection cycles.
 */
internal class BarometerDataProducer(changeReceiver: TrackerDataProducerObserver) :
		TrackerDataProducerComponent(changeReceiver),
		SensorEventListener {
	private val lockObject = Object()
	private var pressureSum = 0.0
	private var sampleCount = 0

	override val preferenceKey: String
		get() = PreferenceKeys.BAROMETER_ENABLED
	override val preferenceDefault: Boolean
		get() = PreferenceKeys.BAROMETER_ENABLED_DEFAULT

	override fun onDataRequest(builder: TrackingCycleBuilder) {
		synchronized(lockObject) {
			if (sampleCount > 0) {
				val avgPressure = (pressureSum / sampleCount).toFloat()
				val altitude = pressureToAltitude(avgPressure)
				builder.pressure = PressureReading(avgPressure, altitude)
				pressureSum = 0.0
				sampleCount = 0
			}
		}
	}

	override fun onDisable(context: Context) {
		super.onDisable(context)
		val sensorManager = context.getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
		sensorManager.unregisterListener(this)
	}

	override fun onEnable(context: Context) {
		super.onEnable(context)
		val sensorManager = context.getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
		val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
		if (pressureSensor != null) {
			sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
		}
	}

	override fun onSensorChanged(event: SensorEvent) {
		if (event.sensor.type == Sensor.TYPE_PRESSURE) {
			recordPressure(event.values.first())
		}
	}

	internal fun recordPressure(pressureHpa: Float) {
		if (!BarometricAltitudeFormula.isValidPressure(pressureHpa)) return
		synchronized(lockObject) {
			pressureSum += pressureHpa.toDouble()
			sampleCount++
		}
	}

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

	companion object {
		const val PRESSURE_KEY = "pressure"

		/** Converts pressure in hPa to altitude in meters using the standard atmosphere formula. */
		fun pressureToAltitude(pressureHpa: Float): Float {
			return BarometricAltitudeFormula.pressureToAltitudeM(pressureHpa)?.toFloat() ?: Float.NaN
		}
	}
}

/**
 * Holds a single pressure reading with derived altitude.
 */
data class PressureReading(
	/** Atmospheric pressure in hectopascals (hPa). */
	val pressureHpa: Float,
	/** Derived altitude in meters (standard atmosphere approximation). */
	val altitudeM: Float
)
