package com.adsamcik.tracker.tracker.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service used to keep device and ActivityService alive while automatic tracking might launch
 */
@AndroidEntryPoint
class ActivityWatcherService : CoreService() {
	private var activityInfo: ActivityInfo = ActivityRequestManager.lastActivity

	@Inject
	lateinit var activityWatcherController: ActivityWatcherServiceController

	private var pollingJob: Job? = null

	private lateinit var notificationManager: NotificationManager

	override fun onCreate() {
		super.onCreate()

		activityWatcherController.attachService(this)

		val updatePreferenceInSeconds = BackgroundTrackingApi.activityFreqSeconds

		startForeground(NOTIFICATION_ID, updateNotification())

		notificationManager = (this as Context).notificationManager

		BackgroundTrackingApi.initialize(this)
		activityWatcherController.poke()

		// M2 fix: Replace java.util.Timer with coroutine-based polling
		pollingJob = launch {
			while (isActive) {
				delay(updatePreferenceInSeconds * Time.SECOND_IN_MILLISECONDS)
				val newActivityInfo = ActivityRequestManager.lastActivity
				if (newActivityInfo != activityInfo) {
					activityInfo = newActivityInfo
					notificationManager.notify(NOTIFICATION_ID, updateNotification())
				}
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		activityWatcherController.detachService()
		pollingJob?.cancel()
		pollingJob = null
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		return START_REDELIVER_INTENT
	}

	private fun updateNotification(): Notification {
		val intent = packageManager.getLaunchIntentForPackage(packageName)
			?: throw NullPointerException("Launch intent for package is null.")

		val builder = NotificationCompat.Builder(
			this,
			getString(com.adsamcik.tracker.shared.base.R.string.channel_activity_watcher_id)
		)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
			.setTicker(
				getString(R.string.notification_activity_watcher_ticker)
			)  // the done text
			.setWhen(Time.nowMillis)  // the time stamp
			.setVibrate(null)
			.setOngoing(true)
			.setContentIntent(
				PendingIntent.getActivity(
					this, 0, intent,
					PendingIntent.FLAG_IMMUTABLE
				)
			)

		builder.setContentTitle(getString(R.string.settings_activity_watcher_title))
		builder.setContentText(
			getString(
				R.string.notification_activity_watcher_info,
				activityInfo.getGroupedActivityName(this),
				activityInfo.confidence
			)
		)

		builder.setSmallIcon(activityInfo.groupedActivity.iconRes)

		return builder.build()
	}

	companion object {
		private const val TAG = "ActivityWatcherService"
		private const val NOTIFICATION_ID = -568465
	}
}
