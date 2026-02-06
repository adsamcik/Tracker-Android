package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step interval writer for sessionless architecture.
 * Records step counter deltas between collection cycles.
 *
 * Contract:
 * - Tracks raw sensor values to detect resets
 * - Writes interval records (start → end with step count)
 * - Handles sensor resets gracefully
 *
 * TODO: DI Migration - This PostTrackerComponent is instantiated by TrackerService.
 *  Future refactor: Accept StepIntervalDao via constructor for testability.
 *  See Section 16A of copilot-instructions.md for DI composition patterns.
 */
internal class StepIntervalWriter : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList() // Optional data

	private lateinit var database: AppDatabase
	private var scope: CoroutineScope? = null
	
	private var lastStepCount: Int = 0
	private var lastStepTime: Long = 0L
	private var lastSensorValue: Int = 0
	private var initialized: Boolean = false

	override suspend fun onEnable(context: Context) {
		database = AppDatabase.database(context)
		scope = CoroutineScope(Job() + Dispatchers.Default)
		
		// Try to resume from last interval
		withContext(Dispatchers.IO) {
			database.stepIntervalDao().getLatest()?.let { lastInterval ->
				lastStepCount = lastInterval.stepCount
				lastStepTime = lastInterval.endTimeMs
				lastSensorValue = lastInterval.sensorValueEnd
				initialized = true
			}
		}
	}

	override suspend fun onDisable(context: Context) {
		// Optionally flush final interval
		initialized = false
		scope?.cancel()
		scope = null
	}

	override fun onNewData(
		context: Context,
		session: com.adsamcik.tracker.shared.base.data.TrackerSession,
		collectionData: CollectionData,
		tempData: com.adsamcik.tracker.tracker.data.collection.CollectionTempData
	) {
		val currentTime = Time.nowMillis
		// Get step count from session object (which accumulates steps)
		val currentSensorValue = session.steps
		
		if (!initialized) {
			// First reading - initialize state
			lastSensorValue = currentSensorValue
			lastStepTime = currentTime
			lastStepCount = 0
			initialized = true
			return
		}

		// Detect sensor reset
		val sensorReset = currentSensorValue < lastSensorValue

		// Calculate step delta
		val stepDelta = if (sensorReset) {
			// Sensor reset - use current value as delta (assuming reset to 0)
			currentSensorValue
		} else {
			currentSensorValue - lastSensorValue
		}

		// Only write if we have a positive delta
		if (stepDelta > 0) {
			val interval = StepInterval(
				startTimeMs = lastStepTime,
				endTimeMs = currentTime,
				stepCount = stepDelta,
				sensorValueStart = lastSensorValue,
				sensorValueEnd = currentSensorValue,
				sensorReset = sensorReset,
				createdAt = currentTime
			)

			scope?.launch(Dispatchers.IO) {
				database.stepIntervalDao().insert(interval)
			}

			lastStepCount += stepDelta
		}

		// Update state for next interval
		lastSensorValue = currentSensorValue
		lastStepTime = currentTime
	}
}
