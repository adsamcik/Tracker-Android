package com.adsamcik.tracker.tracker.component.consumer.pre

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.adsamcik.tracker.common.extension.getSystemServiceTyped
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData

internal class StepPreTrackerComponent : PreTrackerComponent, SensorEventListener {
	override val requiredData: Collection<TrackerComponentRequirement> = listOf()
	private var lastStepCount = -1
	private var stepCountSinceLastCollection = 0

	override suspend fun onNewData(data: MutableCollectionTempData): Boolean {
		if (stepCountSinceLastCollection >= 0) {
			synchronized(stepCountSinceLastCollection) {
				data.set(NEW_STEPS_ARG, stepCountSinceLastCollection)
				stepCountSinceLastCollection = 0
			}
		}
		return true
	}

	override suspend fun onDisable(context: Context) {
		val sensorManager = context.getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
		sensorManager.unregisterListener(this)
	}

	override suspend fun onEnable(context: Context) {
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

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

	companion object {
		const val NEW_STEPS_ARG = "newSteps"
	}
}
