package com.adsamcik.tracker.game.challenge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.shared.utils.extension.getPositiveLongExtraReportNull
import com.adsamcik.tracker.common.preferences.Preferences
import com.adsamcik.tracker.common.useMock
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeSessionData
import com.adsamcik.tracker.game.challenge.worker.ChallengeWorker
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class ChallengeSessionReceiver : BroadcastReceiver() {
	private fun onSessionStarted(context: Context, intent: Intent) {
		val isNewSession = intent.getBooleanExtra(ARG_IS_NEW_SESSION, false)

		if (!isNewSession) {
			val workManager = WorkManager.getInstance(context)
			workManager.cancelUniqueWork(ChallengeWorker.UNIQUE_WORK_NAME)
		}
	}

	private fun onSessionEnded(context: Context, intent: Intent) {
		val id = intent.getPositiveLongExtraReportNull(ARG_ID) ?: return
		val timeout = if (useMock) {
			0L
		} else {
			intent.getPositiveLongExtraReportNull(ARG_RESUME_TIMEOUT)
					?: Time.HOUR_IN_MINUTES
		}


		GlobalScope.launch {
			ChallengeDatabase
					.database(context)
					.sessionDao
					.insert(ChallengeSessionData(id, false))
			val workManager = WorkManager.getInstance(context)
			val data = Data.Builder().putLong(ChallengeWorker.ARG_SESSION_ID, id).build()
			val workRequest = OneTimeWorkRequestBuilder<ChallengeWorker>()
					.setInitialDelay(timeout, TimeUnit.MINUTES)
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
					ChallengeWorker.UNIQUE_WORK_NAME,
					ExistingWorkPolicy.REPLACE,
					workRequest
			)
		}
	}

	override fun onReceive(context: Context, intent: Intent) {
		//The receiver might be subscribed even though the challenges are disabled. Subscribing on demand could be really complicated.
		if (com.adsamcik.tracker.common.preferences.Preferences.getPref(context).getBooleanRes(
						R.string.settings_game_challenge_enable_key,
						R.string.settings_game_challenge_enable_default
				)) {
			when (intent.action) {
				TrackerSession.ACTION_SESSION_STARTED -> onSessionStarted(context, intent)
				TrackerSession.ACTION_SESSION_ENDED -> onSessionEnded(context, intent)
			}
		}
	}

	companion object {
		const val WORK_TAG = "Challenge"
		private const val ARG_IS_NEW_SESSION = TrackerSession.RECEIVER_SESSION_IS_NEW
		private const val ARG_ID = TrackerSession.RECEIVER_SESSION_ID
		private const val ARG_RESUME_TIMEOUT = TrackerSession.RECEIVER_SESSION_RESUME_TIMEOUT
	}
}

