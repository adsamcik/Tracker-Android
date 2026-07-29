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
	private val lockObject = Any()
	private var lastStepCount = -1
	private var stepCountSinceLastCollection = 0
	private var stepValueAtCollectionStart: Int = -1
	private var sensorResetDetected: Boolean = false
	private var firstEventElapsedRealtimeNanos: Long? = null
	private var lastEventElapsedRealtimeNanos: Long? = null
	private var firstEventSequence: Long? = null
	private var eventSequence: Long = 0L
	private var sensorManager: SensorManager? = null
	private var batchingEnabled = false
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
				builder.stepWindowStartElapsedRealtimeNanos = firstEventElapsedRealtimeNanos
				builder.stepWindowEndElapsedRealtimeNanos = lastEventElapsedRealtimeNanos
				builder.stepSourceFirstSequence = firstEventSequence
				builder.stepSourceLastSequence = eventSequence
				}
				stepCountSinceLastCollection = 0
				stepValueAtCollectionStart = lastStepCount
				sensorResetDetected = false
				firstEventElapsedRealtimeNanos = null
				lastEventElapsedRealtimeNanos = null
				firstEventSequence = null
				false
			}
		}
		if (invalidCount) {
			Reporter.report("Negative step count since last collection $stepCountSinceLastCollection")
		}
	}

	override suspend fun onDisable(context: Context) {
		try {
			sensorManager?.unregisterListener(this)
			sensorManager = null
		} finally {
			batchingEnabled = false
			synchronized(lockObject) {
				flushCompletion?.cancel()
				flushCompletion = null
				lastStepCount = -1
				stepCountSinceLastCollection = 0
				stepValueAtCollectionStart = -1
				sensorResetDetected = false
				firstEventElapsedRealtimeNanos = null
				lastEventElapsedRealtimeNanos = null
				firstEventSequence = null
				eventSequence = 0L
			}
		}
		super.onDisable(context)
	}

	override suspend fun onEnable(context: Context) {
		val packageManager = context.packageManager
		check(packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)) {
			"Step counter sensor is unavailable"
		}
		val sensorManager = context.getSystemServiceTyped<SensorManager>(Context.SENSOR_SERVICE)
		val stepCounter = checkNotNull(sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)) {
			"Step counter sensor is unavailable"
		}
		val maxReportLatencyUs = SensorBatching.stepCounterMaxReportLatencyUs(stepCounter)
		check(
			sensorManager.registerListener(
				this,
				stepCounter,
				SensorManager.SENSOR_DELAY_NORMAL,
				maxReportLatencyUs,
			),
		) {
			"Unable to register the step counter listener"
		}
		this.sensorManager = sensorManager
		batchingEnabled = maxReportLatencyUs > 0
		super.onEnable(context)
	}

	suspend fun flushPendingEvents() {
		val manager = sensorManager ?: return
		if (!batchingEnabled) return
		val previousCompletion = synchronized(lockObject) { flushCompletion }
		if (previousCompletion != null) {
			val previousCompleted = withTimeoutOrNull(FLUSH_TIMEOUT_MS) {
				previousCompletion.await()
				true
			} == true
			if (!previousCompleted) {
				error("Timed out waiting for the prior batched step flush to settle")
			}
			synchronized(lockObject) {
				if (flushCompletion === previousCompletion) flushCompletion = null
			}
		}

		val completion = CompletableDeferred<Unit>()
		synchronized(lockObject) {
			flushCompletion = completion
		}
		if (!manager.flush(this)) {
			synchronized(lockObject) {
				if (flushCompletion === completion) flushCompletion = null
			}
			error("Step sensor rejected the final batched-event flush")
		}
		val completed = withTimeoutOrNull(FLUSH_TIMEOUT_MS) {
			completion.await()
			true
		} == true
		if (completed) {
			synchronized(lockObject) {
				if (flushCompletion === completion) flushCompletion = null
			}
		}
		check(completed) {
			"Timed out waiting for final batched step events"
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
				eventSequence++
				if (firstEventSequence == null) firstEventSequence = eventSequence
				if (firstEventElapsedRealtimeNanos == null) {
					firstEventElapsedRealtimeNanos = event.timestamp
				}
				lastEventElapsedRealtimeNanos = event.timestamp
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
