package com.adsamcik.tracker.stats.api.platform

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
actual fun elapsedRealtimeMillis(): Long = System.nanoTime() / 1_000_000
