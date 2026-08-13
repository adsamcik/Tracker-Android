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
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
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

		if (!startForegroundCompat(updateNotification())) {
			stopSelf()
			return
		}

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
		if (!hasActivityPermission) {
			stopSelf(startId)
			return START_NOT_STICKY
		}
		return START_REDELIVER_INTENT
	}

	private fun startForegroundCompat(notification: Notification): Boolean {
		val candidates = activityWatcherForegroundServiceTypeCandidates(
			sdkInt = Build.VERSION.SDK_INT,
			hasActivityPermission = hasActivityPermission,
		)
		if (candidates.isEmpty()) return false
		val type = candidates.single()
		return startForegroundTolerant(sdkInt = Build.VERSION.SDK_INT) {
			if (type != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(
					NOTIFICATION_ID,
					notification,
					type,
				)
			} else {
				startForeground(NOTIFICATION_ID, notification)
			}
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

/**
 * Runs [start] and reports whether the platform's foreground-service start restrictions blocked
 * it, mirroring [TrackerService]'s `tryStartForeground`.
 *
 * On Android 12+ ([Build.VERSION_CODES.S]) a background-restricted start throws
 * `ForegroundServiceStartNotAllowedException`, checked by class name to avoid an API-31 type
 * reference. A [SecurityException] can also surface if the declared foreground-service type is no
 * longer permitted. Both are tolerated rejections; every other exception propagates.
 *
 * @return `true` when [start] completed without a tolerated rejection.
 */
internal inline fun startForegroundTolerant(
	sdkInt: Int,
	start: () -> Unit,
): Boolean = try {
	start()
	true
} catch (_: SecurityException) {
	false
} catch (@Suppress("TooGenericExceptionCaught") exception: RuntimeException) {
	if (!isForegroundServiceStartRestriction(sdkInt, exception::class.java.name)) throw exception
	false
}

/**
 * True when [exceptionClassName] identifies `android.app.ForegroundServiceStartNotAllowedException`
 * on an SDK where that platform exception can actually be thrown ([Build.VERSION_CODES.S]+).
 *
 * Matching by name (rather than `is ForegroundServiceStartNotAllowedException`) avoids referencing
 * an API-31 type directly, since this codebase's `minSdk` predates it.
 */
internal fun isForegroundServiceStartRestriction(sdkInt: Int, exceptionClassName: String): Boolean =
	sdkInt >= Build.VERSION_CODES.S &&
		exceptionClassName == "android.app.ForegroundServiceStartNotAllowedException"

/** Activity recognition satisfies the Android 14+ health foreground-service prerequisite. */
internal fun activityWatcherForegroundServiceTypeCandidates(
	sdkInt: Int,
	hasActivityPermission: Boolean,
): List<Int?> = when {
	!hasActivityPermission -> emptyList()
	sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
		listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
	else -> listOf(null)
}
