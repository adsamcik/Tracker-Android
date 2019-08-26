package com.adsamcik.tracker.common.style.update

import com.adsamcik.tracker.common.style.SunSetRise

interface StyleUpdate {
	val requiredColorData: RequiredColors

	fun getUpdateData(
			styleList: List<Int>,
			sunSetRise: SunSetRise
	): UpdateData
}
