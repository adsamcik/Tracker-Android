package com.adsamcik.tracker.tracker.api

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped

/**
 * Tracks recent walking via the hardware step counter to corroborate lower-confidence ON_FOOT
 * activity-recognition results.
 *
 * This only ever *adds* confidence to an auto-start decision: a missing step sensor, a batched
 * delivery, or no recent steps simply falls back to the confidence-only path and never blocks a
 * start the confidence threshold alone would have allowed. It is registered only while the
 * confidence-based change-detection API is active (the transition API is already high-confidence)
 * and unregistered when detection stops, to keep battery cost minimal.
 *
 * [elapsedRealtime] is injectable for testing.
 */
internal class StepActivityCorroborator(
	private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : SensorEventListener {
	private val lock = Any()
	@Volatile
	private var registered = false
	@Volatile
	private var lastStepElapsed = NO_STEP
	private var lastCount = -1

	fun start(context: Context) {
		synchronized(lock) {
			if (registered) return
			val packageManager = context.packageManager
			if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)) return
			val sensorManager = context.getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
			val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
			lastCount = -1
			lastStepElapsed = NO_STEP
			sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
			registered = true
		}
	}

	fun stop(context: Context) {
		synchronized(lock) {
			if (!registered) return
			val sensorManager = context.getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
			sensorManager.unregisterListener(this)
			registered = false
		}
	}

	/** True when a step increment was observed within [RECENT_STEP_WINDOW_MS]. */
	fun hasRecentSteps(): Boolean {
		val last = lastStepElapsed
		return last != NO_STEP && (elapsedRealtime() - last) <= RECENT_STEP_WINDOW_MS
	}

	override fun onSensorChanged(event: SensorEvent) {
		if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
		val count = event.values.firstOrNull()?.toInt() ?: return
		synchronized(lock) {
			val previous = lastCount
			lastCount = count
			// Ignore the first reading (baseline) and any counter reset; only a real increment
			// means the user actually stepped.
			if (previous in 0 until count) {
				lastStepElapsed = elapsedRealtime()
			}
		}
	}

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

	companion object {
		private const val NO_STEP = Long.MIN_VALUE
		const val RECENT_STEP_WINDOW_MS = 30_000L
	}
}

/**
 * Pure logic: whether an activity-recognition result should trigger an automatic on-foot start.
 *
 * A confidence at or above [requiredConfidence] always qualifies (unchanged behaviour). Additionally,
 * an ON_FOOT result at or above the lower [corroboratedConfidence] qualifies when the step counter
 * confirms recent walking ([hasRecentSteps]). This only widens when auto-tracking starts; it never
 * blocks a start that the confidence threshold alone would have allowed, and only ON_FOOT benefits
 * (vehicle/bicycle activities do not produce steps).
 */
internal fun isOnFootAutoStartCorroborated(
	groupedActivity: GroupedActivity,
	confidence: Int,
	requiredConfidence: Int,
	corroboratedConfidence: Int,
	hasRecentSteps: Boolean,
): Boolean {
	if (confidence >= requiredConfidence) return true
	return groupedActivity == GroupedActivity.ON_FOOT &&
		confidence >= corroboratedConfidence &&
		hasRecentSteps
}
