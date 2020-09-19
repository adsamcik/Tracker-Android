package com.adsamcik.tracker.shared.utils.style.update.implementation

import android.content.Context
import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.utils.style.update.abstraction.StyleUpdate
import com.adsamcik.tracker.shared.utils.style.update.data.DefaultColorData
import com.adsamcik.tracker.shared.utils.style.update.data.DefaultColors
import com.adsamcik.tracker.shared.utils.style.update.data.StyleConfigData

internal class SingleColorUpdate : StyleUpdate() {
	override val nameRes: Int
		get() = R.string.settings_color_update_single_title

	override val defaultColors: DefaultColors
		get() = DefaultColors(
				listOf(
						DefaultColorData(
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
