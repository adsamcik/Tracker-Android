package com.adsamcik.tracker.game.challenge.worker

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.game.CHALLENGE_LOG_SOURCE
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.game.logGame
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.utils.extension.getPositiveLongReportNull
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
internal class ChallengeWorker @AssistedInject constructor(
		@Assisted context: Context,
		@Assisted workerParams: WorkerParameters,
		private val appDatabase: AppDatabase,
		private val challengeManager: ChallengeManager,
) : CoroutineWorker(
		context,
		workerParams,
) {

	@Suppress("ReturnCount")
	override suspend fun doWork(): Result {
		val applicationContext = applicationContext

		logGame(LogData(message = "Started Challenge Worker", source = CHALLENGE_LOG_SOURCE))

		val sessionId = inputData.getPositiveLongReportNull(ARG_SESSION_ID)
				?: return Result.failure()

		val trip = appDatabase.tripDao().getById(sessionId)
			?: return Result.failure()

		val trackerSession = com.adsamcik.tracker.shared.base.data.TrackerSession(
			id = trip.id,
			start = trip.startTimeMs,
			end = trip.endTimeMs,
			isUserInitiated = trip.isUserInitiated,
			collections = trip.sampleCount,
			distanceInM = trip.distanceM,
			steps = trip.steps ?: 0,
		)

		val notificationManager = applicationContext.notificationManager
		val resources = applicationContext.resources

		try {
			challengeManager.processSession(applicationContext, trackerSession) {
				val title = "Completed challenge ${it.getTitle(applicationContext)}"
				logGame(LogData(message = title, source = CHALLENGE_LOG_SOURCE))
				val launchIntent = applicationContext.packageManager
					.getLaunchIntentForPackage(applicationContext.packageName)
					?.apply {
						putExtra("navigate_to", "game")
						putExtra("challenge_id", it.entity.id)
						addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
					}
				val contentIntent = launchIntent?.let { navIntent ->
					PendingIntent.getActivity(
						applicationContext,
						it.entity.id.toInt(),
						navIntent,
						PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
					)
				}
				notificationManager.notify(
						NOTIFICATION_ID,
			    NotificationCompat.Builder(
				    applicationContext,
				    resources.getString(R.string.channel_challenges_id)
			    )
				    .setContentTitle(title)
				    .setSmallIcon(com.adsamcik.tracker.game.R.drawable.ic_challenge_icon)
					.setContentIntent(contentIntent)
					.setAutoCancel(true)
								.build()
				)
			}
		} catch (e: Exception) {
			// processSession threw — WorkManager retries this session event.
			return Result.retry()
		}

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
