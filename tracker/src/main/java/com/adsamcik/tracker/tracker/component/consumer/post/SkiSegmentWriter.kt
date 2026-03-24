package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import com.adsamcik.tracker.shared.base.database.data.SkiSegmentType
import com.adsamcik.tracker.logging.api.ReporterFacade
import com.adsamcik.tracker.stats.engine.ski.RealTimeSkiState
import com.adsamcik.tracker.stats.engine.ski.SkiState
import com.adsamcik.tracker.stats.engine.ski.SkiStateListener
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persists real-time ski state segments to the `ski_run_segment` table.
 *
 * Listens for state transitions from [SkiTrackingComponent]'s detector.
 * On each transition, writes the *completed* previous segment as a
 * [SkiRunSegment] row.
 */
internal class SkiSegmentWriter : PostTrackerComponent, SkiStateListener {
	private val dispatchers = DefaultDispatchersProvider
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private lateinit var database: AppDatabase
	private var scope: CoroutineScope? = null
	private var sessionId: Long = 0L

	// Segment tracking
	private var runIndex: Int = 0
	private var segmentStartTimeMs: Long = 0L
	private var segmentState: SkiState = SkiState.IDLE
	private var segmentLiftType: String? = null
	private var segmentStartAltitudeM: Float = 0f
	private var segmentMaxSpeedMps: Float = 0f
	private var segmentSpeedSum: Float = 0f
	private var segmentSpeedSamples: Int = 0
	private var segmentDistanceM: Float = 0f
	private var lastLatitude: Double = 0.0
	private var lastLongitude: Double = 0.0
	private var hasLastLocation: Boolean = false
	private var lastAltitudeM: Float = 0f

	override suspend fun onEnable(context: Context) {
		database = AppDatabase.database(context)
		scope = CoroutineScope(Job() + dispatchers.default)
		runIndex = 0
		segmentStartTimeMs = 0L
		segmentState = SkiState.IDLE
		hasLastLocation = false
	}

	override suspend fun onDisable(context: Context) {
		// Flush the last in-progress segment synchronously
		if (segmentStartTimeMs > 0L && sessionId > 0L) {
			writeSegmentImmediate(Time.nowMillis)
		}
		// Join all in-flight async writes before cancelling
		scope?.coroutineContext?.get(Job)?.children?.toList()?.forEach { it.join() }
		scope?.cancel()
		scope = null
	}

	override fun onNewData(
		context: Context,
		session: TrackerSession,
		collectionData: CollectionData,
		cycle: TrackingCycle
	) {
		sessionId = session.id

		// Accumulate metrics for the current segment
		collectionData.location?.let { loc ->
			val speed = loc.speed ?: 0f
			if (speed > segmentMaxSpeedMps) segmentMaxSpeedMps = speed
			segmentSpeedSum += speed
			segmentSpeedSamples++

			if (hasLastLocation) {
				val results = FloatArray(1)
				android.location.Location.distanceBetween(
					lastLatitude, lastLongitude, loc.latitude, loc.longitude, results
				)
				segmentDistanceM += results[0]
			}
			lastLatitude = loc.latitude
			lastLongitude = loc.longitude
			hasLastLocation = true
			lastAltitudeM = loc.altitude?.toFloat() ?: lastAltitudeM
		}
	}

	override fun onStateChanged(previousState: SkiState, newState: RealTimeSkiState) {
		val now = newState.stateEntryTimeMs

		// Write completed segment (the previousState phase just ended)
		if (segmentStartTimeMs > 0L && sessionId > 0L) {
			writeSegment(now)
		}

		// Start tracking the new segment
		segmentState = newState.state
		segmentStartTimeMs = now
		segmentStartAltitudeM = lastAltitudeM
		segmentLiftType = newState.currentLiftType
		segmentMaxSpeedMps = 0f
		segmentSpeedSum = 0f
		segmentSpeedSamples = 0
		segmentDistanceM = 0f
	}

	private fun writeSegment(endTimeMs: Long) {
		val segment = buildSegment(endTimeMs)
		runIndex++
		scope?.launch(dispatchers.io) {
			try {
				database.skiRunSegmentDao().insert(segment)
			} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
				ReporterFacade.report(e)
			}
		}
	}

	private suspend fun writeSegmentImmediate(endTimeMs: Long) {
		val segment = buildSegment(endTimeMs)
		runIndex++
		kotlinx.coroutines.withContext(dispatchers.io) {
			try {
				database.skiRunSegmentDao().insert(segment)
			} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
				ReporterFacade.report(e)
			}
		}
	}

	private fun buildSegment(endTimeMs: Long): SkiRunSegment {
		val verticalM = segmentStartAltitudeM - lastAltitudeM
		val avgSpeed = if (segmentSpeedSamples > 0) {
			segmentSpeedSum / segmentSpeedSamples
		} else {
			0f
		}

		val segment = SkiRunSegment(
			sessionId = sessionId,
			runIndex = runIndex,
			segmentType = segmentState.toSegmentType(),
			startTimeMs = segmentStartTimeMs,
			endTimeMs = endTimeMs,
			verticalM = verticalM,
			distanceM = segmentDistanceM,
			maxSpeedMps = segmentMaxSpeedMps,
			avgSpeedMps = avgSpeed,
			liftType = if (segmentState == SkiState.LIFT_UP) segmentLiftType else null,
			createdAt = Time.nowMillis
		)

		return segment
	}

	companion object {
		private fun SkiState.toSegmentType(): SkiSegmentType = when (this) {
			SkiState.DOWNHILL_RUN -> SkiSegmentType.DOWNHILL_RUN
			SkiState.LIFT_UP -> SkiSegmentType.LIFT_UP
			SkiState.IDLE -> SkiSegmentType.IDLE
			SkiState.WALK -> SkiSegmentType.WALK
		}
	}
}
