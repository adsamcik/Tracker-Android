package com.adsamcik.tracker.tracker.component.trigger

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Collection trigger for AMBIENT mode. Fires at a fixed interval without
 * requiring any permissions (no GPS). Default interval is 60 seconds,
 * responsive enough for policy escalation detection via steps/activity.
 */
internal class AmbientCollectionTrigger(
	dispatcher: CoroutineDispatcher,
) : DynamicIntervalCollectionTrigger {
	override val requiredPermissions: Collection<String> get() = emptyList()

	override val titleRes: Int
		get() = R.string.settings_tracker_timer_ambient

	private var repeatEveryMs: Long = DEFAULT_INTERVAL_MS

	private val scope = CoroutineScope(dispatcher + SupervisorJob())
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
		this.receiver = receiver
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

	companion object {
		/** Default ambient collection interval: 60 seconds. */
		const val DEFAULT_INTERVAL_SECONDS = 60
		private const val DEFAULT_INTERVAL_MS = DEFAULT_INTERVAL_SECONDS * Time.SECOND_IN_MILLISECONDS
	}
}
