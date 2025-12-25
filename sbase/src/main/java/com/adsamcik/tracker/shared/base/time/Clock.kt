package com.adsamcik.tracker.shared.base.time

import androidx.annotation.Keep

/**
 * Abstraction over system time to enable deterministic testing.
 * Provides current time in milliseconds and elapsed realtime in nanoseconds.
 */
@Keep
interface Clock {
    /**
     * Current wall-clock time in milliseconds since epoch.
     * Equivalent to System.currentTimeMillis().
     */
    fun currentTimeMillis(): Long

    /**
     * Elapsed realtime in nanoseconds since boot.
     * Equivalent to SystemClock.elapsedRealtimeNanos().
     */
    fun elapsedRealtimeNanos(): Long
}

/**
 * Default implementation using actual system clocks.
 */
@Keep
object SystemClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    
    override fun elapsedRealtimeNanos(): Long = 
        android.os.SystemClock.elapsedRealtimeNanos()
}

/**
 * Test implementation with fixed time values.
 * Allows controlled time progression in tests.
 */
@Keep
class FixedClock(
    private var fixedTimeMillis: Long = 0L,
    private var fixedRealtimeNanos: Long = 0L
) : Clock {
    override fun currentTimeMillis(): Long = fixedTimeMillis
    
    override fun elapsedRealtimeNanos(): Long = fixedRealtimeNanos
    
    /**
     * Advance the clock by the specified milliseconds.
     */
    fun advance(millis: Long) {
        fixedTimeMillis += millis
        fixedRealtimeNanos += millis * 1_000_000L // Convert to nanos
    }
    
    /**
     * Set absolute time values.
     */
    fun setTime(millis: Long, nanos: Long = millis * 1_000_000L) {
        fixedTimeMillis = millis
        fixedRealtimeNanos = nanos
    }
}
