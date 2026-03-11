package com.adsamcik.tracker.tracker.component.trigger

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder

/**
 * Collection trigger for AMBIENT mode. Fires at a fixed interval without
 * requiring any permissions (no GPS). Default interval is 60 seconds,
 * responsive enough for policy escalation detection via steps/activity.
 */
internal class AmbientCollectionTrigger : DynamicIntervalCollectionTrigger {
	override val requiredPermissions: Collection<String> get() = emptyList()

	override val titleRes: Int
		get() = R.string.settings_tracker_timer_ambient

	private var repeatEveryMs: Long = DEFAULT_INTERVAL_MS

	init {
		Assist.ensureLooper()
	}

	private val handler = Handler(requireNotNull(Looper.myLooper()))

	private var receiver: TrackerTimerReceiver? = null

	private val handlerCallback: Runnable = object : Runnable {
		override fun run() {
			this@AmbientCollectionTrigger.receiver?.onUpdate(createCycle())
			handler.postDelayed(this, repeatEveryMs)
		}
	}

	private fun createCycle(): TrackingCycle = TrackingCycleBuilder(
		Time.nowMillis,
		Time.elapsedRealtimeNanos
	).build()

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		this.receiver = receiver
		handler.postDelayed(handlerCallback, repeatEveryMs)
	}

	override fun onDisable(context: Context) {
		handler.removeCallbacks(handlerCallback)
		receiver = null
	}

	override fun updateInterval(context: Context, intervalSeconds: Int, minDistanceMeters: Int) {
		handler.removeCallbacks(handlerCallback)
		repeatEveryMs = intervalSeconds * Time.SECOND_IN_MILLISECONDS
		handler.postDelayed(handlerCallback, repeatEveryMs)
	}

	companion object {
		/** Default ambient collection interval: 60 seconds. */
		const val DEFAULT_INTERVAL_SECONDS = 60
		private const val DEFAULT_INTERVAL_MS = DEFAULT_INTERVAL_SECONDS * Time.SECOND_IN_MILLISECONDS
	}
}
