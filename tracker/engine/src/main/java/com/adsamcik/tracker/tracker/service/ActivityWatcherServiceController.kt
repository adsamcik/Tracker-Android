package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.adsamcik.tracker.shared.base.extension.startForegroundServiceSafely
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Provider
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
 * Hilt singleton that owns the mutable [ActivityWatcherService] instance reference
 * and the [poke] decision logic.
 *
 * The service registers/unregisters itself via [attachService]/[detachService].
 * Hilt-injected callers (Application, TrackerService, DefaultLockManager) use this
 * class directly; non-Hilt callers go through the static bridge in
 * [ActivityWatcherService.Companion].
 */
@Singleton
class ActivityWatcherServiceController @Inject constructor(
	@ApplicationContext private val context: Context,
	private val trackerStateReader: TrackerStateReader,
	private val lockManagerProvider: Provider<LockManager>,
) : ActivityWatcherController {
	@Volatile
	internal var serviceInstance: ActivityWatcherService? = null

	@Volatile
	private var dataDeletionPaused = false

	/** Called by [ActivityWatcherService.onCreate]. */
	fun attachService(service: ActivityWatcherService) {
		serviceInstance = service
	}

	/** Called by [ActivityWatcherService.onDestroy]. */
	fun detachService() {
		serviceInstance = null
	}

	/**
	 * Evaluates whether the watcher service should be running and starts or
	 * stops it accordingly. All parameters have sensible cached or injected defaults
	 * so callers only need to override the value they are reacting to.
	 */
	override fun poke() {
		if (dataDeletionPaused) {
			serviceInstance?.stopSelf()
			return
		}
		poke(
			watcherPreference = BackgroundTrackingApi.activityWatcherEnabled,
			updateInterval = BackgroundTrackingApi.activityFreqSeconds,
			autoTracking = BackgroundTrackingApi.cachedParams.autoTrackingMode,
			trackerLocked = currentTrackerLocked(),
			trackerRunning = trackerStateReader.isServiceRunning,
		)
	}

	/**
	 * Applies the user-facing mode to the legacy watcher-service bridge immediately.
	 * Activity-recognition registration itself is reconciled by [BackgroundTrackingApi]'s
	 * TrackingParams observer; this keeps the optional foreground watcher in the same state.
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
		poke(watcherPreference = enabled, autoTracking = mode)
	}

	override fun pauseForDataDeletion() {
		dataDeletionPaused = true
		serviceInstance?.stopSelf()
	}

	override fun resumeAfterDataDeletion() {
		dataDeletionPaused = false
		poke()
	}

	@Synchronized
	fun poke(
		watcherPreference: Boolean = BackgroundTrackingApi.activityWatcherEnabled,
		updateInterval: Int = BackgroundTrackingApi.activityFreqSeconds,
		autoTracking: Int = BackgroundTrackingApi.cachedParams.autoTrackingMode,
		trackerLocked: Boolean = currentTrackerLocked(),
		trackerRunning: Boolean = trackerStateReader.isServiceRunning,
	) {
		if (dataDeletionPaused) {
			serviceInstance?.stopSelf()
			return
		}
		if (updateInterval > 0 && autoTracking > 0) {
			if (watcherPreference && !trackerLocked && !trackerRunning) {
				if (serviceInstance == null) {
					if (!canStartForegroundService()) {
						return
					}
					context.startForegroundServiceSafely<ActivityWatcherService>()
				}
				return
			}
		}
		serviceInstance?.stopSelf()
	}

	private fun currentTrackerLocked(): Boolean = lockManagerProvider.get().isLocked

	private fun canStartForegroundService(): Boolean {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
		return ProcessLifecycleOwner.get().lifecycle.currentState
			.isAtLeast(Lifecycle.State.STARTED)
	}
}
