package com.adsamcik.tracker.common.style.update

data class StyleConfigData(
		val preferenceColorList: List<Int>,
		val callback: (currentColor: Int) -> Unit
)
