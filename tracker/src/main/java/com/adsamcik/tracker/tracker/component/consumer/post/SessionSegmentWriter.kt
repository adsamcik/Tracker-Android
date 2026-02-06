package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyEscalationEngine
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.SegmentEvent
import com.adsamcik.tracker.stats.api.SegmentSignal
import com.adsamcik.tracker.stats.engine.segment.SessionSegmentDetector
import com.adsamcik.tracker.stats.engine.segment.SegmentDetectorConfig
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Post-tracker component that feeds sensor data into the [SessionSegmentDetector]
 * and persists completed trip segments to the database.
 *
 * Bridges the tracker pipeline with the stats-engine trip detection:
 * - Builds [SegmentSignal] from per-cycle collection data
 * - Handles detector events (trip start/end/cancel)
 * - Locks GPS via [PolicyEscalationEngine.setMinimumTier] during trips
 * - Persists [SessionSegment] rows on trip completion
 *
 * Contract:
 * - No required data: operates on whatever sensors are available
 * - Thread safety: called from TrackerService's componentMutex (single-threaded)
 */
internal class SessionSegmentWriter : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private lateinit var database: AppDatabase
	private var scope: CoroutineScope? = null
	private var detector: SessionSegmentDetector? = null
	private var escalationEngine: PolicyEscalationEngine? = null
	private var userInitiated: Boolean = false
	private var config: SegmentDetectorConfig = SegmentDetectorConfig()

	// Previous location for distance delta calculation
	private var prevLatE7: Int? = null
	private var prevLonE7: Int? = null

	/**
	 * Set the policy escalation engine for GPS locking during trips.
	 * Must be called before [onEnable].
	 */
	fun setEscalationEngine(engine: PolicyEscalationEngine?) {
		this.escalationEngine = engine
	}

	/**
	 * Set whether this tracking session was user-initiated.
	 * Affects segment source classification.
	 */
	fun setUserInitiated(userInitiated: Boolean) {
		this.userInitiated = userInitiated
	}

	override suspend fun onEnable(context: Context) {
		database = AppDatabase.database(context)
		scope = CoroutineScope(Job() + Dispatchers.Default)
		detector = SessionSegmentDetector(config = config)
		prevLatE7 = null
		prevLonE7 = null
	}

	override suspend fun onDisable(context: Context) {
		// Force-end any active trip
		detector?.forceEnd(Time.nowMillis)?.let { event ->
			handleEvent(event)
		}
		detector = null
		prevLatE7 = null
		prevLonE7 = null
		escalationEngine?.clearMinimumTier()
		scope?.cancel()
		scope = null
	}

	override fun onNewData(
		context: Context,
		session: com.adsamcik.tracker.shared.base.data.TrackerSession,
		collectionData: CollectionData,
		tempData: CollectionTempData,
	) {
		val det = detector ?: return
		val signal = buildSignal(collectionData, tempData)
		val event = det.onSignal(signal)
		if (event != null) {
			handleEvent(event)
		}
	}

	private fun buildSignal(
		collectionData: CollectionData,
		tempData: CollectionTempData,
	): SegmentSignal {
		val location = collectionData.location
		val activity = collectionData.activity

		// Convert lat/lon to E7
		val latE7 = location?.let { (it.latitude * 1e7).toInt() }
		val lonE7 = location?.let { (it.longitude * 1e7).toInt() }

		// Calculate distance delta from previous position
		val distanceDeltaM = if (latE7 != null && lonE7 != null && prevLatE7 != null && prevLonE7 != null) {
			SessionSegmentDetector.approximateDistanceE7(prevLatE7!!, prevLonE7!!, latE7, lonE7)
		} else {
			null
		}

		// Update previous position
		if (latE7 != null && lonE7 != null) {
			prevLatE7 = latE7
			prevLonE7 = lonE7
		}

		// Map activity type
		val activityType = activity?.let { mapActivityType(it.activityType) }

		// Get step delta
		val stepDelta = tempData.tryGet<Int>(StepDataProducer.NEW_STEPS_ARG) ?: 0

		return SegmentSignal(
			timestampMs = tempData.timeMillis,
			latE7 = latE7,
			lonE7 = lonE7,
			horizontalAccuracyM = location?.horizontalAccuracy,
			speedMps = location?.speed,
			stepDelta = stepDelta,
			activityType = activityType,
			activityConfidence = activity?.confidence,
			distanceDeltaM = distanceDeltaM,
		)
	}

	private fun handleEvent(event: SegmentEvent) {
		when (event) {
			is SegmentEvent.TripStarted -> {
				escalationEngine?.setMinimumTier(PolicyTier.ACTIVE, "trip detected")
			}
			is SegmentEvent.TripEnded -> {
				escalationEngine?.clearMinimumTier()
				persistSegment(event)
			}
			is SegmentEvent.DepartureCancelled -> {
				escalationEngine?.clearMinimumTier()
			}
			is SegmentEvent.TripUpdated -> {
				// No action needed for updates
			}
		}
	}

	private fun persistSegment(event: SegmentEvent.TripEnded) {
		val source = classifySource(event)

		val segment = SessionSegment(
			startTimeMs = event.startTimeMs,
			endTimeMs = event.endTimeMs,
			distanceM = event.totalDistanceM,
			steps = if (event.totalSteps > 0) event.totalSteps else null,
			primaryActivity = event.primaryActivity?.let { mapActivityTypeToInt(it) },
			activityConfidence = event.averageActivityConfidence,
			sampleCount = event.sampleCount,
			source = source,
			inferenceVersion = config.inferenceVersion,
			createdAt = Time.nowMillis,
		)

		scope?.launch(Dispatchers.IO) {
			database.sessionSegmentDao().insert(segment)
		}
	}

	private fun classifySource(event: SegmentEvent.TripEnded): SegmentSource {
		if (userInitiated) return SegmentSource.USER_CREATED

		val avgConfidence = event.averageActivityConfidence ?: return SegmentSource.INFERRED_LOW_CONFIDENCE
		return when {
			avgConfidence >= 80 -> SegmentSource.INFERRED_HIGH_CONFIDENCE
			avgConfidence >= 50 -> SegmentSource.INFERRED_MEDIUM_CONFIDENCE
			else -> SegmentSource.INFERRED_LOW_CONFIDENCE
		}
	}

	companion object {
		/**
		 * Map Google Play Services activity type int to [DetectedActivityType].
		 *
		 * Values from com.google.android.gms.location.DetectedActivity:
		 * IN_VEHICLE=0, ON_BICYCLE=1, ON_FOOT=2, STILL=3, UNKNOWN=4, TILTING=5, WALKING=7, RUNNING=8
		 */
		internal fun mapActivityType(activityTypeInt: Int): DetectedActivityType = when (activityTypeInt) {
			0 -> DetectedActivityType.IN_VEHICLE
			1 -> DetectedActivityType.ON_BICYCLE
			2 -> DetectedActivityType.ON_FOOT
			3 -> DetectedActivityType.STILL
			5 -> DetectedActivityType.TILTING
			7 -> DetectedActivityType.WALKING
			8 -> DetectedActivityType.RUNNING
			else -> DetectedActivityType.UNKNOWN
		}

		/**
		 * Map [DetectedActivityType] back to int for database storage.
		 */
		internal fun mapActivityTypeToInt(type: DetectedActivityType): Int = when (type) {
			DetectedActivityType.IN_VEHICLE -> 0
			DetectedActivityType.ON_BICYCLE -> 1
			DetectedActivityType.ON_FOOT -> 2
			DetectedActivityType.STILL -> 3
			DetectedActivityType.TILTING -> 5
			DetectedActivityType.WALKING -> 7
			DetectedActivityType.RUNNING -> 8
			DetectedActivityType.UNKNOWN -> 4
		}
	}
}
