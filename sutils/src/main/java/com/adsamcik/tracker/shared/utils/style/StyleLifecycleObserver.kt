package com.adsamcik.tracker.shared.utils.style

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent

/**
 * Style observer that manages enable and disable of style updates.
 */
class StyleLifecycleObserver(private val applicationContext: Context) : LifecycleObserver {

	/**
	 * Called when application moves to foreground.
	 */
	@OnLifecycleEvent(Lifecycle.Event.ON_START)
	fun onMoveToForeground() {
		StyleManager.enableUpdateWithPreference(applicationContext)
	}

	/**
	 * Called when application moves to background.
	 */
	@OnLifecycleEvent(Lifecycle.Event.ON_STOP)
	fun onMoveToBackground() {
		StyleManager.disableUpdate(applicationContext)
	}
}
