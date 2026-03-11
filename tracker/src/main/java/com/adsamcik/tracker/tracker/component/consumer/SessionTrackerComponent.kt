package com.adsamcik.tracker.tracker.component.consumer

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.logger.assertMoreOrEqual
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import com.adsamcik.tracker.stats.api.PlausibilityResult
import com.adsamcik.tracker.stats.api.TripPlausibility

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

internal class SessionTrackerComponent(
	private val isUserInitiated: Boolean,
	private val sessionSegmentDao: SessionSegmentDao,
) : DataTrackerComponent,
	CoroutineScope {
	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf()

	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	private val sessionMutex = Mutex()

	private var mutableSession: MutableTrackerSession = MutableTrackerSession(
		Time.nowMillis,
		isUserInitiated
	)

	val session: TrackerSession
		get() = mutableSession

	@Volatile
	var isNewSession: Boolean = false
		private set

	private var minUpdateDelayInSeconds = -1
	private var minDistanceInMeters = -1
	private var collectedLocationCount = 0
	private val preferenceJobs = mutableListOf<Job>()

	override suspend fun onDataUpdated(
		cycle: TrackingCycle,
		collectionData: MutableCollectionData
	) {
		sessionMutex.withLock {
			mutableSession.run {
				val locationData = cycle.location
				if (locationData != null) {
					collectedLocationCount++
				}
				val distance = locationData?.distance
				distance?.let {
					distanceInM += it

					cycle.activity?.let { activity ->
						validateActivity(
							distance, cycle.elapsedRealtimeNanos,
							activity.groupedActivity
						)
					}
				}

				collections++
				end = Time.nowMillis

				cycle.stepDelta?.let { newSteps ->
					assertMoreOrEqual(newSteps, 0)
					steps += newSteps
				}

				withContext(coroutineContext) {
					upsertSessionSegment(this@run)
				}
			}
		}
	}

	private fun MutableTrackerSession.validateActivity(
		distance: Float,
		elapsedRealtimeNanos: Long,
		groupedActivity: GroupedActivity
	) {
		if (elapsedRealtimeNanos < max(
				Time.SECOND_IN_NANOSECONDS * 20,
				minUpdateDelayInSeconds * 2 * Time.SECOND_IN_NANOSECONDS
			) ||
			distance <= minDistanceInMeters * 2f
		) {

			when (groupedActivity) {
				GroupedActivity.ON_FOOT -> distanceOnFootInM += distance
				GroupedActivity.IN_VEHICLE -> distanceInVehicleInM += distance
				else -> {
				}
			}
		}
	}

	override suspend fun onDisable(context: Context) {
		preferenceJobs.forEach(Job::cancel)
		preferenceJobs.clear()

		sessionMutex.withLock {
			mutableSession.apply {
				end = Time.nowMillis
			}

			withContext(coroutineContext) {
				if (isNewSession && collectedLocationCount == 0) {
					// Rapid start/stop produced no GPS points — remove the pre-inserted row.
					if (mutableSession.id > 0L) {
						sessionSegmentDao.deleteById(mutableSession.id)
					}
				} else {
					upsertSessionSegment(mutableSession)
				}
			}
		}
		job.cancel()
	}

	private suspend fun upsertSessionSegment(session: TrackerSession) {
		if (session.end < session.start) return

		val durationMs = session.end - session.start
		val hasAnomaly = TripPlausibility.evaluate(
			distanceM = session.distanceInM,
			durationMs = durationMs,
			activityType = null,
		) is PlausibilityResult.Implausible

		val segment = SessionSegment(
			id = if (session.id > 0L) session.id else 0,
			startTimeMs = session.start,
			endTimeMs = session.end,
			distanceM = session.distanceInM,
			steps = session.steps,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = session.collections,
			source = if (isUserInitiated) SegmentSource.USER_CREATED else SegmentSource.INFERRED_HIGH_CONFIDENCE,
			inferenceVersion = "tracker_v2",
			createdAt = Time.nowMillis,
			hasDistanceAnomaly = hasAnomaly,
		)

		if (session.id > 0L) {
			sessionSegmentDao.update(segment)
		} else {
			val insertedId = sessionSegmentDao.insert(segment)
			if (insertedId > 0L) {
				session.id = insertedId
			}
		}
	}

	override suspend fun onEnable(context: Context) {
		val prefs = Preferences.getPref(context)
		minDistanceInMeters = prefs.fetchIntRes(
			com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_min_distance_key,
			com.adsamcik.tracker.shared.preferences.R.integer.settings_tracking_min_distance_default
		)
		minUpdateDelayInSeconds = prefs.fetchIntRes(
			com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_min_time_key,
			com.adsamcik.tracker.shared.preferences.R.integer.settings_tracking_min_time_default
		)

		preferenceJobs += PreferenceFlows.int(
			context,
			com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_min_distance_key,
			com.adsamcik.tracker.shared.preferences.R.integer.settings_tracking_min_distance_default
		).onEach { minDistanceInMeters = it }
			.launchIn(this)

		preferenceJobs += PreferenceFlows.int(
			context,
			com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_min_time_key,
			com.adsamcik.tracker.shared.preferences.R.integer.settings_tracking_min_time_default
		).onEach { minUpdateDelayInSeconds = it }
			.launchIn(this)

		withContext(coroutineContext) {
			initializeSession()
		}
	}

	@WorkerThread
	private suspend fun initializeSession() {
		val now = Time.nowMillis
		// Always start a new session segment — resume logic is handled at the
		// SessionSegment level (the segment is upserted on every update).
		val session = MutableTrackerSession(now, isUserInitiated)
		val segment = SessionSegment(
			id = 0,
			startTimeMs = now,
			endTimeMs = now,
			distanceM = 0f,
			steps = 0,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 0,
			source = if (isUserInitiated) SegmentSource.USER_CREATED else SegmentSource.INFERRED_HIGH_CONFIDENCE,
			inferenceVersion = "tracker_v2",
			createdAt = now,
			hasDistanceAnomaly = false,
		)
		session.id = sessionSegmentDao.insert(segment)
		mutableSession = session
		isNewSession = true
	}

	companion object {
		const val SESSION_RESUME_TIMEOUT = 15 * Time.MINUTE_IN_MILLISECONDS
	}
}
