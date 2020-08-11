package com.adsamcik.tracker.shared.utils.style

import com.adsamcik.tracker.shared.utils.style.update.data.DefaultColorData

/**
 * Contains information about current color data including default values.
 */
data class ActiveColorData(
		/**
		 * Active color
		 */
		val active: Int,
		/**
		 * Default data for active color
		 */
		val default: DefaultColorData
)
