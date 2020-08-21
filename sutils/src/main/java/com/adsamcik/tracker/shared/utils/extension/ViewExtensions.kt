package com.adsamcik.tracker.shared.utils.extension

import android.view.View
import com.adsamcik.tracker.shared.base.assist.Assist

/**
 * Runs function on UI thread. Does not switch threads if already on UI thread.
 */
inline fun View.runOnUiThread(crossinline func: () -> Unit) {
	if (Assist.isMainThread()) {
		func()
	} else {
		post { func() }
	}
}
