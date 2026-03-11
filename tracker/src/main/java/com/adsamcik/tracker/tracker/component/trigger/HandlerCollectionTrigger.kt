package com.adsamcik.tracker.tracker.component.trigger

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder

/**
 * Collection trigger that uses handler to periodically trigger collections.
 * Supports dynamic interval updates for policy-based adaptation.
 */
internal class HandlerCollectionTrigger : DynamicIntervalCollectionTrigger {
	override val requiredPermissions: Collection<String> get() = emptyList()

	override val titleRes: Int
		get() = R.string.settings_tracker_timer_clock

	private var repeatEveryMs: Long = -1L

	init {
		Assist.ensureLooper()
	}

	private val handler = Handler(requireNotNull(Looper.myLooper()))

	private var receiver: TrackerTimerReceiver? = null

	private val handlerCallback: Runnable = object : Runnable {
		override fun run() {
			this@HandlerCollectionTrigger.receiver?.onUpdate(createCycle())
			handler.postDelayed(this, repeatEveryMs)
		}
	}

	private fun createCycle(): TrackingCycle = TrackingCycleBuilder(
		Time.nowMillis,
		Time.elapsedRealtimeNanos
	).build()

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		val minUpdateDelayInSeconds = BackgroundTrackingApi.cachedParams.minTimeSeconds

		this.receiver = receiver
		repeatEveryMs = minUpdateDelayInSeconds * Time.SECOND_IN_MILLISECONDS
		handler.postDelayed(handlerCallback, repeatEveryMs)
	}

	override fun onDisable(context: Context) {
		handler.removeCallbacks(handlerCallback)
	}

	override fun updateInterval(context: Context, intervalSeconds: Int, minDistanceMeters: Int) {
		// Update interval and restart handler timer
		handler.removeCallbacks(handlerCallback)
		repeatEveryMs = intervalSeconds * Time.SECOND_IN_MILLISECONDS
		handler.postDelayed(handlerCallback, repeatEveryMs)
	}

}

