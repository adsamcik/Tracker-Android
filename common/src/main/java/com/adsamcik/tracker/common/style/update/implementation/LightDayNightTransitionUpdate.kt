package com.adsamcik.tracker.common.style.update.implementation

import androidx.core.graphics.ColorUtils
import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.style.update.abstraction.LightStyleUpdate
import com.adsamcik.tracker.common.style.update.data.RequiredColorData
import com.adsamcik.tracker.common.style.update.data.RequiredColors

internal class LightDayNightTransitionUpdate : LightStyleUpdate() {
	override val minTimeBetweenUpdatesInMs: Long
		get() = Time.SECOND_IN_MILLISECONDS * 2L

	override val requiredChangeForUpdate: Float
		get() = 0.05f

	override val nameRes: Int = R.string.settings_color_update_light_transition_title

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

	override fun onNewLuminance(newLuminance: Float, luminancePercentage: Float) {
		val color = ColorUtils.blendARGB(colorList[1], colorList[0], luminancePercentage)
		requireConfigData().callback(color)
	}
}
