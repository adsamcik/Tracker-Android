package com.adsamcik.tracker.shared.utils.style.update.data

/**
 * Style configuration data
 */
data class StyleConfigData(
		val preferenceColorList: List<Int>,
		val callback: (currentColor: Int) -> Unit
)
