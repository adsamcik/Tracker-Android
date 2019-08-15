package com.adsamcik.signalcollector.tracker.component.timer

import android.content.Context
import android.os.Handler
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.tracker.R
import com.adsamcik.signalcollector.tracker.component.TrackerTimerComponent
import com.adsamcik.signalcollector.tracker.component.TrackerTimerReceiver
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionTempData

internal class TimeTrackerTimer : TrackerTimerComponent {
	override val requiredPermissions: Collection<String> get() = emptyList()

	private var repeatEveryMs: Long = -1L
	private val handler = Handler()

	private var receiver: TrackerTimerReceiver? = null

	private val handlerCallback: Runnable = object : Runnable {
		override fun run() {
			val receiver = receiver
			if (receiver != null) {
				receiver.onUpdate(createCollectionData())
				handler.postDelayed(this, repeatEveryMs)
			}
		}
	}

	private fun createCollectionData(): MutableCollectionTempData = MutableCollectionTempData(Time.nowMillis,
			Time.elapsedRealtimeNanos)

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		val preferences = Preferences.getPref(context)
		val minUpdateDelayInSeconds = preferences.getIntRes(R.string.settings_tracking_min_time_key,
				R.integer.settings_tracking_min_time_default)

		repeatEveryMs = minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS
		handler.postDelayed(handlerCallback, repeatEveryMs)
	}

	override fun onDisable(context: Context) {
		handler.removeCallbacks(handlerCallback)
	}

}

