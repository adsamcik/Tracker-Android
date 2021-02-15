package com.adsamcik.tracker.points

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.points.work.PointsWorker
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.extension.getPositiveLongExtraReportNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * Challenge session broadcast receiver
 */
class PointsSessionReceiver : BroadcastReceiver(), CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	private fun onSessionFinal(context: Context, intent: Intent) {
		val id = intent.getPositiveLongExtraReportNull(ARG_ID) ?: return

		val workManager = WorkManager.getInstance(context)
		val data = Data.Builder().putLong(ARG_ID, id).build()

		val workRequest = OneTimeWorkRequestBuilder<PointsWorker>()
				.addTag(WORK_TAG)
				.setInputData(data)
				.setConstraints(
						Constraints
								.Builder()
								.setRequiresBatteryNotLow(true)
								.build()
				).build()

		workManager.enqueue(workRequest)

		Logger.log(LogData(message = "Scheduled points work", source = POINTS_LOG_SOURCE))
	}

	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			TrackerSession.ACTION_SESSION_FINAL -> onSessionFinal(context, intent)
		}
	}

	companion object {
		const val WORK_TAG = "SessionPoints"
		private const val ARG_ID = TrackerSession.RECEIVER_SESSION_ID

	}
}


