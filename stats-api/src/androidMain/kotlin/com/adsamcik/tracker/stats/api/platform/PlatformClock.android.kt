package com.adsamcik.tracker.stats.api.platform

import android.os.SystemClock

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
actual fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
