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
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.notification.TrackerNotificationChannels
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
	private var activityInfo: ActivityInfo = ActivityInfo.UNKNOWN

	@Inject
	lateinit var activityWatcherController: ActivityWatcherServiceController

	@Inject
	lateinit var activityRequestManager: ActivityRequestManager

	private var pollingJob: Job? = null

	private lateinit var notificationManager: NotificationManager

	override fun onCreate() {
		super.onCreate()

		TrackerNotificationChannels.ensureActivityWatcherChannel(this)

		activityWatcherController.attachService(this)
		activityInfo = activityRequestManager.lastActivity

		val updatePreferenceInSeconds = BackgroundTrackingApi.activityFreqSeconds

		val foregroundStarted = startForegroundCompat(updateNotification())

		notificationManager = (this as Context).notificationManager

		if (!foregroundStarted) {
			Reporter.w(TAG, "Could not enter foreground; stopping activity watcher")
			stopSelf()
			return
		}

		BackgroundTrackingApi.initialize(this)
		activityWatcherController.poke()

		// M2 fix: Replace java.util.Timer with coroutine-based polling
		pollingJob = launch {
			while (isActive) {
				delay(updatePreferenceInSeconds * Time.SECOND_IN_MILLISECONDS)
				val newActivityInfo = activityRequestManager.lastActivity
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

	/**
	 * Android 15+ may time out a foreground service of a time-limited type. Stop cleanly so
	 * the platform does not raise a fatal `RemoteServiceException`; the watcher will be
	 * re-poked the next time tracking state changes.
	 */
	override fun onTimeout(startId: Int, fgsType: Int) {
		Reporter.w(TAG, "Activity watcher foreground service timed out (type=$fgsType); stopping")
		stopSelf(startId)
	}

	private fun startForegroundCompat(notification: Notification): Boolean {
		return try {
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
			true
		} catch (e: SecurityException) {
			Reporter.report(e)
			false
		} catch (e: IllegalStateException) {
			// ForegroundServiceStartNotAllowedException (Android 12+) is an
			// IllegalStateException subclass; treat any such failure as "not foregrounded".
			Reporter.report(e)
			false
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
		private const val TAG = "ActivityWatcherService"
		private const val NOTIFICATION_ID = -568465
	}
}
