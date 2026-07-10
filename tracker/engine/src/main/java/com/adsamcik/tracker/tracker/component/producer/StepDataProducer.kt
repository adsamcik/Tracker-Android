package com.adsamcik.tracker.tracker.component.producer

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal class StepDataProducer(
	changeReceiver: TrackerDataProducerObserver,
	trackingParamsRepository: TrackingParamsRepository? = null,
) : TrackerDataProducerComponent(
		changeReceiver,
		enabledFlow = trackingParamsRepository?.data?.map { it.stepsEnabled },
), SensorEventListener2 {
	private val lockObject = Object()
	private var lastStepCount = -1
	private var stepCountSinceLastCollection = 0
	private var stepValueAtCollectionStart: Int = -1
	private var sensorResetDetected: Boolean = false
	private var sensorManager: SensorManager? = null
	private var flushCompletion: CompletableDeferred<Unit>? = null

	override val preferenceKey: String
		get() = PreferenceKeys.STEPS_ENABLED
	override val preferenceDefault: Boolean
		get() = PreferenceKeys.STEPS_ENABLED_DEFAULT

	override fun onDataRequest(builder: TrackingCycleBuilder) {
		val invalidCount = synchronized(lockObject) {
			if (stepCountSinceLastCollection < 0) {
				true
			} else {
				if (stepCountSinceLastCollection > 0) {
				builder.stepDelta = stepCountSinceLastCollection
				builder.totalStepsSinceBoot = if (lastStepCount >= 0) lastStepCount.toLong() else null
				builder.stepSensorValueStart = stepValueAtCollectionStart
				builder.stepSensorValueEnd = lastStepCount
				builder.stepSensorReset = sensorResetDetected
				}
				stepCountSinceLastCollection = 0
				stepValueAtCollectionStart = lastStepCount
				sensorResetDetected = false
				false
			}
		}
		if (invalidCount) {
			Reporter.report("Negative step count since last collection $stepCountSinceLastCollection")
		}
	}

	override fun onDisable(context: Context) {
		super.onDisable(context)
		sensorManager?.unregisterListener(this)
		sensorManager = null
		synchronized(lockObject) {
			flushCompletion?.cancel()
			flushCompletion = null
		}
	}

	override fun onEnable(context: Context) {
		super.onEnable(context)
		val packageManager = context.packageManager
		if (packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)) {
			val sensorManager = context.getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
			this.sensorManager = sensorManager
			val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
				?: return
			sensorManager.registerListener(
				this,
				stepCounter,
				SensorManager.SENSOR_DELAY_NORMAL,
				SensorBatching.stepCounterMaxReportLatencyUs(stepCounter),
			)
		}
	}

	suspend fun flushPendingEvents() {
		val manager = sensorManager ?: return
		val completion = CompletableDeferred<Unit>()
		synchronized(lockObject) {
			flushCompletion?.cancel()
			flushCompletion = completion
		}
		if (!manager.flush(this)) {
			synchronized(lockObject) {
				if (flushCompletion === completion) flushCompletion = null
			}
			return
		}
		withTimeoutOrNull(FLUSH_TIMEOUT_MS) { completion.await() }
		synchronized(lockObject) {
			if (flushCompletion === completion) flushCompletion = null
		}
	}

	override fun onSensorChanged(event: SensorEvent) {
		val sensor = event.sensor
		if (sensor.type == Sensor.TYPE_STEP_COUNTER) {
			val stepCount = event.values.first().toInt()
			// Hold the same monitor used by onDataRequest for BOTH the read of lastStepCount
			// (used in the overflow comparison) AND the write back. The previous version
			// wrote outside the synchronized block, so the collection thread could observe
			// stale lastStepCount under the lock while the sensor thread published a new
			// value without memory barrier — corrupting delta math after an actual reset.
			synchronized(lockObject) {
				if (lastStepCount >= 0 && stepCount > 0) {
					//In case sensor would overflow and reset to 0 at some point
					if (lastStepCount > stepCount) {
						this.stepCountSinceLastCollection += stepCount
						sensorResetDetected = true
					} else {
						this.stepCountSinceLastCollection += stepCount - lastStepCount
					}
				}
				lastStepCount = stepCount
			}
		}
	}

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

	override fun onFlushCompleted(sensor: Sensor?) {
		synchronized(lockObject) {
			flushCompletion?.complete(Unit)
		}
	}

	companion object {
		const val NEW_STEPS_ARG = "newSteps"
		private const val FLUSH_TIMEOUT_MS = 1_000L
	}
}
