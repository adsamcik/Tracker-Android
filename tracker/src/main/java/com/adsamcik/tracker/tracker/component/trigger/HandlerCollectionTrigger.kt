package com.adsamcik.tracker.tracker.component.trigger

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.Preferences

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData

/**
 * Collection trigger that uses handler to periodically trigger collections.
 */
internal class HandlerCollectionTrigger : CollectionTriggerComponent {
	override val requiredPermissions: Collection<String> get() = emptyList()

	override val titleRes: Int
		get() = R.string.settings_tracker_timer_clock

	private var repeatEveryMs: Long = -1L

	init {
		Looper.prepare()
	}
	
	private val handler = Handler(requireNotNull(Looper.myLooper()))

	private var receiver: TrackerTimerReceiver? = null

	private val handlerCallback: Runnable = object : Runnable {
		override fun run() {
			this@HandlerCollectionTrigger.receiver?.onUpdate(createCollectionData())
			handler.postDelayed(this, repeatEveryMs)
		}
	}

	private fun createCollectionData(): MutableCollectionTempData = MutableCollectionTempData(
			Time.nowMillis,
			Time.elapsedRealtimeNanos
	)

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		val preferences = Preferences.getPref(context)
		val minUpdateDelayInSeconds = preferences.getIntRes(
				R.string.settings_tracking_min_time_key,
				R.integer.settings_tracking_min_time_default
		)

		this.receiver = receiver
		repeatEveryMs = minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS
		handler.postDelayed(handlerCallback, repeatEveryMs)
	}

	override fun onDisable(context: Context) {
		handler.removeCallbacks(handlerCallback)
	}

}

