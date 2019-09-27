package com.adsamcik.tracker.common.style.update

import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.style.SunSetRise

class SingleColorUpdate : StyleUpdate {
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

	override fun getUpdateData(styleList: List<Int>, sunSetRise: SunSetRise): UpdateData {
		return UpdateData(styleList[0], styleList[0], 0L, 0L)
	}
}
