package com.adsamcik.tracker.tracker.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.data.toLegacyActivityInfo
import com.adsamcik.tracker.tracker.notification.TrackerNotificationChannels
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Service used to keep device and ActivityService alive while automatic tracking might launch
 */
@AndroidEntryPoint
class ActivityWatcherService : CoreService() {
	private var activityInfo: ActivityInfo = ActivityInfo.UNKNOWN

	@Inject
	lateinit var activityWatcherController: ActivityWatcherServiceController

	@Inject
	lateinit var activityRequestManager: ActivityRequestManager

	private var activityUpdatesJob: Job? = null

	private lateinit var notificationManager: NotificationManager

	override fun onCreate() {
		super.onCreate()

		TrackerNotificationChannels.ensureActivityWatcherChannel(this)

		activityWatcherController.attachService(this)
		activityInfo = activityRequestManager.lastActivity.toLegacyActivityInfo()

		startForegroundCompat(updateNotification())

		notificationManager = (this as Context).notificationManager

		BackgroundTrackingApi.initialize(this)
		activityWatcherController.poke()

		activityUpdatesJob = launch {
			activityRequestManager.activityUpdates.collect { update ->
				val legacyActivity = update.activity.toLegacyActivityInfo()
				if (legacyActivity != activityInfo) {
					activityInfo = legacyActivity
					notificationManager.notify(NOTIFICATION_ID, updateNotification())
				}
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		activityWatcherController.detachService()
		activityUpdatesJob?.cancel()
		activityUpdatesJob = null
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		return START_REDELIVER_INTENT
	}

	private fun startForegroundCompat(notification: Notification) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			// SPECIAL_USE foreground service type is available from Android 14.
			startForeground(
				NOTIFICATION_ID,
				notification,
				ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
			)
		} else {
			startForeground(NOTIFICATION_ID, notification)
		}
	}

	private fun updateNotification(): Notification {
		val intent = packageManager.getLaunchIntentForPackage(packageName)
			?: throw NullPointerException("Launch intent for package is null.")

		val builder = NotificationCompat.Builder(
			this,
			getString(com.adsamcik.tracker.shared.base.R.string.channel_activity_watcher_id)
		)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setOnlyAlertOnce(true)
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
		private const val NOTIFICATION_ID = -568465
	}
}
