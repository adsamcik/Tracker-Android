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

	protected var lastLuminance: Float = Float.MIN_VALUE
		private set

	protected var lastLuminancePercentage: Float = Float.MIN_VALUE
		private set

	protected var lastUpdate: Long = 0L
		private set

	protected var maxLuminance: Float = Float.MIN_VALUE
		private set

	protected abstract val minTimeBetweenUpdatesInMs: Long
	protected abstract val requiredChangeForUpdate: Float

	protected abstract fun onNewLuminance(newLuminance: Float, luminancePercentage: Float)

	private fun baseFilter(luminance: Float): Boolean {
		val now = Time.elapsedRealtimeMillis
		val percentage = luminance / maxLuminance
		return now - minTimeBetweenUpdatesInMs > lastUpdate &&
				abs(lastLuminancePercentage - percentage) > requiredChangeForUpdate
	}

	protected abstract fun filter(luminance: Float): Boolean

	@CallSuper
	override fun onPostEnable(context: Context, configData: StyleConfigData) {
		lightSensor = context.sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
				.also { lightSensor ->
					context.sensorManager.registerListener(
							this,
							lightSensor,
							SensorManager.SENSOR_DELAY_NORMAL
					)
					maxLuminance = lightSensor.maximumRange
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
				val luminancePercentage = luminance / maxLuminance
				updateLock.withLock {
					if (isEnabled) {
						onNewLuminance(luminance, luminancePercentage)
					}
				}

				lastLuminancePercentage = luminancePercentage
				lastLuminance = luminance
				lastUpdate = Time.elapsedRealtimeMillis
			}
		}
	}
}
