package com.adsamcik.tracker.shared.utils.style.marker

import com.adsamcik.tracker.shared.utils.style.StyleData

/**
 * Interface for Views that support custom style updates via [StyleData]
 */
interface StyleableView {
	/**
	 * Called when app style changes.
	 */
	fun onStyleChanged(styleData: StyleData)
}
