package com.adsamcik.tracker.common.style.update

import android.content.Context
import com.adsamcik.tracker.common.R

internal class SingleColorUpdate : StyleUpdate() {
	override val nameRes: Int
		get() = R.string.settings_color_update_single_title

	override val requiredColorData: RequiredColors
		get() = RequiredColors(
				listOf(
						RequiredColorData(
								defaultColor = -16619100,
								nameRes = R.string.settings_color_static_title
						)
				)
		)

	override fun onPostEnable(context: Context, configData: StyleConfigData) {
		configData.callback(colorList.first())
	}

	override fun onPreDisable(context: Context) = Unit
}
