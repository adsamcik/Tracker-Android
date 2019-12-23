package com.adsamcik.tracker.common.style.update.data

data class StyleConfigData(
		val preferenceColorList: List<Int>,
		val callback: (currentColor: Int) -> Unit
)
