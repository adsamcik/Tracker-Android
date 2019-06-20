package com.adsamcik.signalcollector.game.challenge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.misc.extension.getPositiveLongExtraReportNull
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.common.useMock
import com.adsamcik.signalcollector.game.R
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeSessionData
import com.adsamcik.signalcollector.game.challenge.worker.ChallengeWorker
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class ChallengeSessionReceiver : BroadcastReceiver() {
	private fun onSessionStarted(context: Context) {
		val workManager = WorkManager.getInstance(context)
		workManager.cancelUniqueWork(ChallengeWorker.UNIQUE_WORK_NAME)
	}

	private fun onSessionEnded(context: Context, intent: Intent) {
		val id = intent.getPositiveLongExtraReportNull(ARG_ID) ?: return

		GlobalScope.launch {
			ChallengeDatabase.getDatabase(context).sessionDao.insert(ChallengeSessionData(id, false))
			val workManager = WorkManager.getInstance(context)
			val data = Data.Builder().putLong(ChallengeWorker.ARG_SESSION_ID, id).build()
			val workRequest = OneTimeWorkRequestBuilder<ChallengeWorker>()
					.setInitialDelay(DELAY_IN_MINUTES, TimeUnit.MINUTES)
					.addTag("ChallengeQueue")
					.setInputData(data)
					.setConstraints(Constraints.Builder()
							.setRequiresBatteryNotLow(true)
							.build()
					).build()
			workManager.enqueueUniqueWork(ChallengeWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, workRequest)
		}
	}

	override fun onReceive(context: Context, intent: Intent) {
		//The receiver might be subscribed even though the challenges are disabled. Subscribing on demand could be really complicated.
		if (Preferences.getPref(context).getBooleanRes(R.string.settings_game_challenge_enable_key, R.string.settings_game_challenge_enable_default)) {
			when (intent.action) {
				TrackerSession.RECEIVER_SESSION_STARTED -> onSessionStarted(context)
				TrackerSession.RECEIVER_SESSION_ENDED -> onSessionEnded(context, intent)
			}
		}
	}

	companion object {
		const val ARG_ID = "id"
		val DELAY_IN_MINUTES = if (useMock) 0L else 60L
	}
}