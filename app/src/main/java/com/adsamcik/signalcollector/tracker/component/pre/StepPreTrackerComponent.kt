package com.adsamcik.signalcollector.tracker.component.pre

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import com.adsamcik.signalcollector.common.extension.getSystemServiceTyped
import com.adsamcik.signalcollector.tracker.component.PreTrackerComponent
import com.adsamcik.signalcollector.tracker.data.CollectionTempData
import com.google.android.gms.location.LocationResult

class StepPreTrackerComponent : PreTrackerComponent, SensorEventListener {
	private var lastStepCount = -1
	private var stepCount = -1

	override suspend fun onNewLocation(locationResult: LocationResult, previousLocation: Location?, data: CollectionTempData): Boolean {
		if (lastStepCount >= 0 && stepCount >= 0) {
			val diff = stepCount - lastStepCount
			lastStepCount = stepCount
			data.set(NEW_STEPS_ARG, diff)
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
			if (lastStepCount >= 0) {
				//in case sensor would overflow and reset to 0 at some point
				if (lastStepCount > stepCount) {
					this.stepCount += stepCount
				} else {
					this.stepCount += stepCount - lastStepCount
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