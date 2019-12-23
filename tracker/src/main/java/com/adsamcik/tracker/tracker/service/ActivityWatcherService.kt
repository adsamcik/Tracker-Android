package com.adsamcik.tracker.tracker.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.ActivityInfo
import com.adsamcik.tracker.common.extension.notificationManager
import com.adsamcik.tracker.common.extension.startForegroundService
import com.adsamcik.tracker.common.preferences.Preferences
import com.adsamcik.tracker.common.service.CoreService
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate

/**
 * Service used to keep device and ActivityService alive while automatic tracking might launch
 */
class ActivityWatcherService : CoreService() {
	private var activityInfo: ActivityInfo = ActivityRequestManager.lastActivity

	private val timer: Timer = Timer()

	private lateinit var notificationManager: NotificationManager

	override fun onCreate() {
		super.onCreate()

		instance = this

		val updatePreferenceInSeconds = com.adsamcik.tracker.common.preferences.Preferences.getPref(this)
				.getIntResString(
						R.string.settings_activity_freq_key,
						R.string.settings_activity_freq_default
				)

		startForeground(NOTIFICATION_ID, updateNotification())

		notificationManager = (this as Context).notificationManager

		BackgroundTrackingApi.initialize(this)

		timer.scheduleAtFixedRate(0L, updatePreferenceInSeconds * Time.SECOND_IN_MILLISECONDS) {
			val newActivityInfo = ActivityRequestManager.lastActivity
			if (newActivityInfo != activityInfo) {
				activityInfo = newActivityInfo
				notificationManager.notify(NOTIFICATION_ID, updateNotification())
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		instance = null
		timer.cancel()
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
				getString(R.string.channel_activity_watcher_id)
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
								0
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

		private var instance: ActivityWatcherService? = null

		private fun getWatcherPreference(context: Context): Boolean = com.adsamcik.tracker.common.preferences.Preferences.getPref(
				context
		).getBooleanRes(
				R.string.settings_activity_watcher_key, R.string.settings_activity_watcher_default
		)

		private fun getAutoTrackingPreference(context: Context): Int = com.adsamcik.tracker.common.preferences.Preferences.getPref(
				context
		).getIntResString(
				R.string.settings_tracking_activity_key,
				R.string.settings_tracking_activity_default
		)

		private fun getActivityIntervalPreference(context: Context): Int = com.adsamcik.tracker.common.preferences.Preferences.getPref(
				context
		).getIntResString(
				R.string.settings_activity_freq_key, R.string.settings_activity_freq_default
		)

		fun onWatcherPreferenceChange(context: Context, value: Boolean) {
			poke(context, watcherPreference = value)
		}

		fun onAutoTrackingPreferenceChange(context: Context, value: Int) {
			poke(context, autoTracking = value)
		}

		fun onActivityIntervalPreferenceChange(context: Context, value: Int) {
			poke(context, updateInterval = value)
		}


		/**
		 * Pokes [ActivityWatcherService] which checks if it should run
		 *
		 * Note: This method cannot use preference observer, because it needs context.
		 *
		 * @param context context
		 */
		@Synchronized
		@Suppress("LongParameterList")
		fun poke(
				context: Context,
				watcherPreference: Boolean = getWatcherPreference(context),
				updateInterval: Int = getActivityIntervalPreference(context),
				autoTracking: Int = getAutoTrackingPreference(context),
				trackerLocked: Boolean = TrackerLocker.isLocked.value,
				trackerRunning: Boolean = TrackerService.isServiceRunning.value
		) {

			if (updateInterval > 0 && autoTracking > 0) {
				if (watcherPreference && !trackerLocked && !trackerRunning) {
					if (instance == null) {
						context.startForegroundService<ActivityWatcherService> { }
					}
					return
				}
			}

			instance?.stopSelf()
		}
	}
}

