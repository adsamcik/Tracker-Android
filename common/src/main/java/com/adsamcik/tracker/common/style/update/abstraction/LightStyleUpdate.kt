package com.adsamcik.tracker.common.style.update.abstraction

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.annotation.CallSuper
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.extension.sensorManager
import com.adsamcik.tracker.common.style.update.data.StyleConfigData
import kotlin.concurrent.withLock
import kotlin.math.abs

internal abstract class LightStyleUpdate : StyleUpdate(), SensorEventListener {
	protected var lightSensor: Sensor? = null
		private set

	protected var lastLuminance: Float = Float.MAX_VALUE
		private set

	protected var lastUpdate: Long = 0L
		private set

	protected abstract val minTimeBetweenUpdatesInMs: Long
	protected abstract val requiredLuminanceChange: Float

	protected abstract fun onNewLuminance(newLuminance: Float)

	private fun baseFilter(luminance: Float): Boolean {
		val now = Time.elapsedRealtimeMillis
		return now - minTimeBetweenUpdatesInMs > lastUpdate &&
				abs(lastLuminance - luminance) > requiredLuminanceChange
	}

	protected abstract fun filter(luminance: Float): Boolean

	@CallSuper
	override fun onPostEnable(context: Context, configData: StyleConfigData) {
		val sensorManager = context.sensorManager
		lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
				.also { lightSensor ->
					sensorManager.registerListener(
							this,
							lightSensor,
							SensorManager.SENSOR_DELAY_NORMAL
					)
				}

	}

	@CallSuper
	override fun onPreDisable(context: Context) {
		context.sensorManager.unregisterListener(this)
		lightSensor = null
	}

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

	override fun onSensorChanged(event: SensorEvent) {
		if (event.sensor.type == Sensor.TYPE_LIGHT) {
			val luminance = event.values[0]
			if (baseFilter(luminance) && filter(luminance)) {
				updateLock.withLock {
					if (isEnabled) {
						onNewLuminance(luminance)

						lastLuminance = lastLuminance
						lastUpdate = Time.elapsedRealtimeMillis
					}
				}
			}
		}
	}
}
