package com.adsamcik.tracker.shared.utils.style.update.data

data class StyleConfigData(
		val preferenceColorList: List<Int>,
		val callback: (currentColor: Int) -> Unit
)
