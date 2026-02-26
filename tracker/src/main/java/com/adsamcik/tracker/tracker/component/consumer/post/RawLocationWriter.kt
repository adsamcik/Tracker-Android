package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.tracker.component.consumer.data.LocationTrackerComponent
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Raw location sample writer for sessionless architecture.
 * Writes location samples to location_sample table with quality/motion classification.
 *
 * Contract:
 * - Converts Double lat/lon → E7 integers (degrees * 1e7)
 * - Classifies quality based on horizontal accuracy
 * - Infers motion state from activity
 * - Batches writes (configurable threshold)
 *
 * TODO: DI Migration - This PostTrackerComponent is instantiated by TrackerService.
 *  Future refactor: Accept LocationSampleDao via constructor for testability.
 *  See Section 16A of copilot-instructions.md for DI composition patterns.
 */
internal class RawLocationWriter : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = listOf(
		TrackerComponentRequirement.LOCATION
	)

	private lateinit var database: AppDatabase
	private val sampleBuffer = mutableListOf<LocationSample>()
	private var currentPolicy: String? = null
	private var scope: CoroutineScope? = null

	override suspend fun onEnable(context: Context) {
		database = AppDatabase.database(context)
		sampleBuffer.clear()
		scope = CoroutineScope(Job() + Dispatchers.Default)
	}

	override suspend fun onDisable(context: Context) {
		flush() // Write any buffered samples
		sampleBuffer.clear()
		scope?.cancel()
		scope = null
	}

	/**
	 * Set the current tracking policy (for sample tagging).
	 */
	fun setPolicy(policy: String?) {
		currentPolicy = policy
	}

	override fun onNewData(
		context: Context,
		session: com.adsamcik.tracker.shared.base.data.TrackerSession,
		collectionData: CollectionData,
		tempData: com.adsamcik.tracker.tracker.data.collection.CollectionTempData
	) {
		val location = collectionData.location ?: return
		val now = Time.nowMillis

		// Convert lat/lon to E7 format (integer microdegrees)
		val latE7 = (location.latitude * 1e7).toInt()
		val lonE7 = (location.longitude * 1e7).toInt()

		// Classify quality based on horizontal accuracy
		val accuracy = location.horizontalAccuracy
		val quality = when {
			accuracy == null -> SampleQuality.COARSE
			accuracy < 10f -> SampleQuality.HIGH
			accuracy < 50f -> SampleQuality.MEDIUM
			else -> SampleQuality.LOW
		}

		// Infer motion state from activity
		val motionState = when (collectionData.activity?.activityType) {
			3 -> MotionState.STILL // STILL
			2, 7, 8 -> MotionState.MOVING // ON_FOOT, WALKING, RUNNING
			0, 1 -> MotionState.MOVING // IN_VEHICLE, ON_BICYCLE
			else -> MotionState.UNKNOWN
		}

		// Raw GPS altitude (before fusion) from LocationTrackerComponent
		val rawGpsAlt = tempData.tryGet<Double>(LocationTrackerComponent.RAW_GPS_ALTITUDE_KEY)

		val sample = LocationSample(
			timeMs = location.time,
			elapsedRealtimeNanos = 0L, // Platform limitation: android.location.Location not accessible in current data flow
			latE7 = latE7,
			lonE7 = lonE7,
			altitudeM = location.altitude?.toFloat(),
			rawGpsAltitudeM = rawGpsAlt?.toFloat(),
			hAccM = location.horizontalAccuracy,
			vAccM = location.verticalAccuracy,
			speedMps = location.speed,
			speedAccuracyMps = location.speedAccuracy,
			provider = "fused", // Hardcoded: actual provider not exposed by current LocationData abstraction
			quality = quality,
			motionState = motionState,
			policy = currentPolicy,
			bucketId = null, // Populated later by aging/compression worker
			createdAt = now
		)

		sampleBuffer.add(sample)

		// Batch writes for efficiency
		if (sampleBuffer.size >= BATCH_SIZE_THRESHOLD) {
			scope?.launch(Dispatchers.IO) {
				flush()
			}
		}
	}

	private suspend fun flush() {
		if (sampleBuffer.isEmpty()) return

		withContext(Dispatchers.IO) {
			database.locationSampleDao().insert(sampleBuffer.toList())
		}

		sampleBuffer.clear()
	}

	companion object {
		private const val BATCH_SIZE_THRESHOLD = 10 // Write every 10 samples
	}
}
