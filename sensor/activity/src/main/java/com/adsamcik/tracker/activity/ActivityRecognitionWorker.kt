package com.adsamcik.tracker.activity

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
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
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.mapper.toEntity
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.base.result.runCatchingCancellable
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.SkiRunSegment
import com.adsamcik.tracker.shared.model.SkiSegmentType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@HiltWorker
internal class ActivityRecognitionWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val databaseProvider: Provider<AppDatabase>,
	private val skiInfrastructureManager: SkiInfrastructureManager,
	private val trackingStartupGate: TrackingStartupGate,
) :
	CoroutineWorker(
		context,
		workerParams
	) {

	override suspend fun doWork(): Result {
		val batchMode = inputData.getBoolean(ARG_BATCH_MODE, false)
		val sessionId = inputData.getLong(ARG_SESSION_ID, -1)
		if (!batchMode && sessionId < 0) return Result.failure()

		val startupGeneration = trackingStartupGate.currentGeneration
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
		}

		return try {
			requireReadyGeneration(startupGeneration)
			val database = databaseProvider.get()
			coroutineScope {
				if (batchMode) {
					return@coroutineScope doBatchWork(database, startupGeneration)
				}

				requireReadyGeneration(startupGeneration)
				val trip = database.tripDao().getById(sessionId)
					?: return@coroutineScope Result.failure()

				requireReadyGeneration(startupGeneration)
				val segments = database.sessionSegmentDao()
					.getUnrecognizedWithin(trip.startTimeMs, trip.endTimeMs)

				if (segments.isEmpty()) return@coroutineScope Result.success()

				processSegmentWindow(database, segments, startupGeneration)
				Result.success()
			}
		} catch (_: StartupGenerationChangedException) {
			// Deletion/recovery owns the newer generation. This stale work must not be replayed.
			Result.success()
		}
	}

	/**
	 * Batch mode: processes unrecognized segments in bounded time windows to avoid
	 * reading the full location/pressure history into memory at once.
	 */
	private suspend fun doBatchWork(
		database: AppDatabase,
		startupGeneration: Long,
	): Result = coroutineScope {
		val bounds = getUnrecognizedSegmentBounds(database, startupGeneration)
			?: return@coroutineScope Result.success()
		var windowStart = bounds.first

		while (windowStart <= bounds.last) {
			requireReadyGeneration(startupGeneration)
			val windowEnd = minOf(windowStart + BATCH_WINDOW_SIZE_MS - 1, bounds.last)
			val segments = getUnrecognizedSegmentsBetween(
				database,
				windowStart,
				windowEnd,
				startupGeneration,
			)
			if (segments.isNotEmpty()) {
				processSegmentWindow(database, segments, startupGeneration)
			}
			windowStart = windowEnd + 1
		}

		return@coroutineScope Result.success()
	}

	private suspend fun processSegmentWindow(
		database: AppDatabase,
		segments: List<SessionSegment>,
		startupGeneration: Long,
	) = coroutineScope {
		if (segments.isEmpty()) return@coroutineScope
		requireReadyGeneration(startupGeneration)

		val minStart = segments.minOf { it.startTimeMs }
		val maxEnd = segments.maxOf { it.endTimeMs }
		val allLocationSamplesDeferred = async {
			loadLocationSamplesBetween(database, minStart, maxEnd, startupGeneration)
		}
		val allActivitySnapshotsDeferred = async {
			loadActivitySnapshotsBetween(database, minStart, maxEnd, startupGeneration)
		}
		val allPressureDeferred = async {
			requireReadyGeneration(startupGeneration)
			database.pressureSampleDao().getAllBetween(minStart, maxEnd).also {
				requireReadyGeneration(startupGeneration)
			}
		}

		val allActivitySnapshots = allActivitySnapshotsDeferred.await()
		val allLocations = allLocationSamplesDeferred.await().mapNotNull {
			it.toActivityLocation(allActivitySnapshots)
		}
		val allPressure = allPressureDeferred.await()

		for (segment in segments) {
			requireReadyGeneration(startupGeneration)
			val segmentLocations = allLocations.filter {
				it.time in segment.startTimeMs..segment.endTimeMs
			}
			val segmentPressure = allPressure.filter {
				it.timeMs in segment.startTimeMs..segment.endTimeMs
			}
			processSession(
				segment,
				segmentLocations,
				segmentPressure,
				database,
				startupGeneration,
			)
		}
	}

	private suspend fun loadLocationSamplesBetween(
		database: AppDatabase,
		fromMs: Long,
		toMs: Long,
		startupGeneration: Long,
	): List<LocationSample> {
		val samples = mutableListOf<LocationSample>()
		var afterTimeMs: Long? = null
		var afterId: Long? = null

		while (true) {
			requireReadyGeneration(startupGeneration)
			val chunk = database.locationSampleDao().getChunkBetweenOrdered(
				fromMs = fromMs,
				toMs = toMs,
				afterTimeMs = afterTimeMs,
				afterId = afterId,
				limit = LOCATION_CHUNK_SIZE,
			)
			requireReadyGeneration(startupGeneration)
			if (chunk.isEmpty()) break

			samples += chunk.map { it.toModel() }
			val lastSample = chunk.last()
			afterTimeMs = lastSample.timeMs
			afterId = lastSample.id
		}

		return samples
	}

	private suspend fun loadActivitySnapshotsBetween(
		database: AppDatabase,
		fromMs: Long,
		toMs: Long,
		startupGeneration: Long,
	): List<ActivitySnapshot> {
		requireReadyGeneration(startupGeneration)
		val dao = database.activitySnapshotDao()
		val latest = dao.getLatestBefore(fromMs)
		requireReadyGeneration(startupGeneration)
		val snapshots = dao.getAllBetween(fromMs, toMs)
		requireReadyGeneration(startupGeneration)
		return (listOfNotNull(latest) + snapshots)
			.distinctBy { "${it.timeMs}:${it.activityType}:${it.confidence}:${it.isTransition}" }
			.sortedBy { it.timeMs }
	}

	private suspend fun getUnrecognizedSegmentBounds(
		database: AppDatabase,
		startupGeneration: Long,
	): LongRange? {
		requireReadyGeneration(startupGeneration)
		val bounds = database.sessionSegmentDao().getUnrecognizedBounds()
		requireReadyGeneration(startupGeneration)
		val minStart = bounds.minStart ?: return null
		val maxEnd = bounds.maxEnd ?: return null
		return minStart..maxEnd
	}

	private suspend fun getUnrecognizedSegmentsBetween(
		database: AppDatabase,
		fromMs: Long,
		toMs: Long,
		startupGeneration: Long,
	): List<SessionSegment> {
		requireReadyGeneration(startupGeneration)
		return database.sessionSegmentDao().getUnrecognizedStartingBetween(fromMs, toMs).also {
			requireReadyGeneration(startupGeneration)
		}
	}

	/**
	 * Runs all activity recognizers against a single segment's data and persists the result.
	 */
	private suspend fun processSession(
		segment: SessionSegment,
		locationCollection: List<ActivityLocation>,
		pressureSamples: List<PressureSample>,
		database: AppDatabase,
		startupGeneration: Long,
	): Result = coroutineScope {
		requireReadyGeneration(startupGeneration)
		val recognizers = buildList {
			add(OnFootActivityRecognizer())
			add(VehicleActivityRecognizer())
			if (pressureSamples.isNotEmpty()) add(SkiActivityRecognizer())
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
				val result = runCatchingCancellable {
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
		val skiRecognizer = activityRecognitionResult.first as? SkiActivityRecognizer
		val skiRunSegments = skiRecognizer?.skiSessionSummary?.let { summary ->
			requireReadyGeneration(startupGeneration)
			val now = System.currentTimeMillis()
			summary.runs.map { run ->
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
		}.orEmpty()

		// Classification and any ski derivatives are one fenced commit. If startup moves
		// to a new generation during either DAO call, the final check throws and Room
		// rolls the complete classification transaction back.
		database.withTransaction {
			requireReadyGeneration(startupGeneration)
			try {
				database.sessionSegmentDao().update(updatedSegment)
				if (skiRunSegments.isNotEmpty()) {
					database.skiRunSegmentDao().insert(skiRunSegments.map { it.toEntity() })
				}
			} finally {
				requireReadyGeneration(startupGeneration)
			}
		}

		return@coroutineScope Result.success()
	}

	private fun requireReadyGeneration(startupGeneration: Long) {
		if (!trackingStartupGate.isReady ||
			trackingStartupGate.currentGeneration != startupGeneration
		) {
			throw StartupGenerationChangedException
		}
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

	private object StartupGenerationChangedException : RuntimeException()
}
