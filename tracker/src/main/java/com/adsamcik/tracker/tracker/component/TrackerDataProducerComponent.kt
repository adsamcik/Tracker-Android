package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.annotation.CallSuper
import androidx.lifecycle.Observer
import com.adsamcik.tracker.shared.preferences.observer.PreferenceObserver
import com.adsamcik.tracker.shared.utils.debug.assertTrue
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData

internal abstract class TrackerDataProducerComponent(private val changeReceiver: TrackerDataProducerObserver) {
	private val observer = Observer<Boolean> { changeReceiver.onStateChange(it, this) }

	protected abstract val keyRes: Int
	protected abstract val defaultRes: Int

	var isEnabled: Boolean = false
		private set

	var canBeEnabled: Boolean = false

	fun onAttach(context: Context) {
		PreferenceObserver.observe(
				context,
				keyRes = keyRes,
				defaultRes = defaultRes,
				observer = observer
		)
	}

	fun onDetach(context: Context) {
		PreferenceObserver.removeObserver(context, keyRes, observer)
	}

	@CallSuper
	open fun onEnable(context: Context) {
		assertTrue(canBeEnabled)
		isEnabled = true
	}

	@CallSuper
	open fun onDisable(context: Context) {
		assertTrue(isEnabled)
		isEnabled = false
	}

	abstract fun onDataRequest(tempData: MutableCollectionTempData)
}
