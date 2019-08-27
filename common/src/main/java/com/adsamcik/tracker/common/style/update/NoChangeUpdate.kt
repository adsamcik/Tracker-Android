package com.adsamcik.tracker.common.style.update

import com.adsamcik.tracker.common.style.SunSetRise

class NoChangeUpdate : StyleUpdate {
	override val requiredColorData: RequiredColors
		get() = RequiredColors(emptyList())

	override fun getUpdateData(styleList: List<Int>, sunSetRise: SunSetRise): UpdateData {
		return UpdateData(0, 0, 0, 0)
	}

}
