package com.adsamcik.tracker.activity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.activity.ActivityRecognitionWorker
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.extension.getPositiveLongExtraReportNull

/**
 * Session receiver for activities
 */
class ActivitySessionReceiver : BroadcastReceiver() {
	private fun onSessionEnded(context: Context, intent: Intent) {
		val id = intent.getPositiveLongExtraReportNull(ARG_ID) ?: return

		val data = Data.Builder().putLong(ActivityRecognitionWorker.ARG_SESSION_ID, id).build()
		val workRequest = OneTimeWorkRequestBuilder<ActivityRecognitionWorker>()
				.addTag(ActivityRecognitionWorker.WORK_TAG)
				.setInputData(data)
				.setConstraints(
						Constraints.Builder()
								.setRequiresBatteryNotLow(true)
								.build()
				).build()


		WorkManager.getInstance(context).enqueue(workRequest)
	}

	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			TrackerSession.ACTION_SESSION_ENDED -> onSessionEnded(context, intent)
		}
	}

	companion object {
		private const val ARG_ID = TrackerSession.RECEIVER_SESSION_ID
	}
}
