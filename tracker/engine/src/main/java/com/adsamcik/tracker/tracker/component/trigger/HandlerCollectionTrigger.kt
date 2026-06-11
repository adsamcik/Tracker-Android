package com.adsamcik.tracker.tracker.component.trigger

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Collection trigger that uses coroutines to periodically trigger collections.
 * Supports dynamic interval updates for policy-based adaptation.
 */
internal class HandlerCollectionTrigger : DynamicIntervalCollectionTrigger {
	override val requiredPermissions: Collection<String> get() = emptyList()

	override val titleRes: Int
		get() = R.string.settings_tracker_timer_clock

	private var repeatEveryMs: Long = -1L

	private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
	private var timerJob: Job? = null

	@Volatile
	private var receiver: TrackerTimerReceiver? = null

	private fun startTimer() {
		timerJob?.cancel()
		timerJob = scope.launch {
			while (true) {
				delay(repeatEveryMs)
				receiver?.onUpdate(createCycle())
			}
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
		startTimer()
	}

	override fun onDisable(context: Context) {
		timerJob?.cancel()
		timerJob = null
		receiver = null
	}

	override fun updateInterval(context: Context, intervalSeconds: Int, minDistanceMeters: Int) {
		repeatEveryMs = intervalSeconds * Time.SECOND_IN_MILLISECONDS
		startTimer()
	}

}
