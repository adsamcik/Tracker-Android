package com.adsamcik.tracker.common.style.update.implementation

import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.style.update.abstraction.LightStyleUpdate
import com.adsamcik.tracker.common.style.update.data.RequiredColorData
import com.adsamcik.tracker.common.style.update.data.RequiredColors

internal class LightDayNightSwitchUpdate : LightStyleUpdate() {
	override val minTimeBetweenUpdatesInMs: Long
		get() = Time.SECOND_IN_MILLISECONDS * 2L

	override val requiredLuminanceChange: Float
		get() = 0.05f

	override val nameRes: Int = R.string.settings_color_update_light_switch_title

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

	override fun filter(luminance: Float): Boolean = true

	override fun onNewLuminance(newLuminance: Float) {
		val color = if (newLuminance < 1f) colorList[1] else colorList[0]
		requireConfigData().callback(color)
	}
}
