package com.adsamcik.tracker.game.challenge.worker

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.R
import com.adsamcik.tracker.game.CHALLENGE_LOG_SOURCE
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeSessionData
import com.adsamcik.tracker.game.logGame
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.utils.extension.getPositiveLongReportNull

internal class ChallengeWorker(context: Context, workerParams: WorkerParameters) : Worker(
		context,
		workerParams
) {

	private fun getSession(database: ChallengeDatabase, id: Long): ChallengeSessionData {
		val databaseSession = database.sessionDao().get(id)
		return if (databaseSession == null) {
			val newSession = ChallengeSessionData(id, false)
			database.sessionDao().insert(newSession)
			newSession
		} else {
			databaseSession
		}
	}

	@Suppress("ReturnCount")
	override fun doWork(): Result {
		val applicationContext = applicationContext

		logGame(LogData(message = "Started Challenge Worker", source = CHALLENGE_LOG_SOURCE))

		val sessionId = inputData.getPositiveLongReportNull(ARG_SESSION_ID)
				?: return Result.failure()

		val database = ChallengeDatabase.database(applicationContext)

		val challengeSession = getSession(database, sessionId)

		if (challengeSession.isChallengeProcessed) return Result.success()

		val trackerSession = AppDatabase.database(applicationContext).sessionDao().get(sessionId)
				?: return Result.failure()

		val notificationManager = applicationContext.notificationManager
		val resources = applicationContext.resources

		ChallengeManager.processSession(applicationContext, trackerSession) {
			val title = "Completed challenge ${it.getTitle(applicationContext)}"
			logGame(LogData(message = title, source = CHALLENGE_LOG_SOURCE))
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
		database.sessionDao().update(challengeSession)

		logGame(
				LogData(
						message = "Successfully finished Challenge Worker",
						source = CHALLENGE_LOG_SOURCE
				)
		)
		return Result.success()
	}

	companion object {
		const val UNIQUE_WORK_NAME = "ChallengeSessionProcessing"
		const val ARG_SESSION_ID = "SessionId"
		private const val NOTIFICATION_ID = 24255737
	}

}

