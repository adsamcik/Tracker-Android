package com.adsamcik.tracker.game.challenge.worker

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.R
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.debug.LogData
import com.adsamcik.tracker.common.extension.getPositiveLongReportNull
import com.adsamcik.tracker.common.extension.notificationManager
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.logGame

internal class ChallengeWorker(context: Context, workerParams: WorkerParameters) : Worker(
		context,
		workerParams
) {
	@Suppress("ReturnCount")
	override fun doWork(): Result {
		val applicationContext = applicationContext

		logGame(LogData(message = "Started Challenge Worker"))

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
			val title = "Completed challenge ${it.getTitle(applicationContext)}"
			logGame(LogData(message = title))
			notificationManager.notify(
					NOTIFICATION_ID,
					NotificationCompat.Builder(
							applicationContext,
							resources.getString(R.string.channel_challenges_id)
					)
							.setContentTitle(title)
							.setSmallIcon(R.drawable.ic_signals)
							.build()
			)
		}

		challengeSession.isChallengeProcessed = true
		sessionDao.update(challengeSession)

		logGame(LogData(message = "Successfully finished Challenge Worker"))
		return Result.success()
	}

	companion object {
		const val UNIQUE_WORK_NAME = "ChallengeSessionProcessing"
		const val ARG_SESSION_ID = "SessId"
		private const val NOTIFICATION_ID = 24255737
	}

}

