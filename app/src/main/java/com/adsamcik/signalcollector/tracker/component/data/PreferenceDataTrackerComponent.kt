package com.adsamcik.signalcollector.tracker.component.data

import android.content.Context
import androidx.annotation.CallSuper
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.preference.listener.PreferenceListener

abstract class PreferenceDataTrackerComponent : DataTrackerComponent {
	protected abstract val enabledKeyRes: Int
	protected abstract val enabledDefaultRes: Int

	var enabled: Boolean = false
		private set

	private val enableObserver: Observer<Boolean> = Observer {
		enabled = it
	}

	@CallSuper
	override fun onDisable(context: Context, owner: LifecycleOwner) {
		PreferenceListener.removeObserver(context, enabledKeyRes, enableObserver)
	}

	@CallSuper
	override fun onEnable(context: Context, owner: LifecycleOwner) {
		PreferenceListener.observe(context, enabledKeyRes, defaultRes = enabledDefaultRes, listener = enableObserver)
	}
}