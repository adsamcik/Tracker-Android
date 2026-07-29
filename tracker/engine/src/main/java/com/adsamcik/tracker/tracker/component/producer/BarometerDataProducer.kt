package com.adsamcik.tracker.tracker.component.producer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.altitude.BarometricAltitudeFormula
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import kotlinx.coroutines.flow.map

/**
 * Produces barometric pressure data from the device pressure sensor.
 * Accumulates and averages pressure readings between collection cycles.
 */
internal class BarometerDataProducer(
	changeReceiver: TrackerDataProducerObserver,
	trackingParamsRepository: TrackingParamsRepository? = null,
) :
		TrackerDataProducerComponent(
			changeReceiver,
			enabledFlow = trackingParamsRepository?.data?.map { it.barometerEnabled },
		),
		SensorEventListener {
	private val lockObject = Any()
	private var pressureSum = 0.0
	private var pressureSquaredDeviationSum = 0.0
	private var sampleCount = 0
	private var minPressureHpa = Float.POSITIVE_INFINITY
	private var maxPressureHpa = Float.NEGATIVE_INFINITY
	private var windowStartElapsedRealtimeNanos: Long? = null
	private var windowEndElapsedRealtimeNanos: Long? = null
	private var firstSourceSequence: Long? = null
	private var sourceSequence = 0L
	private var sensorManager: SensorManager? = null

	override val preferenceKey: String
		get() = PreferenceKeys.BAROMETER_ENABLED
	override val preferenceDefault: Boolean
		get() = PreferenceKeys.BAROMETER_ENABLED_DEFAULT

	override fun onDataRequest(builder: TrackingCycleBuilder) {
		synchronized(lockObject) {
			if (sampleCount > 0) {
				val avgPressure = (pressureSum / sampleCount).toFloat()
				val altitude = pressureToAltitude(avgPressure)
				val variance = if (sampleCount > 1) {
					pressureSquaredDeviationSum / (sampleCount - 1)
				} else {
					0.0
				}
				builder.pressure = PressureReading(
					pressureHpa = avgPressure,
					altitudeM = altitude,
					sampleCount = sampleCount,
					minPressureHpa = minPressureHpa,
					maxPressureHpa = maxPressureHpa,
					standardDeviationHpa = kotlin.math.sqrt(variance).toFloat(),
					windowStartElapsedRealtimeNanos = windowStartElapsedRealtimeNanos,
					windowEndElapsedRealtimeNanos = windowEndElapsedRealtimeNanos,
					sourceFirstSequence = firstSourceSequence,
					sourceLastSequence = sourceSequence,
				)
				clearWindow()
			}
		}
	}

	override suspend fun onDisable(context: Context) {
		try {
			sensorManager?.unregisterListener(this)
			sensorManager = null
		} finally {
			synchronized(lockObject) {
				clearWindow()
				sourceSequence = 0L
			}
		}
		super.onDisable(context)
	}

	override suspend fun onEnable(context: Context) {
		val sensorManager = context.getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
		val pressureSensor = checkNotNull(sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)) {
			"Pressure sensor is unavailable"
		}
		check(
			sensorManager.registerListener(
				this,
				pressureSensor,
				SensorManager.SENSOR_DELAY_NORMAL,
			),
		) {
			"Unable to register the pressure sensor listener"
		}
		this.sensorManager = sensorManager
		super.onEnable(context)
	}

	override fun onSensorChanged(event: SensorEvent) {
		if (event.sensor.type == Sensor.TYPE_PRESSURE) {
			recordPressure(event.values.first(), event.timestamp)
		}
	}

	internal fun recordPressure(
		pressureHpa: Float,
		elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	) {
		if (!BarometricAltitudeFormula.isValidPressure(pressureHpa)) return
		synchronized(lockObject) {
			sourceSequence++
			if (firstSourceSequence == null) firstSourceSequence = sourceSequence
			if (windowStartElapsedRealtimeNanos == null) {
				windowStartElapsedRealtimeNanos = elapsedRealtimeNanos
			}
			windowEndElapsedRealtimeNanos = elapsedRealtimeNanos
			val previousMean = if (sampleCount == 0) 0.0 else pressureSum / sampleCount
			pressureSum += pressureHpa.toDouble()
			sampleCount++
			val newMean = pressureSum / sampleCount
			pressureSquaredDeviationSum +=
				(pressureHpa - previousMean) * (pressureHpa - newMean)
			minPressureHpa = minOf(minPressureHpa, pressureHpa)
			maxPressureHpa = maxOf(maxPressureHpa, pressureHpa)
		}
	}

	private fun clearWindow() {
		pressureSum = 0.0
		pressureSquaredDeviationSum = 0.0
		sampleCount = 0
		minPressureHpa = Float.POSITIVE_INFINITY
		maxPressureHpa = Float.NEGATIVE_INFINITY
		windowStartElapsedRealtimeNanos = null
		windowEndElapsedRealtimeNanos = null
		firstSourceSequence = null
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
 * Aggregate barometric pressure emitted for one collection cycle, with its standard-atmosphere
 * relative-altitude derivation. It is not a raw pressure-sensor event.
 */
data class PressureReading(
	/** Mean valid atmospheric pressure in hectopascals (hPa) across the collection window. */
	val pressureHpa: Float,
	/** Derived altitude in meters (standard atmosphere approximation). */
	val altitudeM: Float,
	val sampleCount: Int = 1,
	val minPressureHpa: Float = pressureHpa,
	val maxPressureHpa: Float = pressureHpa,
	val standardDeviationHpa: Float = 0f,
	val windowStartElapsedRealtimeNanos: Long? = null,
	val windowEndElapsedRealtimeNanos: Long? = null,
	val sourceFirstSequence: Long? = null,
	val sourceLastSequence: Long? = null,
)
