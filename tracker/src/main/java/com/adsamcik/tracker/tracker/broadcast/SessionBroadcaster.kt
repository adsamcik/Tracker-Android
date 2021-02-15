package com.adsamcik.tracker.tracker.broadcast

import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import java.util.concurrent.TimeUnit

/**
 * Broadcasts session states to the whole application.
 */
internal object SessionBroadcaster {
	private const val PARAM_SESSION_END = "endSession"
	private const val SESSION_FINAL_WORK_TAG = "finalSession"
	private const val SESSION_BROADCAST_LOG_SOURCE = "session"

	private fun createBaseIntent(context: Context, action: String, session: TrackerSession) =
			Intent(action).apply {
				putExtra(TrackerSession.RECEIVER_SESSION_ID, session.id)
				`package` = context.packageName
			}

	private fun sendBroadcast(context: Context, intent: Intent) {
		context.sendBroadcast(intent, TrackerSession.BROADCAST_PERMISSION)
	}

	/**
	 * Broadcast session started
	 */
	fun broadcastSessionStart(context: Context, session: TrackerSession, isNew: Boolean) {
		val intent = createBaseIntent(
				context,
				TrackerSession.ACTION_SESSION_STARTED,
				session
		).apply {
			putExtra(TrackerSession.RECEIVER_SESSION_IS_NEW, isNew)
		}
		sendBroadcast(context, intent)

		cancelSessionFinal(context, session)
		Logger.log(LogData(message = "Session started", source = SESSION_BROADCAST_LOG_SOURCE))
	}

	/**
	 * Broadcast session ended
	 */
	fun broadcastSessionEnd(context: Context, session: TrackerSession) {
		val intent = createBaseIntent(
				context,
				TrackerSession.ACTION_SESSION_ENDED,
				session
		)
		sendBroadcast(context, intent)

		Logger.log(LogData(message = "Session ended", source = SESSION_BROADCAST_LOG_SOURCE))

		if (session.isUserInitiated) {
			broadcastSessionFinal(context, session)
		} else {
			scheduleBroadcastSessionFinal(context, session)
		}
	}

	/**
	 * Broadcast session is final
	 */
	fun broadcastSessionFinal(context: Context, session: TrackerSession) {
		val intent = createBaseIntent(
				context,
				TrackerSession.ACTION_SESSION_FINAL,
				session
		)
		sendBroadcast(context, intent)
		Logger.log(LogData(message = "Session finalized", source = SESSION_BROADCAST_LOG_SOURCE))
	}

	private fun getScheduleFinalId(session: TrackerSession) = "${session.id}$SESSION_FINAL_WORK_TAG"

	private fun cancelSessionFinal(context: Context, session: TrackerSession) {
		WorkManager.getInstance(context).cancelUniqueWork(getScheduleFinalId(session))
	}

	private fun scheduleBroadcastSessionFinal(context: Context, session: TrackerSession) {
		val data = Data
				.Builder()
				.putLong(TrackerSession.RECEIVER_SESSION_ID, session.id)
				.putLong(PARAM_SESSION_END, session.end)
				.build()

		val workRequest = OneTimeWorkRequestBuilder<SessionFinalWorker>()
				.addTag(SESSION_FINAL_WORK_TAG)
				.setInputData(data)
				.setInitialDelay(
						SessionTrackerComponent.SESSION_RESUME_TIMEOUT,
						TimeUnit.MILLISECONDS
				)
				.build()

		WorkManager
				.getInstance(context)
				.enqueueUniqueWork(
						getScheduleFinalId(session),
						ExistingWorkPolicy.REPLACE,
						workRequest
				)
	}

	private class SessionFinalWorker(context: Context, workerParams: WorkerParameters) : Worker(
			context,
			workerParams
	) {
		override fun doWork(): Result {
			val session = requireNotNull(
					AppDatabase.database(applicationContext)
							.sessionDao()
							.get(
									inputData.getLong(
											TrackerSession.RECEIVER_SESSION_ID,
											Long.MIN_VALUE
									)
							)
			)

			if (session.end == inputData.getLong(PARAM_SESSION_END, Long.MIN_VALUE)) {
				broadcastSessionFinal(applicationContext, session)
			}

			return Result.success()
		}

	}
}
