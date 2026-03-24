package com.adsamcik.tracker.activity

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.activity.recognizer.ActivityRecognitionResult
import com.adsamcik.tracker.activity.recognizer.OnFootActivityRecognizer
import com.adsamcik.tracker.activity.recognizer.SkiActivityRecognizer
import com.adsamcik.tracker.activity.recognizer.VehicleActivityRecognizer
import com.adsamcik.tracker.activity.ski.SkiInfrastructureManager
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import com.adsamcik.tracker.shared.base.database.data.SkiSegmentType
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@HiltWorker
internal class ActivityRecognitionWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val database: AppDatabase,
	private val skiInfrastructureManager: SkiInfrastructureManager,
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
		val allLocationsDeferred = async { loadLocationsBetween(minStart, maxEnd) }
		val allPressureDeferred = async { database.pressureSampleDao().getAllBetween(minStart, maxEnd) }

		val allLocations = allLocationsDeferred.await()
		val allPressure = allPressureDeferred.await()

		for (segment in segments) {
			val segmentLocations = allLocations.filter {
				it.time in segment.startTimeMs..segment.endTimeMs
			}
			val segmentPressure = allPressure.filter {
				it.timeMs in segment.startTimeMs..segment.endTimeMs
			}
			processSession(segment, segmentLocations, segmentPressure, database)
		}
	}

	private suspend fun loadLocationsBetween(fromMs: Long, toMs: Long): List<DatabaseLocation> {
		val locations = mutableListOf<DatabaseLocation>()
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

			chunk.mapNotNullTo(locations) { it.toDatabaseLocation() }
			val lastSample = chunk.last()
			afterTimeMs = lastSample.timeMs
			afterId = lastSample.id
		}

		return locations
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
		locationCollection: List<DatabaseLocation>,
		pressureSamples: List<PressureSample>,
		database: AppDatabase
	): Result = coroutineScope {
		val recognizers = listOf(
			OnFootActivityRecognizer(),
			VehicleActivityRecognizer(),
			SkiActivityRecognizer()
		)

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
				val result = tryWithResultAndReport(
					default = { ActivityRecognitionResult(null, 0) }
				) {
					it.resolve(session, locationCollection)
				}
				Pair(it, result)
			}
		}

		val results = deferredResults.mapNotNull {
			val result = it.await()
			return@mapNotNull if (result.second.recognizedActivity == null) {
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
			primaryActivity = activityRecognitionResult.second.requireRecognizedActivity.id.toInt(),
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
			database.skiRunSegmentDao().insert(skiRunSegments)
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
	 * Converts a [LocationSample] to a [DatabaseLocation] for recognizer compatibility.
	 * Activity info defaults to UNKNOWN since LocationSample doesn't store per-point activity.
	 */
	private fun LocationSample.toDatabaseLocation(): DatabaseLocation? {
		val lat = latE7 ?: return null
		val lon = lonE7 ?: return null
		return DatabaseLocation(
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
			activityInfo = ActivityInfo.UNKNOWN
		)
	}

	companion object {
		const val ARG_SESSION_ID = "sessionId"
		const val ARG_BATCH_MODE = "batchMode"
		const val WORK_TAG = "ActivityRecognition"
		private const val BATCH_WINDOW_SIZE_MS = 7L * 24L * 60L * 60L * 1000L
		private const val LOCATION_CHUNK_SIZE = 2_000
	}
}
