package com.adsamcik.tracker.common.style.update.implementations

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.graphics.ColorUtils
import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.extension.sensorManager
import com.adsamcik.tracker.common.style.update.RequiredColorData
import com.adsamcik.tracker.common.style.update.RequiredColors
import com.adsamcik.tracker.common.style.update.StyleConfigData
import com.adsamcik.tracker.common.style.update.StyleUpdate
import kotlin.math.abs

internal class LightChangeUpdate : StyleUpdate(), SensorEventListener {
	override val nameRes: Int = R.string.settings_color_update_light_title

	override val requiredColorData: RequiredColors
		get() = RequiredColors(
				listOf(
						RequiredColorData(
								defaultColor = -2031888,
								nameRes = R.string.settings_color_day_title
						),
						RequiredColorData(
								defaultColor = -16315596,
								nameRes = R.string.settings_color_night_title
						)
				)
		)

	private var lightSensor: Sensor? = null

	private var lastLuminance: Float = Float.MIN_VALUE
	private var lastLuminancePercentage: Float = Float.MIN_VALUE

	private var lastUpdate: Long = 0L
	private var maxLuminance: Float = Float.MIN_VALUE

	private fun updateColor(percentage: Float) {
		val color = ColorUtils.blendARGB(colorList[0], colorList[1], percentage)
		requireConfigData().callback(color)
	}

	private fun onNewLuminance(newLuminance: Float) {
		val now = Time.elapsedRealtimeMillis
		val percentage = newLuminance / maxLuminance
		if (now - maxUpdateRateInMillis > lastUpdate &&
				abs(lastLuminancePercentage - percentage) > requiredDifference) {
			lastLuminancePercentage = percentage
			lastLuminance = newLuminance
			lastUpdate = now

			updateColor(percentage)
		}
	}

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

	override fun onPreDisable(context: Context) {
		context.sensorManager.unregisterListener(this)
		lightSensor = null
	}

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

	override fun onSensorChanged(event: SensorEvent) {
		if (event.sensor.type == Sensor.TYPE_LIGHT) {
			onNewLuminance(event.values[0])
		}
	}

	companion object {
		private const val requiredDifference = 0.05f
		private const val maxUpdateRateInMillis = Time.SECOND_IN_MILLISECONDS * 2f
	}
}
