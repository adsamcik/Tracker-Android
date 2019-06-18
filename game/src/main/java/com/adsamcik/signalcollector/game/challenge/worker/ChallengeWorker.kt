package com.adsamcik.signalcollector.game.challenge.worker

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.misc.extension.getPositiveLongReportNull
import com.adsamcik.signalcollector.common.misc.extension.notificationManager
import com.adsamcik.signalcollector.game.challenge.ChallengeManager
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase

class ChallengeWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

	override fun doWork(): Result {
		val applicationContext = applicationContext
		val sessionId = inputData.getPositiveLongReportNull(ARG_SESSION_ID)
				?: return Result.failure()

		val database = ChallengeDatabase.getDatabase(applicationContext)
		val sessionDao = database.sessionDao

		val challengeSession = sessionDao.get(sessionId) ?: return Result.failure()

		if (challengeSession.isChallengeProcessed) return Result.success()

		val trackerSession = AppDatabase.getDatabase(applicationContext).sessionDao().get(sessionId).value
				?: return Result.failure()

		val notificationManager = applicationContext.notificationManager
		val resources = applicationContext.resources

		notificationManager.notify(1, NotificationCompat.Builder(applicationContext, resources.getString(R.string.channel_challenges_id))
				.setContentTitle("Working on challenges")
				.build())

		ChallengeManager.processSession(trackerSession) {

			notificationManager.notify(0, NotificationCompat.Builder(applicationContext, resources.getString(R.string.channel_challenges_id))
					.setContentTitle("Completed challenge ${it.data.type.name}")
					.build())
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