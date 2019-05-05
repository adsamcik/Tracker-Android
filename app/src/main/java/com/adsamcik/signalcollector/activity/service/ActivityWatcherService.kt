package com.adsamcik.signalcollector.activity.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.GroupedActivity
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.app.activity.LaunchActivity
import com.adsamcik.signalcollector.common.misc.extension.notificationManager
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.adsamcik.signalcollector.tracker.service.TrackerService
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate

/**
 * Service used to keep device and ActivityService alive while automatic tracking might launch
 */
class ActivityWatcherService : LifecycleService() {
	private var activityInfo = ActivityService.lastActivity

	private val timer: Timer = Timer()

	private lateinit var notificationManager: NotificationManager

	override fun onCreate() {
		super.onCreate()

		instance = this

		val updatePreferenceInSeconds = Preferences.getPref(this).getIntResString(R.string.settings_activity_freq_key, R.string.settings_activity_freq_default)

		startForeground(NOTIFICATION_ID, updateNotification())
		ActivityService.requestAutoTracking(this, this::class, updatePreferenceInSeconds)

		notificationManager = (this as Context).notificationManager

		timer.scheduleAtFixedRate(0L, updatePreferenceInSeconds * Constants.SECOND_IN_MILLISECONDS) {
			val newActivityInfo = ActivityService.lastActivity
			if (newActivityInfo != activityInfo) {
				activityInfo = newActivityInfo
				notificationManager.notify(NOTIFICATION_ID, updateNotification())
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		ActivityService.removeActivityRequest(this, this::class)
		instance = null
		timer.cancel()
	}

	override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		return START_REDELIVER_INTENT
	}

	private fun updateNotification(): Notification {
		val intent = Intent(this, LaunchActivity::class.java)
		val builder = NotificationCompat.Builder(this, getString(R.string.channel_activity_watcher_id))
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setTicker(getString(R.string.notification_tracker_active_ticker))  // the done text
				.setWhen(System.currentTimeMillis())  // the time stamp
				.setVibrate(null)
				.setContentIntent(PendingIntent.getActivity(this, 0, intent, 0)) // The intent to send when the entry is clicked
				.setColor(ContextCompat.getColor(this, R.color.color_accent))

		builder.setContentTitle(getString(R.string.settings_activity_watcher_title))
		builder.setContentText(getString(R.string.notification_activity_watcher_info, activityInfo.getGroupedActivityName(this), activityInfo.confidence))
		when (activityInfo.groupedActivity) {
			GroupedActivity.IN_VEHICLE -> builder.setSmallIcon(R.drawable.ic_directions_car_white_24dp)
			GroupedActivity.ON_FOOT -> builder.setSmallIcon(R.drawable.ic_directions_walk_white_24dp)
			GroupedActivity.STILL -> builder.setSmallIcon(R.drawable.ic_accessibility_white_24dp)
			GroupedActivity.UNKNOWN -> builder.setSmallIcon(R.drawable.ic_help_white_24dp)
		}

		return builder.build()
	}

	companion object {
		private const val NOTIFICATION_ID = -568465

		private var instance: ActivityWatcherService? = null

		private fun getWatcherPreference(context: Context): Boolean = Preferences.getPref(context).getBooleanRes(R.string.settings_activity_watcher_key, R.string.settings_activity_watcher_default)

		private fun getAutoTrackingPreference(context: Context): Int = Preferences.getPref(context).getIntResString(R.string.settings_tracking_activity_key, R.string.settings_tracking_activity_default)

		private fun getActivityIntervalPreference(context: Context): Int = Preferences.getPref(context).getIntResString(R.string.settings_activity_freq_key, R.string.settings_activity_freq_default)

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
		fun poke(context: Context,
		         watcherPreference: Boolean = getWatcherPreference(context),
		         updateInterval: Int = getActivityIntervalPreference(context),
		         autoTracking: Int = getAutoTrackingPreference(context),
		         trackerLocked: Boolean = TrackerLocker.isLocked.value,
		         trackerRunning: Boolean = TrackerService.isServiceRunning.value) {

			if (updateInterval > 0 && autoTracking > 0) {
				ActivityService.requestAutoTracking(context, LaunchActivity::class, updateInterval)
				if (watcherPreference && !trackerLocked && !trackerRunning) {
					if (instance == null)
						ContextCompat.startForegroundService(context, Intent(context, ActivityWatcherService::class.java))
					return
				}
			}

			instance?.stopSelf()
		}
	}
}
