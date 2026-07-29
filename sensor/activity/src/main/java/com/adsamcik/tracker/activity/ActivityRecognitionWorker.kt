package com.adsamcik.tracker.activity

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.activity.recognizer.ActivityRecognitionResult
import com.adsamcik.tracker.activity.recognizer.ActivityLocation
import com.adsamcik.tracker.activity.recognizer.OnFootActivityRecognizer
import com.adsamcik.tracker.activity.recognizer.SkiActivityRecognizer
import com.adsamcik.tracker.activity.recognizer.VehicleActivityRecognizer
import com.adsamcik.tracker.activity.ski.SkiInfrastructureManager
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.toSegmentPrimaryActivityId
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.mapper.toEntity
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.base.result.runWithResultAndReport
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.SkiRunSegment
import com.adsamcik.tracker.shared.model.SkiSegmentType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

@HiltWorker
internal class ActivityRecognitionWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val database: AppDatabase,
	private val skiInfrastructureManager: SkiInfrastructureManager,
	private val trackingParamsRepository: TrackingParamsRepository,
) :
	CoroutineWorker(
		context,
		workerParams
	) {

	override suspend fun doWork(): Result = coroutineScope {
		val batchMode = inputData.getBoolean(ARG_BATCH_MODE, false)
		if (batchMode) {
			return@coroutineScope doBatchWork()
		}

		val sessionId = inputData.getLong(ARG_SESSION_ID, -1)
		if (sessionId < 0) {
			return@coroutineScope fail("Session id was either not set or was invalid.")
		}

		val trip = database.tripDao().getById(sessionId)
			?: return@coroutineScope fail("Trip with id $sessionId not found.", false)

		val segments = database.sessionSegmentDao().getUnrecognizedWithin(trip.startTimeMs, trip.endTimeMs)

		if (segments.isEmpty()) return@coroutineScope Result.success()

		processSegmentWindow(segments)
		Result.success()
	}

	/**
	 * Batch mode: processes unrecognized segments in bounded time windows to avoid
	 * reading the full location/pressure history into memory at once.
	 */
	private suspend fun doBatchWork(): Result = coroutineScope {
		val bounds = getUnrecognizedSegmentBounds() ?: return@coroutineScope Result.success()
		var windowStart = bounds.first

		while (windowStart <= bounds.last) {
			val windowEnd = minOf(windowStart + BATCH_WINDOW_SIZE_MS - 1, bounds.last)
			val segments = getUnrecognizedSegmentsBetween(windowStart, windowEnd)
			if (segments.isNotEmpty()) {
				processSegmentWindow(segments)
			}
			windowStart = windowEnd + 1
		}

		return@coroutineScope Result.success()
	}

	private suspend fun processSegmentWindow(segments: List<SessionSegment>) = coroutineScope {
		if (segments.isEmpty()) return@coroutineScope

		val minStart = segments.minOf { it.startTimeMs }
		val maxEnd = segments.maxOf { it.endTimeMs }
		val skiDetectionEnabled = trackingParamsRepository.data.first().skiDetectionEnabled
		val allLocationSamplesDeferred = async { loadLocationSamplesBetween(minStart, maxEnd) }
		val allActivitySnapshotsDeferred = async { loadActivitySnapshotsBetween(minStart, maxEnd) }
		val allPressureDeferred = if (skiDetectionEnabled) {
			async { database.pressureSampleDao().getAllBetween(minStart, maxEnd) }
		} else {
			null
		}

		val allActivitySnapshots = allActivitySnapshotsDeferred.await()
		val allLocations = allLocationSamplesDeferred.await().mapNotNull {
			it.toActivityLocation(allActivitySnapshots)
		}
		val allPressure = allPressureDeferred?.await().orEmpty()

		for (segment in segments) {
			val segmentLocations = allLocations.filter {
				it.time in segment.startTimeMs..segment.endTimeMs
			}
			val segmentPressure = allPressure.filter {
				it.timeMs in segment.startTimeMs..segment.endTimeMs
			}
			processSession(segment, segmentLocations, segmentPressure, skiDetectionEnabled, database)
		}
	}

	private suspend fun loadLocationSamplesBetween(fromMs: Long, toMs: Long): List<LocationSample> {
		val samples = mutableListOf<LocationSample>()
		var afterTimeMs: Long? = null
		var afterId: Long? = null

		while (true) {
			val chunk = database.locationSampleDao().getChunkBetweenOrdered(
				fromMs = fromMs,
				toMs = toMs,
				afterTimeMs = afterTimeMs,
				afterId = afterId,
				limit = LOCATION_CHUNK_SIZE,
			)
			if (chunk.isEmpty()) break

			samples += chunk.map { it.toModel() }
			val lastSample = chunk.last()
			afterTimeMs = lastSample.timeMs
			afterId = lastSample.id
		}

		return samples
	}

	private suspend fun loadActivitySnapshotsBetween(fromMs: Long, toMs: Long): List<ActivitySnapshot> {
		val dao = database.activitySnapshotDao()
		return (listOfNotNull(dao.getLatestBefore(fromMs)) + dao.getAllBetween(fromMs, toMs))
			.distinctBy { "${it.timeMs}:${it.activityType}:${it.confidence}:${it.isTransition}" }
			.sortedBy { it.timeMs }
	}

	private suspend fun getUnrecognizedSegmentBounds(): LongRange? {
		val bounds = database.sessionSegmentDao().getUnrecognizedBounds()
		val minStart = bounds.minStart ?: return null
		val maxEnd = bounds.maxEnd ?: return null
		return minStart..maxEnd
	}

	private suspend fun getUnrecognizedSegmentsBetween(fromMs: Long, toMs: Long): List<SessionSegment> {
		return database.sessionSegmentDao().getUnrecognizedStartingBetween(fromMs, toMs)
	}

	/**
	 * Runs all activity recognizers against a single segment's data and persists the result.
	 */
	private suspend fun processSession(
		segment: SessionSegment,
		locationCollection: List<ActivityLocation>,
		pressureSamples: List<PressureSample>,
		skiDetectionEnabled: Boolean,
		database: AppDatabase
	): Result = coroutineScope {
		val recognizers = buildList {
			add(OnFootActivityRecognizer())
			add(VehicleActivityRecognizer())
			if (skiDetectionEnabled) add(SkiActivityRecognizer())
		}

		recognizers.filterIsInstance<SkiActivityRecognizer>().forEach {
			it.pressureSamples = pressureSamples
			it.infrastructureManager = skiInfrastructureManager
		}

		// Create TrackerSession for recognizer interface compatibility
		val session = TrackerSession(
			id = segment.id,
			start = segment.startTimeMs,
			end = segment.endTimeMs
		)

		val deferredResults = recognizers.map {
			async {
				val result = runWithResultAndReport {
					it.resolve(session, locationCollection)
				}.getOrElse {
					ActivityRecognitionResult(null, 0)
				}
				Pair(it, result)
			}
		}

		val results = deferredResults.mapNotNull {
			val result = it.await()
			return@mapNotNull if (
				result.second.recognizedActivity == null ||
				result.second.confidence <= 0
			) {
				null
			} else {
				result
			}
		}

		if (results.isEmpty()) return@coroutineScope Result.success()

		val activityRecognitionResult = requireNotNull(
			results.maxByOrNull { it.first.precisionConfidence * it.second.confidence }
		) { "results was checked non-empty but maxByOrNull returned null" }

		val updatedSegment = segment.copy(
			primaryActivity = activityRecognitionResult.second.requireRecognizedActivity.toSegmentPrimaryActivityId(),
			activityConfidence = activityRecognitionResult.second.confidence
		)
		database.sessionSegmentDao().update(updatedSegment)

		// Persist ski run segments if skiing was detected
		val skiRecognizer = activityRecognitionResult.first as? SkiActivityRecognizer
		skiRecognizer?.skiSessionSummary?.let { summary ->
			val now = System.currentTimeMillis()
			val skiRunSegments = summary.runs.map { run ->
				SkiRunSegment(
					sessionId = segment.id,
					runIndex = run.runIndex,
					segmentType = SkiSegmentType.valueOf(run.segmentType.name),
					startTimeMs = run.startTimeMs,
					endTimeMs = run.endTimeMs,
					verticalM = run.verticalM,
					distanceM = run.distanceM,
					maxSpeedMps = run.maxSpeedMps,
					avgSpeedMps = run.avgSpeedMps,
					createdAt = now
				)
			}
			database.skiRunSegmentDao().insert(skiRunSegments.map { it.toEntity() })
		}

		return@coroutineScope Result.success()
	}

	private fun fail(message: String, report: Boolean = true): Result {
		if (report) {
			Reporter.report(Throwable(message))
		} else {
			Reporter.log(message)
		}

		return Result.failure()
	}


	/**
	 * Converts a [LocationSample] to an [ActivityLocation] for recognizer compatibility.
	 * Uses persisted activity snapshots when available; otherwise keeps the signal unknown.
	 */
	private fun LocationSample.toActivityLocation(activitySnapshots: List<ActivitySnapshot>): ActivityLocation? {
		val lat = latE7 ?: return null
		val lon = lonE7 ?: return null
		return ActivityLocation(
			location = Location(
				time = timeMs,
				latitude = lat / 1e7,
				longitude = lon / 1e7,
				altitude = altitudeM?.toDouble(),
				horizontalAccuracy = hAccM,
				verticalAccuracy = vAccM,
				speed = speedMps,
				speedAccuracy = speedAccuracyMps
			),
			activityInfo = resolveActivityInfo(activitySnapshots)
		)
	}

	private fun LocationSample.resolveActivityInfo(activitySnapshots: List<ActivitySnapshot>): ActivityInfo {
		val snapshot = activitySnapshots.lastOrNull { it.timeMs <= timeMs }
		if (snapshot != null &&
			snapshot.confidence > 0 &&
			snapshot.activityType != DetectedActivity.UNKNOWN.value
		) {
			return ActivityInfo(snapshot.activityType, snapshot.confidence.coerceIn(0, 100))
		}

		return when (motionState) {
			MotionState.STILL -> ActivityInfo(DetectedActivity.STILL, 100)
			MotionState.MOVING,
			MotionState.UNKNOWN,
			null -> ActivityInfo.UNKNOWN
		}
	}

	companion object {
		const val ARG_SESSION_ID = "sessionId"
		const val ARG_BATCH_MODE = "batchMode"
		const val WORK_TAG = "ActivityRecognition"
		private const val BATCH_WINDOW_SIZE_MS = 7L * 24L * 60L * 60L * 1000L
		private const val LOCATION_CHUNK_SIZE = 2_000
	}
}
