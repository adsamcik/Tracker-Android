package com.adsamcik.tracker.shared.utils.style.update.implementation

import androidx.core.graphics.ColorUtils
import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.utils.style.update.abstraction.LightStyleUpdate
import com.adsamcik.tracker.shared.utils.style.update.data.RequiredColorData
import com.adsamcik.tracker.shared.utils.style.update.data.RequiredColors
import com.adsamcik.tracker.shared.utils.style.utility.ColorFunctions
import kotlin.math.min

internal class LightDayNightTransitionUpdate : LightStyleUpdate() {
	override val minTimeBetweenUpdatesInMs: Long
		get() = Time.SECOND_IN_MILLISECONDS * 2L

	override val requiredLuminanceChange: Float
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

	private var lastColor: Int = 0

	override fun filter(luminance: Float): Boolean = true

	override fun onNewLuminance(newLuminance: Float) {
		val customLuminancePercentage = min(MAX_BRIGHTNESS_LUM, newLuminance) / MAX_BRIGHTNESS_LUM
		val transformedPercentage = customLuminancePercentage * customLuminancePercentage
		val color = ColorUtils.blendARGB(colorList[1], colorList[0], transformedPercentage)
		if (ColorFunctions.distance(lastColor, color) > COLOR_DIFFERENCE_THRESHOLD) {
			requireConfigData().callback(color)
		}
	}

	companion object {
		private const val MAX_BRIGHTNESS_LUM = 150f
		private const val COLOR_DIFFERENCE_THRESHOLD = 50
	}
}
