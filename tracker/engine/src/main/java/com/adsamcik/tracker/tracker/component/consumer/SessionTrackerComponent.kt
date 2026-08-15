package com.adsamcik.tracker.tracker.component.consumer

import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import dev.tracebox.Tracebox
import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PlausibilityResult
import com.adsamcik.tracker.stats.api.TripPlausibility

import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

internal class SessionTrackerComponent(
	private val isUserInitiated: Boolean,
	private val sessionSegmentDao: SessionSegmentDao,
	private val trackingParamsRepository: TrackingParamsRepository? = null,
	private val resumeSessionSegmentId: Long? = null,
) : DataTrackerComponent,
	CoroutineScope {
	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf()

	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = DefaultDispatchersProvider.default + job

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
	private var collectedActivityCount = 0
	private var collectedPressureCount = 0
	private val preferenceJobs = mutableListOf<Job>()
	private val activityEvidence = mutableMapOf<Int, ActivityEvidence>()
	private var restoredDominantActivity: Pair<Int, Int>? = null
	private var sessionCreatedAt: Long = Time.nowMillis
	private data class ActivityEvidence(var confidenceScore: Long = 0L, var confidenceTotal: Long = 0L, var observations: Int = 0)

	override suspend fun onDataUpdated(
		cycle: TrackingCycle,
		collectionData: MutableCollectionData
	) {
		sessionMutex.withLock {
			mutableSession.run {
				if (cycle.activityFresh) {
					cycle.activity?.let { activity ->
						collectedActivityCount++
						recordActivityEvidence(activity)
					}
				}
				if (collectionData.location != null) {
					collectedLocationCount++
				}
				if (cycle.pressure != null) {
					collectedPressureCount++
				}
				// Accumulate the distance produced by LocationTrackerComponent
				// (collectionData.distanceFromPreviousM), which is measured from the last
				// *accepted* location. Ordinary quality-gated cycles can be bridged, but a
				// confirmed teleport re-acquisition explicitly contributes zero cross-gap
				// distance so a session never invents a route through an unknown interval.
				val locationAccepted = collectionData.location != null
				val distance = if (locationAccepted) collectionData.distanceFromPreviousM else null
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
					if (newSteps < 0) {
						Tracebox.log.warn(TrackerTraceboxTemplates.STEP_COUNTER_REGRESSED)
					}
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
				else -> Unit
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
				if (isNewSession &&
					collectedLocationCount == 0 &&
					collectedActivityCount == 0 &&
					collectedPressureCount == 0 &&
					mutableSession.steps == 0
				) {
					// Remove the pre-inserted row only when the session produced no persistable
					// data of its own — no accepted location fixes, fresh activity snapshots,
					// pressure readings, or steps. This still cleans up rapid start/stop (and
					// location sessions that never got a usable fix) while preserving intentional
					// activity-only, pressure-only, and steps-only sessions. Wi-Fi and cell
					// observations are persisted in their own tables.
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

		val dominantActivity = dominantActivity()
		val durationMs = session.end - session.start
		val hasAnomaly = TripPlausibility.evaluate(
			distanceM = session.distanceInM,
			durationMs = durationMs,
			activityType = dominantActivity?.first?.toDetectedActivityType(),
		) is PlausibilityResult.Implausible

		val segment = SessionSegment(
			id = if (session.id > 0L) session.id else 0,
			startTimeMs = session.start,
			endTimeMs = session.end,
			distanceM = session.distanceInM,
			steps = session.steps,
			primaryActivity = dominantActivity?.first,
			activityConfidence = dominantActivity?.second,
			sampleCount = session.collections,
			source = if (isUserInitiated) SegmentSource.USER_CREATED else SegmentSource.INFERRED_HIGH_CONFIDENCE,
			inferenceVersion = "tracker_v2",
			createdAt = sessionCreatedAt,
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

	private fun recordActivityEvidence(activity: ActivityInfo) {
		if (activity.activityType !in MOVEMENT_ACTIVITY_IDS) return
		val confidence = activity.confidence.coerceIn(0, 100)
		val evidence = activityEvidence.getOrPut(activity.activityType) { ActivityEvidence() }
		// Only fresh recognizer updates reach this method. Confidence weighting
		// avoids a single weak classification replacing sustained strong evidence.
		evidence.confidenceScore += confidence.coerceAtLeast(1)
		evidence.confidenceTotal += confidence
		evidence.observations++
	}

	private fun dominantActivity(): Pair<Int, Int>? = activityEvidence
		.maxWithOrNull(
			compareBy<Map.Entry<Int, ActivityEvidence>> { it.value.confidenceScore }
				.thenBy { it.value.observations }
				.thenBy { it.key },
		)
		?.let { (activityType, evidence) ->
			activityType to (evidence.confidenceTotal / evidence.observations.coerceAtLeast(1)).toInt()
		} ?: restoredDominantActivity

	private fun Int.toDetectedActivityType(): DetectedActivityType? = when (this) {
		DetectedActivity.WALKING.value -> DetectedActivityType.WALKING
		DetectedActivity.RUNNING.value -> DetectedActivityType.RUNNING
		DetectedActivity.ON_FOOT.value -> DetectedActivityType.ON_FOOT
		DetectedActivity.ON_BICYCLE.value -> DetectedActivityType.ON_BICYCLE
		DetectedActivity.IN_VEHICLE.value -> DetectedActivityType.IN_VEHICLE
		else -> null
	}

	override suspend fun onEnable(context: Context) {
		activityEvidence.clear()
		val repository = trackingParamsRepository
		if (repository != null) {
			val params = repository.data.first()
			minDistanceInMeters = params.minDistanceMeters
			minUpdateDelayInSeconds = params.minTimeSeconds
			preferenceJobs += repository.data
				.onEach {
					minDistanceInMeters = it.minDistanceMeters
					minUpdateDelayInSeconds = it.minTimeSeconds
				}
				.launchIn(this)
		} else {
			val prefs = Preferences(context)
			minDistanceInMeters = prefs.fetchInt(
				PreferenceKeys.TRACKING_MIN_DISTANCE,
				PreferenceKeys.TRACKING_MIN_DISTANCE_DEFAULT
			)
			minUpdateDelayInSeconds = prefs.fetchInt(
				PreferenceKeys.TRACKING_MIN_TIME,
				PreferenceKeys.TRACKING_MIN_TIME_DEFAULT
			)

			preferenceJobs += PreferenceFlows.int(
				context,
				PreferenceKeys.TRACKING_MIN_DISTANCE,
				PreferenceKeys.TRACKING_MIN_DISTANCE_DEFAULT
			).onEach { minDistanceInMeters = it }
				.launchIn(this)

			preferenceJobs += PreferenceFlows.int(
				context,
				PreferenceKeys.TRACKING_MIN_TIME,
				PreferenceKeys.TRACKING_MIN_TIME_DEFAULT
			).onEach { minUpdateDelayInSeconds = it }
				.launchIn(this)
		}

		withContext(coroutineContext) {
			initializeSession()
		}
	}

	@WorkerThread
	private suspend fun initializeSession() {
		val now = Time.nowMillis
		val expectedSource = if (isUserInitiated) {
			SegmentSource.USER_CREATED
		} else {
			SegmentSource.INFERRED_HIGH_CONFIDENCE
		}
		val resumable = resumeSessionSegmentId
			?.let { segmentId -> sessionSegmentDao.getById(segmentId) }
			?.takeIf { segment ->
				segment.source == expectedSource &&
					segment.startTimeMs <= segment.endTimeMs &&
					segment.endTimeMs <= now &&
					now - segment.endTimeMs <= SESSION_RESUME_TIMEOUT
			}
		if (resumable != null) {
			mutableSession = MutableTrackerSession(
				id = resumable.id,
				start = resumable.startTimeMs,
				end = now,
				isUserInitiated = isUserInitiated,
				collections = resumable.sampleCount,
				distanceInM = resumable.distanceM,
				distanceOnFootInM = 0f,
				distanceInVehicleInM = 0f,
				steps = resumable.steps ?: 0,
			)
			restoredDominantActivity = resumable.primaryActivity?.let { activity ->
				activity to (resumable.activityConfidence ?: 0)
			}
			sessionCreatedAt = resumable.createdAt
			isNewSession = false
			return
		}

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
			source = expectedSource,
			inferenceVersion = "tracker_v2",
			createdAt = now,
			hasDistanceAnomaly = false,
		)
		session.id = sessionSegmentDao.insert(segment)
		mutableSession = session
		sessionCreatedAt = now
		restoredDominantActivity = null
		isNewSession = true
	}

	companion object {
		const val SESSION_RESUME_TIMEOUT = 15 * Time.MINUTE_IN_MILLISECONDS
		private val MOVEMENT_ACTIVITY_IDS = setOf(
			DetectedActivity.WALKING.value,
			DetectedActivity.RUNNING.value,
			DetectedActivity.ON_FOOT.value,
			DetectedActivity.ON_BICYCLE.value,
			DetectedActivity.IN_VEHICLE.value,
		)
	}
}
