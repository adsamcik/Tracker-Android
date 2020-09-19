package com.adsamcik.tracker.shared.utils.style.marker

import android.view.View

/**
 * Interface that requires recycler to implement onViewChangedListener
 */
interface IViewChange {
	/**
	 * Invoked when view in recycler is changed
	 */
	var onViewChangedListener: ((View) -> Unit)?
}
