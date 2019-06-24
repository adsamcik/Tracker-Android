package com.adsamcik.signalcollector.activity

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.signalcollector.activity.recognizer.OnFootActivityRecognizer
import com.adsamcik.signalcollector.activity.recognizer.VehicleActivityRecognizer
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.tracker.data.session.MutableTrackerSession
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class ActivityRecognitionWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
	private val activeRecognizers = listOf(OnFootActivityRecognizer(), VehicleActivityRecognizer())

	override suspend fun doWork(): Result = coroutineScope {
		val sessionId = inputData.getLong(ARG_SESSION_ID, -1)
		if (sessionId < 0) return@coroutineScope fail("Session id was either not set or was invalid.")

		val database = AppDatabase.getDatabase(applicationContext)
		val session = database.sessionDao().get(sessionId)
				?: return@coroutineScope fail("Session with id $sessionId not found.")
		val locationCollection = database.locationDao().getAllBetween(session.start, session.end)

		val deferredResults = activeRecognizers.map { async { Pair(it, it.resolve(session, locationCollection)) } }

		val results = deferredResults.mapNotNull {
			val result = it.await()
			return@mapNotNull if (result.second.recognizedActivity == null) {
				null
			} else {
				result
			}
		}

		if (results.isEmpty()) return@coroutineScope Result.success()

		val activityRecognitionResult = results.maxBy { it.first.precisionConfidence * it.second.confidence }
				?: throw NullPointerException()

		val mutableSession = MutableTrackerSession(session)
		mutableSession.sessionActivityId = activityRecognitionResult.second.requireRecognizedActivity.ordinal

		database.sessionDao().update(mutableSession)

		return@coroutineScope Result.success()
	}

	private fun fail(message: String): Result {
		Reporter.report(Throwable(message))
		return Result.failure()
	}


	companion object {
		const val ARG_SESSION_ID = "sessionId"
		const val WORK_TAG = "ActivityRecognition"
	}
}
