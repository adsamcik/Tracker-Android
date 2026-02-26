package com.adsamcik.tracker.tracker.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.base.extension.startForegroundService
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.controller.LockManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.*
import javax.inject.Inject
import kotlin.concurrent.scheduleAtFixedRate

/**
 * Hilt EntryPoint for accessing dependencies from static context (companion object methods)
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ActivityWatcherEntryPoint {
	fun trackerServiceController(): TrackerServiceController
	fun lockManager(): LockManager
}

/**
 * Service used to keep device and ActivityService alive while automatic tracking might launch
 */
@AndroidEntryPoint
class ActivityWatcherService : CoreService() {
	private var activityInfo: ActivityInfo = ActivityRequestManager.lastActivity

	@Inject
	lateinit var trackerServiceController: TrackerServiceController
	
	@Inject
	lateinit var lockManager: LockManager

	private val timer: Timer = Timer()

	private lateinit var notificationManager: NotificationManager

	override fun onCreate() {
		super.onCreate()

		instance = this

		val updatePreferenceInSeconds = BackgroundTrackingApi.activityFreqSeconds

		startForeground(NOTIFICATION_ID, updateNotification())

		notificationManager = (this as Context).notificationManager

		BackgroundTrackingApi.initialize(this)
		poke(this)

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
		private const val NOTIFICATION_ID = -568465

		private var instance: ActivityWatcherService? = null

		/**
		 * Called when watcher preference is changed.
		 */
		fun onWatcherPreferenceChange(context: Context, value: Boolean) {
			poke(context, watcherPreference = value)
		}

		/**
		 * Called when auto tracking preference is changed.
		 */
		fun onAutoTrackingPreferenceChange(context: Context, value: Int) {
			poke(context, autoTracking = value)
		}

		/**
		 * Called when activity interval preference is changed.
		 */
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
			watcherPreference: Boolean = BackgroundTrackingApi.activityWatcherEnabled,
			updateInterval: Int = BackgroundTrackingApi.activityFreqSeconds,
			autoTracking: Int = BackgroundTrackingApi.cachedParams.autoTrackingMode,
			trackerLocked: Boolean = dagger.hilt.android.EntryPointAccessors
				.fromApplication(context.applicationContext, ActivityWatcherEntryPoint::class.java)
				.lockManager().isLocked,
			trackerRunning: Boolean = dagger.hilt.android.EntryPointAccessors
				.fromApplication(context.applicationContext, ActivityWatcherEntryPoint::class.java)
				.trackerServiceController().isServiceRunning
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

