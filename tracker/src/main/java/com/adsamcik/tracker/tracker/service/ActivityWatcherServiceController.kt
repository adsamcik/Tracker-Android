package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.adsamcik.tracker.shared.base.extension.startForegroundService
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point used by the static bridge in [ActivityWatcherService.Companion.poke]
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
     * stops it accordingly.  All parameters have sensible Hilt-resolved defaults
     * so callers only need to override the value they are reacting to.
     */
    @Synchronized
    fun poke(
        watcherPreference: Boolean = BackgroundTrackingApi.activityWatcherEnabled,
        updateInterval: Int = BackgroundTrackingApi.activityFreqSeconds,
        autoTracking: Int = BackgroundTrackingApi.cachedParams.autoTrackingMode,
        trackerLocked: Boolean = EntryPointAccessors
            .fromApplication(context.applicationContext, ActivityWatcherEntryPoint::class.java)
            .lockManager().isLocked,
        trackerRunning: Boolean = EntryPointAccessors
            .fromApplication(context.applicationContext, ActivityWatcherEntryPoint::class.java)
            .trackerServiceController().isServiceRunning,
    ) {
        if (updateInterval > 0 && autoTracking > 0) {
            if (watcherPreference && !trackerLocked && !trackerRunning) {
                if (serviceInstance == null) {
                    if (!canStartForegroundService()) {
                        Log.i(tag, "Skipping ActivityWatcherService start: app not in foreground")
                        return
                    }
                    try {
                        context.startForegroundService<ActivityWatcherService> { }
                    } catch (exception: SecurityException) {
                        Log.w(tag, "Activity watcher start blocked by security policy", exception)
                    } catch (exception: RuntimeException) {
                        val isForegroundStartRestricted =
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                    exception::class.java.name ==
                                    "android.app.ForegroundServiceStartNotAllowedException"
                        if (isForegroundStartRestricted) {
                            Log.w(tag, "Skipped starting ActivityWatcherService from background-restricted context")
                        } else {
                            throw exception
                        }
                    }
                }
                return
            }
        }
        serviceInstance?.stopSelf()
    }

    private fun canStartForegroundService(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
    }
}
