package com.adsamcik.signalcollector.game.challenge.worker

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.game.challenge.ChallengeManager
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.misc.extension.notificationManager

class ChallengeWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
	override fun doWork(): Result {
		val database = ChallengeDatabase.getAppDatabase(applicationContext)
		val sessionDao = database.sessionDao

		val sessionList = sessionDao.getAll()
		ChallengeManager.processSession(sessionList) {
			val resources = applicationContext.resources

			applicationContext.notificationManager.notify(0, NotificationCompat.Builder(applicationContext, resources.getString(R.string.channel_challenges_id))
					.setContentTitle("Completed challenge ${it.data.type.name}")
					.build())
		}

		sessionDao.delete(sessionList)
		return Result.success()
	}

}