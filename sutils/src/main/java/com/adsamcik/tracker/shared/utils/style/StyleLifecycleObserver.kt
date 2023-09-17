package com.adsamcik.tracker.shared.utils.style

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Style observer that manages enable and disable of style updates.
 */
class StyleLifecycleObserver(private val applicationContext: Context) : DefaultLifecycleObserver {
	override fun onStart(owner: LifecycleOwner) {
		super.onStart(owner)
		StyleManager.enableUpdateWithPreference(applicationContext)
	}

	override fun onStop(owner: LifecycleOwner) {
		super.onStop(owner)
		StyleManager.disableUpdate(applicationContext)
	}
}
