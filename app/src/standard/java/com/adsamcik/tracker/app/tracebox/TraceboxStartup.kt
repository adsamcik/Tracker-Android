package com.adsamcik.tracker.app.tracebox

import android.app.Application

/** Standard Tracker builds deliberately contain no Tracebox runtime. */
object TraceboxStartup {
    fun install(application: Application) = Unit
}
