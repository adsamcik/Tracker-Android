package com.adsamcik.tracker.common.style.update

import com.adsamcik.tracker.common.style.SunSetRise

interface StyleUpdate {
	val nameRes: Int
	val requiredColorData: RequiredColors

	val id: String
		get() = this::class.java.simpleName

	fun getUpdateData(
			styleList: List<Int>,
			sunSetRise: SunSetRise
	): UpdateData
}
