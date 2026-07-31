package com.adsamcik.tracker.tracker.component

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import kotlinx.coroutines.Job

internal interface TrackerTimerReceiver {
	/**
	 * Called when update is triggered.
	 * Executed on main thread so longer running tasks should be run on worker thread.
	 */
	@MainThread
	fun onUpdate(cycle: TrackingCycle): Job

	/**
	 * Called when error occurs.
	 * Executed on main thread.
	 */
	@MainThread
	fun onError(errorData: TrackerTimerErrorData)

	/**
	 * Reports provider observability independently of user-facing error handling. The default
	 * keeps non-location timers and lightweight test receivers source-compatible.
	 */
	@MainThread
	fun onLocationProviderAvailabilityChanged(available: Boolean, reason: String) = Unit
}

internal data class TrackerTimerErrorData(
	val severity: TrackerTimerErrorSeverity,
	@StringRes val messageRes: Int,
)

internal enum class TrackerTimerErrorSeverity {
	STOP_SERVICE,
	NOTIFY_USER,
}
