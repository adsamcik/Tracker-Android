package com.adsamcik.tracker.activity

import android.content.Context
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
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal class ActivityRecognitionWorker(context: Context, workerParams: WorkerParameters) :
	CoroutineWorker(
		context,
		workerParams
	) {
	private val activeRecognizers = listOf(
		OnFootActivityRecognizer(),
		VehicleActivityRecognizer(),
		SkiActivityRecognizer()
	)

	override suspend fun doWork(): Result = coroutineScope {
		val sessionId = inputData.getLong(ARG_SESSION_ID, -1)
		if (sessionId < 0) {
			return@coroutineScope fail("Session id was either not set or was invalid.")
		}

		val database = AppDatabase.database(applicationContext)
		val session = database.sessionDao().get(sessionId)
			?: return@coroutineScope fail("Session with id $sessionId not found.", false)
		val locationCollection = database.locationDao().getAllBetween(session.start, session.end)

		// Pre-fetch pressure data for ski recognition
		val pressureSamples = database.pressureSampleDao().getAllBetween(session.start, session.end)
		val infraManager = SkiInfrastructureManager(applicationContext)
		activeRecognizers.filterIsInstance<SkiActivityRecognizer>().forEach {
			it.pressureSamples = pressureSamples
			it.infrastructureManager = infraManager
		}

		val deferredResults = activeRecognizers.map {
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

		val mutableSession = MutableTrackerSession(session).apply {
			sessionActivityId = activityRecognitionResult.second.requireRecognizedActivity.id
		}

		database.sessionDao().update(mutableSession)

		// Persist ski run segments if skiing was detected
		val skiRecognizer = activityRecognitionResult.first as? SkiActivityRecognizer
		skiRecognizer?.skiSessionSummary?.let { summary ->
			val now = System.currentTimeMillis()
			val segments = summary.runs.map { run ->
				SkiRunSegment(
					sessionId = sessionId,
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
			database.skiRunSegmentDao().insert(segments)
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


	companion object {
		const val ARG_SESSION_ID = "sessionId"
		const val WORK_TAG = "ActivityRecognition"
	}
}

