package com.adsamcik.tracker.app.tracebox

import android.app.Application
import dev.tracebox.Tracebox
import dev.tracebox.TraceboxConfiguration
import dev.tracebox.api.TraceboxHandle

/**
 * Installed from `Application.attachBaseContext()` in every Tracker process. The runtime remains
 * fail-closed until the user enables it in Settings; the persisted choice is only written after a
 * successful explicit profile update.
 */
object TraceboxStartup {
    @Volatile
    private var handle: TraceboxHandle? = null

    fun install(application: Application) {
        handle = Tracebox.install(
            application,
            TraceboxConfiguration.Builder()
                .setProcessRole(TraceboxConfiguration.DEFAULT_PROCESS_ROLE)
                .setPersistRequestedProfile(true)
                .build(),
        )
    }

    fun requireHandle(application: Application): TraceboxHandle =
        handle ?: Tracebox.install(
            application,
            TraceboxConfiguration.Builder()
                .setProcessRole(TraceboxConfiguration.DEFAULT_PROCESS_ROLE)
                .setPersistRequestedProfile(true)
                .build(),
        ).also { handle = it }
}
