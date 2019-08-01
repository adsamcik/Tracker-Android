package com.adsamcik.signalcollector.tracker.component

import android.content.Context
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.common.preference.observer.PreferenceObserver
import com.adsamcik.signalcollector.tracker.data.MutableCollectionTempData

internal abstract class TrackerDataProducerComponent(private val changeReceiver: TrackerDataProducerObserver) {
	private val observer = Observer<Boolean> { changeReceiver.onStateChange(it, this) }

	protected abstract val keyRes: Int
	protected abstract val defaultRes: Int

	fun onEnable(context: Context) {
		PreferenceObserver.observe(context,
				keyRes = keyRes,
				defaultRes = defaultRes,
				observer = observer)
	}

	fun onDisable(context: Context) {
		PreferenceObserver.removeObserver(context, keyRes, observer)
	}


	abstract fun onDataRequest(tempData: MutableCollectionTempData)
}