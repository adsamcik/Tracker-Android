package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point used by static callers such as [BackgroundTrackingApi]
 * to resolve this controller without constructor injection.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ActivityWatcherControllerEntryPoint {
	fun activityWatcherServiceController(): ActivityWatcherServiceController
}

/**
 * Compatibility bridge for the removed Activity watcher foreground service.
 *
 * Activity Recognition continuity is owned by the provider PendingIntent and its registration
 * arbiter. Keeping a second indefinite foreground service alive for the same provider adds no
 * durable authority, so legacy callers may still poke this bridge but can no longer start work.
 */
@Singleton
class ActivityWatcherServiceController @Inject constructor(
	@ApplicationContext private val context: Context,
) : ActivityWatcherController {
	override fun poke() = Unit

	/**
	 * Preserves the released preference contract. Activity-recognition registration itself is
	 * reconciled by [BackgroundTrackingApi]'s TrackingParams observer.
	 */
	override fun applyAutoTrackingMode(mode: Int) {
		require(mode >= 0) { "Automatic tracking mode must not be negative" }
		val enabled = mode > 0
		Preferences(context).edit {
			setBoolean(
				context.getString(com.adsamcik.tracker.activity.R.string.settings_activity_watcher_key),
				enabled,
			)
		}
	}

	override fun pauseForDataDeletion() = Unit

	override fun resumeAfterDataDeletion() = Unit

	@Suppress("UNUSED_PARAMETER")
	fun poke(
		watcherPreference: Boolean = BackgroundTrackingApi.activityWatcherEnabled,
		updateInterval: Int = BackgroundTrackingApi.activityFreqSeconds,
		autoTracking: Int = BackgroundTrackingApi.cachedParams.autoTrackingMode,
		trackerLocked: Boolean = false,
		trackerRunning: Boolean = false,
	) = Unit
}
