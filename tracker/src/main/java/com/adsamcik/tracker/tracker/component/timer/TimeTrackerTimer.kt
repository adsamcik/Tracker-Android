package com.adsamcik.tracker.tracker.component.timer

import android.content.Context
import android.os.Handler
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.shared.preferences.Preferences

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.TrackerTimerComponent
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData

internal class TimeTrackerTimer : TrackerTimerComponent {
	override val requiredPermissions: Collection<String> get() = emptyList()

	override val titleRes: Int
		get() = R.string.settings_tracker_timer_clock

	private var repeatEveryMs: Long = -1L
	private val handler = Handler()

	private var receiver: TrackerTimerReceiver? = null

	private val handlerCallback: Runnable = object : Runnable {
		override fun run() {
			this@TimeTrackerTimer.receiver?.onUpdate(createCollectionData())
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

