package com.adsamcik.tracker.game.challenge.worker

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.R
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.extension.getPositiveLongReportNull
import com.adsamcik.tracker.common.extension.notificationManager
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase

class ChallengeWorker(context: Context, workerParams: WorkerParameters) : Worker(
		context,
		workerParams
) {

	override fun doWork(): Result {
		val applicationContext = applicationContext
		val sessionId = inputData.getPositiveLongReportNull(ARG_SESSION_ID)
				?: return Result.failure()

		val database = ChallengeDatabase.database(applicationContext)
		val sessionDao = database.sessionDao

		val challengeSession = sessionDao.get(sessionId) ?: return Result.failure()

		if (challengeSession.isChallengeProcessed) return Result.success()

		val trackerSession = AppDatabase.database(applicationContext).sessionDao().get(sessionId)
				?: return Result.failure()

		val notificationManager = applicationContext.notificationManager
		val resources = applicationContext.resources

		ChallengeManager.processSession(applicationContext, trackerSession) {
			notificationManager.notify(
					0,
					NotificationCompat.Builder(
							applicationContext,
							resources.getString(R.string.channel_challenges_id)
					)
							.setContentTitle("Completed challenge ${it.data.type.name}")
							.setSmallIcon(R.drawable.ic_directions_walk_white_24dp)
							.build()
			)
		}

		challengeSession.isChallengeProcessed = true
		sessionDao.update(challengeSession)
		return Result.success()
	}

	companion object {
		const val UNIQUE_WORK_NAME = "ChallengeSessionProcessing"
		const val ARG_SESSION_ID = "SessId"
	}

}

