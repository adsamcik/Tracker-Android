package com.adsamcik.tracker.shared.base.database.data

/** Retention uses the entire measured window, including saved clock uncertainty. */
object AllDaySensorRetention {
	/** Returns the earliest possible measurement time, or null for an unknown bound. */
	fun activityLowerBound(timeMs: Long, uncertaintyMs: Long?): Long? = uncertaintyMs?.let {
		CanonicalRetentionBounds.walPoint(timeMs, it)
	}

	/** Includes the full measured Pressure window and its clock uncertainty. */
	fun pressureLowerBound(
		timeMs: Long,
		uncertaintyMs: Long?,
		windowStartNanos: Long?,
		windowEndNanos: Long?,
	): Long? {
		if (uncertaintyMs == null || windowStartNanos == null || windowEndNanos == null) {
			return null
		}
		if (windowStartNanos < 0L || windowEndNanos < windowStartNanos) {
			return null
		}
		return CanonicalRetentionBounds.wifiCoverage(timeMs, uncertaintyMs, windowStartNanos, windowEndNanos)
	}

	// These equivalent SQL bounds also protect cleanup and bounded reads before their row limit.
	const val ACTIVITY_LOWER_BOUND_SQL = "CASE WHEN time_ms < 0 OR time_uncertainty_ms IS NULL " +
		"OR time_uncertainty_ms < 0 THEN NULL ELSE MAX(0, time_ms - time_uncertainty_ms) END"
	const val PRESSURE_LOWER_BOUND_SQL = "CASE WHEN time_ms < 0 OR time_uncertainty_ms IS NULL " +
		"OR time_uncertainty_ms < 0 OR time_uncertainty_ms > time_ms OR window_start_elapsed_realtime_nanos IS NULL " +
		"OR window_end_elapsed_realtime_nanos IS NULL OR window_start_elapsed_realtime_nanos < 0 " +
		"OR window_end_elapsed_realtime_nanos < window_start_elapsed_realtime_nanos THEN NULL " +
		"ELSE time_ms - time_uncertainty_ms - " +
		"((window_end_elapsed_realtime_nanos - window_start_elapsed_realtime_nanos) / 1000000 + " +
		"CASE WHEN (window_end_elapsed_realtime_nanos - window_start_elapsed_realtime_nanos) % 1000000 = 0 " +
		"THEN 0 ELSE 1 END) END"
}

/** Earliest possible timestamp for this all-day Activity observation. */
fun ActivitySnapshot.allDayRetentionLowerBoundMs(): Long? =
	AllDaySensorRetention.activityLowerBound(timeMs, observationStamp.timeUncertaintyMs)

/** Earliest possible timestamp within this all-day Pressure window. */
fun PressureSample.allDayRetentionLowerBoundMs(): Long? = AllDaySensorRetention.pressureLowerBound(
	timeMs, observationStamp.timeUncertaintyMs, windowStartElapsedRealtimeNanos, windowEndElapsedRealtimeNanos,
)
