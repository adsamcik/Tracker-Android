package com.adsamcik.tracker.shared.utils.style.update.data

import androidx.annotation.StringRes

/**
 * Data class containing default colors. These specify default data for style colors.
 */
data class DefaultColors(
		/**
		 * List of default color data
		 */
		val list: List<DefaultColorData>
)

/**
 * Data class containing data for default colors.
 */
data class DefaultColorData(
		/**
		 * Default color
		 */
		val defaultColor: Int,
		/**
		 * Name resource
		 */
		@StringRes
		val nameRes: Int
)
