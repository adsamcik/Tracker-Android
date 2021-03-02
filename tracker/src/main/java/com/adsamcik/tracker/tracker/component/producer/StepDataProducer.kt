package com.adsamcik.tracker.tracker.component.producer

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData

internal class StepDataProducer(changeReceiver: TrackerDataProducerObserver) :
		TrackerDataProducerComponent(changeReceiver),
		SensorEventListener {
	private var lastStepCount = -1
	private var stepCountSinceLastCollection = 0

	override val keyRes: Int
		get() = R.string.settings_steps_enabled_key
	override val defaultRes: Int
		get() = R.string.settings_steps_enabled_default

	override fun onDataRequest(tempData: MutableCollectionTempData) {
		if (stepCountSinceLastCollection >= 0) {
			synchronized(stepCountSinceLastCollection) {
				tempData.set(NEW_STEPS_ARG, stepCountSinceLastCollection)
				stepCountSinceLastCollection = 0
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
		val packageManager = context.packageManager
		if (packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)) {
			val sensorManager = context.getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
			val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
			sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
		}
	}

	override fun onSensorChanged(event: SensorEvent) {
		val sensor = event.sensor
		if (sensor.type == Sensor.TYPE_STEP_COUNTER) {
			val stepCount = event.values.first().toInt()
			if (lastStepCount >= 0 && stepCount > 0) {
				synchronized(stepCountSinceLastCollection) {
					//In case sensor would overflow and reset to 0 at some point
					if (lastStepCount > stepCount) {
						this.stepCountSinceLastCollection += stepCount
					} else {
						this.stepCountSinceLastCollection += stepCount - lastStepCount
					}
				}
			}

			lastStepCount = stepCount
		}
	}

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

	companion object {
		const val NEW_STEPS_ARG = "newSteps"
	}
}
