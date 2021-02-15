package com.adsamcik.tracker.game.challenge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.game.CHALLENGE_LOG_SOURCE
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeSessionData
import com.adsamcik.tracker.game.challenge.worker.ChallengeWorker
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.getPositiveLongExtraReportNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Challenge session broadcast receiver
 */
class ChallengeSessionReceiver : BroadcastReceiver(), CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	private fun onSessionFinal(context: Context, intent: Intent) {
		val id = intent.getPositiveLongExtraReportNull(ARG_ID) ?: return

		launch {
			ChallengeDatabase
					.database(context)
					.sessionDao
					.insert(ChallengeSessionData(id, false))

			val workManager = WorkManager.getInstance(context)
			val data = Data.Builder().putLong(ChallengeWorker.ARG_SESSION_ID, id).build()
			val workRequest = OneTimeWorkRequestBuilder<ChallengeWorker>()
					.addTag(WORK_TAG)
					.setInputData(data)
					.setConstraints(
							Constraints
									.Builder()
									.setRequiresBatteryNotLow(true)
									.build()
					)
					.build()
			workManager.enqueueUniqueWork(
					"$id${ChallengeWorker.UNIQUE_WORK_NAME}",
					ExistingWorkPolicy.REPLACE,
					workRequest
			)
		}
	}

	override fun onReceive(context: Context, intent: Intent) {
		Logger.log(
				LogData(
						message = "Received event ${intent.action}",
						source = CHALLENGE_LOG_SOURCE
				)
		)
		//The receiver might be subscribed even though the challenges are disabled. Subscribing on demand could be really complicated.
		if (Preferences.getPref(context).getBooleanRes(
						R.string.settings_game_challenge_enable_key,
						R.string.settings_game_challenge_enable_default
				)) {
			when (intent.action) {
				TrackerSession.ACTION_SESSION_FINAL -> onSessionFinal(context, intent)
			}
		}
	}

	companion object {
		const val WORK_TAG = "Challenge"
		private const val ARG_ID = TrackerSession.RECEIVER_SESSION_ID
	}
}

