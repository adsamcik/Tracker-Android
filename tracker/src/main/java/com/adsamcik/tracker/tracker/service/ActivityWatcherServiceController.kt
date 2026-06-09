package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.adsamcik.tracker.shared.base.extension.startForegroundServiceSafely
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
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
	private val trackerServiceController: TrackerServiceController,
	private val lockManagerProvider: Provider<LockManager>,
) {
	private val tag = "ActivityWatcherService"

	@Volatile
	internal var serviceInstance: ActivityWatcherService? = null

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
	@Synchronized
	fun poke(
		watcherPreference: Boolean = BackgroundTrackingApi.activityWatcherEnabled,
		updateInterval: Int = BackgroundTrackingApi.activityFreqSeconds,
		autoTracking: Int = BackgroundTrackingApi.cachedParams.autoTrackingMode,
		trackerLocked: Boolean = currentTrackerLocked(),
		trackerRunning: Boolean = trackerServiceController.isServiceRunning,
	) {
		if (updateInterval > 0 && autoTracking > 0) {
			if (watcherPreference && !trackerLocked && !trackerRunning) {
				if (serviceInstance == null) {
					if (!canStartForegroundService()) {
						Log.i(tag, "Skipping ActivityWatcherService start: app not in foreground")
						return
					}
					context.startForegroundServiceSafely<ActivityWatcherService> { }
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
